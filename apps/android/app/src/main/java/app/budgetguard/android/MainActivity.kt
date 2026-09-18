package app.budgetguard.android

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.budgetguard.android.dashboard.Account
import app.budgetguard.android.dashboard.Budget
import app.budgetguard.android.dashboard.Entity
import app.budgetguard.android.dashboard.Invoice
import app.budgetguard.android.dashboard.MobileDashboard
import app.budgetguard.android.dashboard.PlannedItem
import app.budgetguard.android.dashboard.Transaction
import app.budgetguard.android.dashboard.budgetSummary
import app.budgetguard.android.dashboard.cashflowSummary
import app.budgetguard.android.dashboard.formatPeriodRange
import app.budgetguard.android.dashboard.formatTransactionDate
import app.budgetguard.android.dashboard.formatZar
import app.budgetguard.android.dashboard.summariseSafeToSpend
import app.budgetguard.android.sms.AccountMessageCandidate
import app.budgetguard.android.sms.SmsAccountScanner
import app.budgetguard.android.sync.CollectorStatus
import app.budgetguard.android.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

class MainActivity : ComponentActivity() {
    private enum class Screen { HOME, TRANSACTIONS, INVOICES, ACCOUNTS, PROFILE }
    private enum class AuthMode { SIGN_IN, SIGN_UP }

    private val applicationState by lazy { application as BudgetGuardApplication }
    private var selectedScreen = Screen.HOME
    private var authMode = AuthMode.SIGN_IN
    private var onboardingStep = 0
    private var selectedEntityId: String? = null
    private var selectedPeriodStart: String? = null
    private var userEmail: String? = null
    private var dashboard: MobileDashboard? = null
    private var collectorStatus = CollectorStatus("idle", null, 0)
    private var capturedCount = 0
    private var pendingCount = 0
    private var collectorHealthText: TextView? = null
    private var permissionAction: TextView? = null
    private var pendingAccountScan: Pair<String, String>? = null

    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateCollectorWidgets()
    }

    private val smsHistoryPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingAccountScan
        pendingAccountScan = null
        if (granted && request != null) {
            scanMessageAccounts(request.first, request.second)
        } else if (!granted) {
            AlertDialog.Builder(this)
                .setTitle("Message search wasn't enabled")
                .setMessage("You can still add accounts manually and new-message auto-detection will keep working. Start the search again whenever you want to grant access.")
                .setPositiveButton("Okay", null)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        observeCollectorState()
        restoreSession()
    }

    override fun onResume() {
        super.onResume()
        updateCollectorWidgets()
    }

    private fun restoreSession() {
        val client = applicationState.collectorClient
        if (client == null) {
            renderConfigurationRequired()
            return
        }
        renderLoading("Opening your ledger…")
        lifecycleScope.launch {
            val email = runCatching { client.currentUserEmail() }.getOrNull()
            if (email == null) {
                renderAuth()
            } else {
                userEmail = email
                loadDashboard()
            }
        }
    }

    private fun loadDashboard(keepContentVisible: Boolean = false) {
        val client = applicationState.collectorClient ?: return
        if (!keepContentVisible || dashboard == null) renderLoading("Reading your budget period…")
        lifecycleScope.launch {
            runCatching { client.loadDashboard(selectedEntityId, selectedPeriodStart) }
                .onSuccess {
                    dashboard = it
                    selectedEntityId = it.entity.id
                    selectedPeriodStart = it.period.startsOn
                    if (client.isOnboardingComplete()) renderDashboard() else renderOnboarding(it)
                }
                .onFailure {
                    if (dashboard == null) renderError("We couldn't refresh your ledger.", it.message)
                    else Toast.makeText(this@MainActivity, "Refresh failed. Try again shortly.", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun renderAuth(message: String? = null) {
        dashboard = null
        userEmail = null
        val content = pageColumn(horizontal = 26, top = 42, bottom = 34)
        content.gravity = Gravity.CENTER_HORIZONTAL

        content.addView(brandMark(72))
        content.addView(label("BUDGETGUARD", 12f, Palette.moss, bold = true).withTopMargin(18))
        content.addView(label("Your money,\nin the present tense.", 35f, Palette.ink, bold = true).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 0.94f)
        }.withTopMargin(10))
        content.addView(label("Everyday transactions become calm, useful guardrails—right where they happen.", 16f, Palette.muted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }.withTopMargin(16))

        val panel = card(Palette.canvas, radius = 28, padding = 20).withTopMargin(34) as LinearLayout
        panel.addView(label(if (authMode == AuthMode.SIGN_IN) "Welcome back" else "Create your vault", 25f, Palette.ink, bold = true))
        panel.addView(label(if (authMode == AuthMode.SIGN_IN) "Sign in to see this month's position." else "One private workspace across phone and web.", 14f, Palette.muted).withTopMargin(5))

        val tabs = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        tabs.addView(tab("Sign in", authMode == AuthMode.SIGN_IN) {
            authMode = AuthMode.SIGN_IN
            renderAuth()
        })
        tabs.addView(tab("Create account", authMode == AuthMode.SIGN_UP) {
            authMode = AuthMode.SIGN_UP
            renderAuth()
        })
        panel.addView(tabs.withTopMargin(18))

        val emailInput = input("Email address", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).apply {
            imeOptions = EditorInfo.IME_ACTION_NEXT
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            setAutofillHints(View.AUTOFILL_HINT_EMAIL_ADDRESS)
        }
        val passwordInput = input("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            imeOptions = EditorInfo.IME_ACTION_NEXT
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
        }
        val confirmInput = input("Confirm password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        panel.addView(emailInput.withTopMargin(20))
        panel.addView(passwordInput.withTopMargin(10))
        if (authMode == AuthMode.SIGN_UP) panel.addView(confirmInput.withTopMargin(10))

        val feedback = label(message.orEmpty(), 13f, Palette.coral).apply {
            visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        panel.addView(feedback.withTopMargin(10))

        val submit = action(if (authMode == AuthMode.SIGN_IN) "Open my dashboard  →" else "Create my account  →", primary = true)
        submit.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString()
            val confirm = confirmInput.text.toString()
            val validation = when {
                !email.contains("@") -> "Enter a valid email address."
                password.length < 8 -> "Use at least eight characters for your password."
                authMode == AuthMode.SIGN_UP && password != confirm -> "Those passwords don't match."
                else -> null
            }
            if (validation != null) {
                feedback.text = validation
                feedback.visibility = View.VISIBLE
                return@setOnClickListener
            }
            submit.isEnabled = false
            submit.alpha = 0.55f
            feedback.visibility = View.GONE
            lifecycleScope.launch {
                val result = runCatching {
                    if (authMode == AuthMode.SIGN_IN) applicationState.collectorClient!!.signIn(email, password)
                    else applicationState.collectorClient!!.signUp(email, password)
                }
                result.onSuccess {
                    userEmail = applicationState.collectorClient?.currentUserEmail()
                    if (userEmail == null) renderAuth("Your account was created. Check your email to confirm it, then sign in.")
                    else {
                        SyncScheduler.enqueueNow(this@MainActivity)
                        loadDashboard()
                    }
                }.onFailure {
                    submit.isEnabled = true
                    submit.alpha = 1f
                    feedback.text = friendlyAuthError(it.message)
                    feedback.visibility = View.VISIBLE
                }
            }
        }
        panel.addView(submit.withTopMargin(16))
        content.addView(panel)

        content.addView(label("▣  Raw bank messages never leave this phone. Only normalized transaction fields sync to your private workspace.", 12f, Palette.muted).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }.withTopMargin(24))
        setInsetContentView(ScrollView(this).apply { setBackgroundColor(Palette.paper); addView(content) })
    }

    private fun renderOnboarding(data: MobileDashboard) {
        collectorHealthText = null
        permissionAction = null
        val content = pageColumn(horizontal = 24, top = 30, bottom = 34)
        content.addView(horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(brandMark(50))
            addView(label("SET UP BUDGETGUARD", 12f, Palette.moss, bold = true).apply {
                letterSpacing = 0.09f
                setPadding(dp(13), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("${onboardingStep + 1} / 3", 12f, Palette.muted, bold = true))
        })

        when (onboardingStep) {
            0 -> {
                content.addView(label("A budget that starts with how you actually bank.", 33f, Palette.ink, bold = true).apply {
                    setLineSpacing(0f, 0.98f)
                }.withTopMargin(34))
                content.addView(label("You're signed in as ${userEmail ?: "your BudgetGuard account"}. Next, tell us about the accounts you want to track and what each one is normally used for.", 15f, Palette.muted).apply {
                    setLineSpacing(dp(3).toFloat(), 1f)
                }.withTopMargin(14))
                val promise = card(Palette.ink, radius = 26, padding = 20)
                promise.addView(label("AUTO-DETECTION STAYS ON", 11f, Palette.mint, bold = true).apply { letterSpacing = 0.09f })
                promise.addView(label("If a supported SMS mentions an account you haven't entered, BudgetGuard will add it conservatively and ask you to review its purpose.", 15f, Color.WHITE).apply {
                    setLineSpacing(dp(3).toFloat(), 1f)
                }.withTopMargin(10))
                promise.addView(label("Raw SMS bodies remain on this phone.", 12f, Palette.inkMuted).withTopMargin(16))
                content.addView(promise.withTopMargin(28))
                content.addView(action("Start setup  →", primary = true).apply {
                    setOnClickListener {
                        onboardingStep = 1
                        renderOnboarding(data)
                    }
                }.withTopMargin(24))
            }

            1 -> {
                content.addView(label("What should we call you?", 31f, Palette.ink, bold = true).withTopMargin(34))
                content.addView(label("This name stays in your private BudgetGuard profile.", 14f, Palette.muted).withTopMargin(8))
                val displayName = input("Display name").apply { setText(data.profileDisplayName) }
                content.addView(displayName.withTopMargin(24))
                content.addView(action("Continue to accounts  →", primary = true).apply {
                    setOnClickListener {
                        val name = displayName.text.toString().trim()
                        if (name.isBlank()) {
                            Toast.makeText(this@MainActivity, "Enter a display name.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        isEnabled = false
                        lifecycleScope.launch {
                            runCatching { applicationState.collectorClient?.updateProfile(name) }
                                .onSuccess {
                                    onboardingStep = 2
                                    loadDashboard()
                                }
                                .onFailure {
                                    isEnabled = true
                                    Toast.makeText(this@MainActivity, "Couldn't save your profile.", Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                }.withTopMargin(16))
                content.addView(action("Back", primary = false).apply {
                    setOnClickListener {
                        onboardingStep = 0
                        renderOnboarding(data)
                    }
                }.withTopMargin(8))
            }

            else -> {
                content.addView(label("Choose the accounts to track.", 31f, Palette.ink, bold = true).withTopMargin(34))
                content.addView(label("Edit the suggested accounts, add your own, and describe what each one is typically used for. You can change these later.", 14f, Palette.muted).apply {
                    setLineSpacing(dp(3).toFloat(), 1f)
                }.withTopMargin(8))
                val detection = card(Palette.sage, radius = 20, padding = 16)
                detection.addView(label("● Auto-detection is active", 14f, Palette.ink, bold = true))
                detection.addView(label("New SMS account identifiers will be added without interrupting transaction capture.", 12f, Palette.inkSoft).withTopMargin(4))
                content.addView(detection.withTopMargin(18))
                data.accounts.forEach { account -> content.addView(accountCard(account).withTopMargin(10)) }
                content.addView(action("Find accounts in messages", primary = true).apply {
                    setOnClickListener { showBankAccountSearch(data) }
                }.withTopMargin(12))
                content.addView(action("Add another account", primary = false).apply {
                    setOnClickListener { showAccountEditor(null) }
                }.withTopMargin(8))
                content.addView(action("Finish setup", primary = true).apply {
                    setOnClickListener {
                        if (data.accounts.isEmpty()) {
                            Toast.makeText(this@MainActivity, "Add at least one account to continue.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        applicationState.collectorClient?.finishOnboarding()
                        selectedScreen = Screen.HOME
                        renderDashboard()
                    }
                }.withTopMargin(22))
                content.addView(action("Back", primary = false).apply {
                    setOnClickListener {
                        onboardingStep = 1
                        renderOnboarding(data)
                    }
                }.withTopMargin(8))
            }
        }

        setInsetContentView(ScrollView(this).apply {
            setBackgroundColor(Palette.paper)
            addView(content)
        })
    }

    private fun renderDashboard() {
        val data = dashboard ?: return
        collectorHealthText = null
        permissionAction = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.paper)
        }
        val scroller = ScrollView(this).apply {
            isFillViewport = true
            addView(when (selectedScreen) {
                Screen.HOME -> buildHome(data)
                Screen.TRANSACTIONS -> buildTransactions(data)
                Screen.INVOICES -> buildInvoices(data)
                Screen.ACCOUNTS -> buildAccounts(data)
                Screen.PROFILE -> buildProfileAndSync(data)
            })
        }
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setInsetContentView(root)
        updateCollectorWidgets()
    }

    private fun buildHome(data: MobileDashboard): View {
        val content = pageColumn(horizontal = 20, top = 22, bottom = 28)
        content.addView(buildHeader("Home"))

        val sts = data.summariseSafeToSpend()
        val cashflow = data.cashflowSummary()
        val selectedMonth = YearMonth.from(LocalDate.parse(data.period.startsOn))
        val heroLabel = when {
            selectedMonth.isAfter(YearMonth.now()) -> "PLANNED PERIOD BUDGET"
            selectedMonth.isBefore(YearMonth.now()) -> "PERIOD BUDGET POSITION"
            else -> "Safe to spend"
        }
        val hero = card(Palette.ink, radius = 30, padding = 22).withTopMargin(22) as LinearLayout
        hero.addView(label(heroLabel, 11f, Palette.mint, bold = true).apply { letterSpacing = 0.11f })
        hero.addView(label(formatZar(sts.safeToSpendCents), 39f, Color.WHITE, bold = true).withTopMargin(7))
        hero.addView(label("Plan leftover (not cash)", 14f, Palette.inkMuted).withTopMargin(7))
        hero.addView(label(formatZar(cashflow.projectedSurplusCents), 12f, Palette.inkMuted).withTopMargin(2))
        hero.addView(label("Use Safe to spend before you buy something.", 14f, Palette.inkMuted).withTopMargin(7))
        val budget = data.budgetSummary()
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = if (budget.limitCents == 0L) 0 else (((budget.spentCents + budget.committedCents) * 100) / budget.limitCents).coerceIn(0, 100).toInt()
            progressTintList = ColorStateList.valueOf(Palette.mint)
            progressBackgroundTintList = ColorStateList.valueOf(Palette.inkRaised)
        }
        hero.addView(progress.withHeight(6).withTopMargin(18))
        hero.addView(label("${formatZar(budget.spentCents)} spent  ·  ${formatZar(budget.committedCents)} pending", 12f, Palette.inkMuted).withTopMargin(8))
        content.addView(hero)

        val signalRow = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        signalRow.addView(metricCard("PERIOD END", formatZar(cashflow.projectedSurplusCents), "projected surplus", Palette.sage), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        signalRow.addView(space(10))
        signalRow.addView(metricCard("REVIEW", data.transactionsNeedingReview.toString(), "transactions", Palette.peach).apply {
            setOnClickListener {
                selectedScreen = Screen.TRANSACTIONS
                renderDashboard()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(signalRow.withTopMargin(12))

        if (data.invoiceInbox != null) {
            val invoiceSignal = card(if (data.invoicesNeedingReview > 0) Palette.peach else Palette.sage, radius = 22, padding = 17)
            val invoiceTop = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
            invoiceTop.addView(vertical().apply {
                addView(label("INVOICE INBOX", 10f, Palette.moss, bold = true).apply { letterSpacing = 0.09f })
                addView(label(
                    if (data.invoicesNeedingReview > 0) "${data.invoicesNeedingReview} ${if (data.invoicesNeedingReview == 1) "invoice needs" else "invoices need"} review"
                    else "Forward PDF invoices from your email",
                    15f,
                    Palette.ink,
                    bold = true,
                ).withTopMargin(5))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            invoiceTop.addView(label("Open  →", 12f, Palette.moss, bold = true))
            invoiceSignal.addView(invoiceTop)
            invoiceSignal.setOnClickListener {
                selectedScreen = Screen.INVOICES
                renderDashboard()
            }
            content.addView(invoiceSignal.withTopMargin(12))
        }

        content.addView(sectionHeading("Period plan", "${cashflow.allocationPercentage}% allocated").withTopMargin(28))
        val plan = card(Palette.canvas, radius = 22, padding = 18)
        plan.addView(moneyLine("Carried forward", formatZar(data.period.carryoverCents), Palette.moss))
        plan.addView(divider().withVerticalMargin(12))
        val incomeItems = data.plannedItems.filter { it.direction == "income" }
        plan.addView(planGroupHeading("MONEY IN", cashflow.plannedIncomeCents, Palette.moss))
        if (incomeItems.isEmpty()) {
            plan.addView(label("No income planned yet.", 12f, Palette.muted).withTopMargin(9))
        } else {
            incomeItems.forEachIndexed { index, item ->
                plan.addView(plannedIncomeRow(data, item).withTopMargin(if (index == 0) 10 else 0))
                if (index < incomeItems.lastIndex) plan.addView(divider().withVerticalMargin(10))
            }
        }
        plan.addView(divider().withVerticalMargin(12))
        val expenseItems = data.plannedItems.filter { it.direction == "expense" }
        plan.addView(planGroupHeading("MONEY OUT", cashflow.plannedExpenseCents, Palette.coral))
        if (expenseItems.isEmpty()) {
            plan.addView(label("No expenses planned yet.", 12f, Palette.muted).withTopMargin(9))
        } else {
            expenseItems.forEachIndexed { index, item ->
                plan.addView(plannedExpenseRow(data, item).withTopMargin(if (index == 0) 10 else 0))
                if (index < expenseItems.lastIndex) plan.addView(divider().withVerticalMargin(10))
            }
        }
        val planActions = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        planActions.addView(action("+  Income", primary = false).apply {
            setOnClickListener { showIncomeEditor(data, null) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        planActions.addView(space(10))
        planActions.addView(action("+  Expense", primary = true).apply {
            setOnClickListener { showExpenseEditor(data, null) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        plan.addView(planActions.withTopMargin(16))
        content.addView(plan.withTopMargin(12))

        content.addView(sectionHeading("Flexible budgets", if (data.budgets.isEmpty()) "Not set up" else "Selected period").withTopMargin(28))
        if (data.budgets.isEmpty()) {
            content.addView(emptyCard("Your monthly plan is connected. Flexible category limits can still be managed in the web companion while the mobile planner is completed.").withTopMargin(12))
        } else {
            data.budgets.sortedByDescending(Budget::percentage).take(4).forEach { budgetItem ->
                content.addView(budgetRow(data, budgetItem).withTopMargin(10))
            }
        }

        content.addView(sectionHeading("Recent movements", "See all →").apply {
            setOnClickListener {
                selectedScreen = Screen.TRANSACTIONS
                renderDashboard()
            }
        }.withTopMargin(28))
        if (data.transactions.isEmpty()) {
            content.addView(emptyCard("New supported Absa and FNB transactions will appear here after SMS access is enabled.").withTopMargin(12))
        } else {
            val recent = card(Palette.canvas, radius = 22, padding = 4)
            data.transactions.take(4).forEachIndexed { index, transaction ->
                recent.addView(transactionRow(data, transaction, compact = true))
                if (index < minOf(3, data.transactions.lastIndex)) recent.addView(divider(16))
            }
            content.addView(recent.withTopMargin(12))
        }

        return content
    }

    private fun buildTransactions(data: MobileDashboard): View {
        val content = pageColumn(horizontal = 20, top = 22, bottom = 28)
        content.addView(buildHeader("Activity"))
        content.addView(label("Every movement.", 31f, Palette.ink, bold = true).withTopMargin(22))
        content.addView(label("Tap a category to sort a payment. Pending card reservations stay separate from posted spend.", 14f, Palette.muted).withTopMargin(7))

        if (data.transactionsNeedingReview > 0) {
            val notice = card(Palette.peach, radius = 20, padding = 17)
            notice.addView(label("${data.transactionsNeedingReview} need a quick decision", 16f, Palette.ink, bold = true))
            notice.addView(label("Categorise outgoing payments so your daily guardrail stays honest.", 13f, Palette.inkSoft).withTopMargin(4))
            content.addView(notice.withTopMargin(18))
        }

        if (data.transactions.isEmpty()) {
            content.addView(emptyCard("No transactions in this period. BudgetGuard will add supported Absa and FNB notifications automatically.").withTopMargin(20))
        } else {
            data.transactions.forEach { transaction ->
                content.addView(card(Palette.canvas, radius = 20, padding = 3).apply {
                    addView(transactionRow(data, transaction, compact = false))
                }.withTopMargin(10))
            }
        }
        return content
    }

    private fun buildAccounts(data: MobileDashboard): View {
        val content = pageColumn(horizontal = 20, top = 22, bottom = 28)
        content.addView(buildHeader("Accounts"))
        content.addView(label("Every account has a job.", 31f, Palette.ink, bold = true).withTopMargin(22))
        content.addView(label("These accounts belong to ${data.entity.name}. The SMS identifier must match the account label in the bank notification; FNB accounts use the visible suffix.", 14f, Palette.muted).withTopMargin(7))
        val accountActions = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        accountActions.addView(action("Find in messages", primary = true).apply {
            setOnClickListener { showBankAccountSearch(data) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        accountActions.addView(space(10))
        accountActions.addView(action("Add manually", primary = false).apply {
            setOnClickListener { showAccountEditor(null) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(accountActions.withTopMargin(16))

        val messageSearch = card(Palette.sage, radius = 20, padding = 16)
        messageSearch.addView(label("PRIVATE, ON-DEMAND SEARCH", 10f, Palette.moss, bold = true).apply { letterSpacing = 0.08f })
        messageSearch.addView(label("Choose a bank, review what was found, then assign only the accounts that belong to ${data.entity.name}. Message text stays on this phone.", 13f, Palette.inkSoft).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        }.withTopMargin(6))
        content.addView(messageSearch.withTopMargin(14))

        val attention = data.accounts.count(Account::mappingNeedsAttention)
        if (attention > 0) {
            val notice = card(Palette.peach, radius = 20, padding = 17)
            notice.addView(label("$attention SMS ${if (attention == 1) "mapping needs" else "mappings need"} attention", 16f, Palette.ink, bold = true))
            notice.addView(label("Review the highlighted account identifiers so SMS balances and transactions match correctly.", 13f, Palette.inkSoft).withTopMargin(4))
            content.addView(notice.withTopMargin(18))
        }

        val grouped = data.accounts.groupBy { if (it.role == "operational") "Everyday accounts" else "Long-term & liabilities" }
        listOf("Everyday accounts", "Long-term & liabilities").forEach { groupName ->
            val accounts = grouped[groupName].orEmpty()
            if (accounts.isEmpty()) return@forEach
            content.addView(sectionHeading(groupName, "${accounts.size}").withTopMargin(24))
            accounts.forEach { account -> content.addView(accountCard(account).withTopMargin(10)) }
        }

        return content
    }

    private fun buildInvoices(data: MobileDashboard): View {
        val content = pageColumn(horizontal = 20, top = 22, bottom = 28)
        content.addView(buildHeader("Invoices"))
        content.addView(label("Expenses that come to you.", 31f, Palette.ink, bold = true).withTopMargin(22))
        content.addView(label("Forward an email with a PDF invoice. BudgetGuard extracts the supplier, amount, dates and bank details, then waits for your approval before changing the plan.", 14f, Palette.muted).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }.withTopMargin(7))

        val inbox = data.invoiceInbox
        if (inbox == null) {
            content.addView(emptyCard("Your private invoice address is still being prepared. Refresh in a moment.").withTopMargin(20))
        } else {
            val addressCard = card(Palette.ink, radius = 26, padding = 20)
            addressCard.addView(label("YOUR PRIVATE BUDGETGUARD INBOX", 10f, Palette.mint, bold = true).apply { letterSpacing = 0.1f })
            addressCard.addView(label(inbox.address, 16f, Color.WHITE, bold = true).apply {
                setTextIsSelectable(true)
                setLineSpacing(dp(3).toFloat(), 1f)
            }.withTopMargin(11))
            addressCard.addView(label("Keep this address private. Anyone who has it can submit an invoice to your review queue.", 12f, Palette.inkMuted).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
            }.withTopMargin(9))
            addressCard.addView(action("Copy forwarding address", primary = false).apply {
                background = rounded(Palette.inkRaised, 17)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("BudgetGuard invoice inbox", inbox.address))
                    Toast.makeText(this@MainActivity, "Invoice address copied.", Toast.LENGTH_SHORT).show()
                }
            }.withTopMargin(16))
            content.addView(addressCard.withTopMargin(20))
        }

        val needsReview = data.invoices.filter { it.status == "needs_review" }
        val processed = data.invoices.filter { it.status != "needs_review" }
        content.addView(sectionHeading("Needs review", needsReview.size.toString()).withTopMargin(28))
        if (needsReview.isEmpty()) {
            content.addView(emptyCard("No invoices are waiting. Forward a PDF invoice to the private address above and refresh this page.").withTopMargin(12))
        } else {
            needsReview.forEach { invoice -> content.addView(invoiceCard(data, invoice, actionable = true).withTopMargin(10)) }
        }

        if (processed.isNotEmpty()) {
            content.addView(sectionHeading("Processed", processed.size.toString()).withTopMargin(28))
            processed.take(12).forEach { invoice -> content.addView(invoiceCard(data, invoice, actionable = false).withTopMargin(10)) }
        }
        return content
    }

    private fun invoiceCard(data: MobileDashboard, invoice: Invoice, actionable: Boolean): View {
        val root = card(Palette.canvas, radius = 22, padding = 18)
        val top = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(label(invoice.supplierName?.take(1)?.uppercase() ?: "?", 17f, Palette.ink, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(if (actionable) Palette.peach else Palette.sage, 16)
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        top.addView(vertical().apply {
            setPadding(dp(12), 0, dp(8), 0)
            addView(label(invoice.supplierName ?: "Supplier needs confirmation", 16f, Palette.ink, bold = true).apply { maxLines = 1 })
            val reference = invoice.invoiceNumber?.let { "Invoice $it" } ?: invoice.originalFileName
            addView(label(reference, 11f, Palette.muted).apply { maxLines = 1 }.withTopMargin(3))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val amount = invoice.outstandingCents ?: invoice.totalCents
        top.addView(label(amount?.let { formatInvoiceAmount(invoice.currency, it) } ?: "Amount?", 15f, Palette.ink, bold = true).apply {
            gravity = Gravity.END
        })
        root.addView(top)

        val meta = buildList {
            invoice.dueDate?.let { add("Due ${formatTransactionDate(it)}") }
            if (invoice.extractionStatus == "needs_ocr") add("Scan needs checking")
            add("${(invoice.extractionConfidence * 100).toInt()}% extracted")
        }.joinToString(" · ")
        root.addView(label(meta, 11f, if (invoice.extractionStatus == "complete") Palette.moss else Palette.coral, bold = true).withTopMargin(12))
        if (invoice.warnings.isNotEmpty() && actionable) {
            root.addView(label(invoice.warnings.first(), 12f, Palette.muted).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
            }.withTopMargin(7))
        }
        if (actionable) {
            root.addView(action("Review and plan payment  →", primary = true).apply {
                setOnClickListener { showInvoiceReview(data, invoice) }
            }.withTopMargin(14))
        } else {
            root.addView(label(invoice.status.replace('_', ' ').replaceFirstChar(Char::uppercase), 11f, Palette.moss, bold = true).withTopMargin(12))
        }
        return root
    }

    private fun showInvoiceReview(data: MobileDashboard, invoice: Invoice) {
        val container = vertical().apply { setPadding(dp(22), dp(4), dp(22), dp(16)) }
        container.addView(label("Confirm the extracted details, choose who owns the expense, then decide how much to include in this budget period.", 13f, Palette.muted).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        val supplier = input("Supplier or company name").apply { setText(invoice.supplierName.orEmpty()) }
        val invoiceNumber = input("Invoice number (optional)").apply { setText(invoice.invoiceNumber.orEmpty()) }
        val outstanding = invoice.outstandingCents ?: invoice.totalCents
        val amount = input("Amount to pay this period", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            outstanding?.let { setText(BigDecimal.valueOf(it, 2).toPlainString()) }
        }
        val entitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, data.entities.map { it.name })
            val current = invoice.entityId ?: data.entity.id
            setSelection(data.entities.indexOfFirst { it.id == current }.coerceAtLeast(0))
            background = rounded(Palette.paper, 16, Palette.line)
            minimumHeight = dp(52)
            setPadding(dp(10), 0, dp(10), 0)
        }
        val categoryOptions = listOf("Uncategorised") + data.categories.map { it.name }
        val categorySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, categoryOptions)
            background = rounded(Palette.paper, 16, Palette.line)
            minimumHeight = dp(52)
            setPadding(dp(10), 0, dp(10), 0)
        }
        val accountOptions = listOf("No payment account yet") + data.accounts.map { "${it.institution.uppercase()} · ${it.name}" }
        val accountSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, accountOptions)
            background = rounded(Palette.paper, 16, Palette.line)
            minimumHeight = dp(52)
            setPadding(dp(10), 0, dp(10), 0)
        }
        container.addView(supplier.withTopMargin(16))
        container.addView(invoiceNumber.withTopMargin(10))
        container.addView(amount.withTopMargin(10))
        container.addView(label("Entity", 11f, Palette.muted, bold = true).withTopMargin(14))
        container.addView(entitySpinner.withTopMargin(5))
        container.addView(label("Category", 11f, Palette.muted, bold = true).withTopMargin(12))
        container.addView(categorySpinner.withTopMargin(5))
        container.addView(label("Payment account · optional", 11f, Palette.muted, bold = true).withTopMargin(12))
        container.addView(accountSpinner.withTopMargin(5))

        val details = card(Palette.sage, radius = 18, padding = 15)
        details.addView(label("EXTRACTED INVOICE DETAILS", 10f, Palette.moss, bold = true).apply { letterSpacing = 0.08f })
        invoice.issueDate?.let { details.addView(moneyLine("Invoice date", formatTransactionDate(it), Palette.ink).withTopMargin(10)) }
        invoice.dueDate?.let { details.addView(moneyLine("Due date", formatTransactionDate(it), Palette.ink).withTopMargin(8)) }
        invoice.supplierRegistrationNumber?.let { details.addView(moneyLine("Company reg", it, Palette.ink).withTopMargin(8)) }
        invoice.supplierVatNumber?.let { details.addView(moneyLine("VAT number", it, Palette.ink).withTopMargin(8)) }
        invoice.paymentReference?.let { details.addView(moneyLine("Reference", it, Palette.ink).withTopMargin(8)) }
        invoice.bankName?.let { details.addView(moneyLine("Bank", it, Palette.ink).withTopMargin(8)) }
        invoice.bankAccountHolder?.let { details.addView(moneyLine("Account holder", it, Palette.ink).withTopMargin(8)) }
        invoice.bankAccountNumber?.let { details.addView(moneyLine("Account number", it, Palette.ink).withTopMargin(8)) }
        invoice.bankBranchCode?.let { details.addView(moneyLine("Branch code", it, Palette.ink).withTopMargin(8)) }
        if (invoice.bankAccountNumber != null) {
            details.addView(label("Bank details from an invoice remain unverified. Confirm them independently before any payment.", 11f, Palette.coral, bold = true).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
            }.withTopMargin(12))
        }
        container.addView(details.withTopMargin(16))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Review invoice")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Add to plan", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reject", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val supplierName = supplier.text.toString().trim()
                val paymentCents = parseMoneyCents(amount.text.toString())
                val maximum = invoice.outstandingCents ?: invoice.totalCents
                if (supplierName.isBlank() || paymentCents == null || (maximum != null && paymentCents > maximum)) {
                    Toast.makeText(this, "Enter a supplier and an amount no higher than the outstanding balance.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val entity = data.entities[entitySpinner.selectedItemPosition]
                val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                save.isEnabled = false
                lifecycleScope.launch {
                    runCatching {
                        val client = applicationState.collectorClient ?: error("BudgetGuard is not configured.")
                        val target = if (entity.id == data.entity.id) data else client.loadDashboard(entity.id, data.period.startsOn)
                        client.updateInvoiceDraft(invoice.id, supplierName, invoiceNumber.text.toString(), maximum ?: paymentCents)
                        client.approveInvoice(
                            invoiceId = invoice.id,
                            entityId = entity.id,
                            periodId = target.period.id,
                            categoryId = data.categories.getOrNull(categorySpinner.selectedItemPosition - 1)?.id,
                            accountId = if (entity.id == data.entity.id) data.accounts.getOrNull(accountSpinner.selectedItemPosition - 1)?.id else null,
                            paymentCents = paymentCents,
                        )
                        entity.id
                    }.onSuccess { entityId ->
                        dialog.dismiss()
                        selectedEntityId = entityId
                        selectedPeriodStart = data.period.startsOn
                        selectedScreen = Screen.INVOICES
                        Toast.makeText(this@MainActivity, "Invoice added to this period's expenses.", Toast.LENGTH_LONG).show()
                        loadDashboard(keepContentVisible = true)
                    }.onFailure {
                        save.isEnabled = true
                        Toast.makeText(this@MainActivity, "Couldn't add this invoice to the plan.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Reject this invoice?")
                    .setMessage("It will leave the review queue but remain in processed invoices.")
                    .setPositiveButton("Reject") { _, _ ->
                        lifecycleScope.launch {
                            runCatching { applicationState.collectorClient?.rejectInvoice(invoice.id) }
                                .onSuccess {
                                    dialog.dismiss()
                                    loadDashboard(keepContentVisible = true)
                                }
                                .onFailure { Toast.makeText(this@MainActivity, "Couldn't reject this invoice.", Toast.LENGTH_LONG).show() }
                        }
                    }
                    .setNegativeButton("Keep reviewing", null)
                    .show()
            }
        }
        dialog.show()
    }

    private fun formatInvoiceAmount(currency: String, cents: Long): String =
        if (currency == "ZAR") formatZar(cents) else "$currency ${BigDecimal.valueOf(cents, 2).toPlainString()}"

    private fun buildProfileAndSync(data: MobileDashboard): View {
        val content = pageColumn(horizontal = 20, top = 22, bottom = 28)
        content.addView(buildHeader("Profile & sync"))
        content.addView(label("Your private workspace.", 31f, Palette.ink, bold = true).withTopMargin(22))
        content.addView(label("Account identity, phone permissions, and background delivery in one place.", 14f, Palette.muted).withTopMargin(7))

        val profile = card(Palette.ink, radius = 26, padding = 20)
        val profileRow = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        profileRow.addView(label(initials(), 18f, Palette.ink, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(Palette.mint, 20)
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val identity = vertical().apply {
            setPadding(dp(14), 0, 0, 0)
            addView(label(data.profileDisplayName.ifBlank { "BudgetGuard member" }, 15f, Color.WHITE, bold = true).apply { maxLines = 1 })
            addView(label(userEmail ?: "BudgetGuard account", 11f, Palette.inkMuted).apply { maxLines = 1 }.withTopMargin(4))
        }
        profileRow.addView(identity, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        profile.addView(profileRow)
        content.addView(profile.withTopMargin(20))

        content.addView(sectionHeading("Phone collector", "").withTopMargin(28))
        val sync = card(Palette.canvas, radius = 22, padding = 18)
        collectorHealthText = label("", 15f, Palette.ink, bold = true).apply { setLineSpacing(dp(4).toFloat(), 1f) }
        sync.addView(collectorHealthText)
        sync.addView(divider().withVerticalMargin(14))
        sync.addView(moneyLine("Stored on this phone", "$capturedCount transactions", Palette.ink))
        sync.addView(moneyLine("Waiting to upload", "$pendingCount", if (pendingCount > 0) Palette.coral else Palette.moss).withTopMargin(10))
        permissionAction = action("Allow SMS access", primary = false).apply {
            setOnClickListener { smsPermission.launch(Manifest.permission.RECEIVE_SMS) }
        }
        sync.addView(permissionAction!!.withTopMargin(16))
        sync.addView(action("Sync now", primary = true).apply {
            setOnClickListener {
                applicationState.statusStore.markQueued()
                SyncScheduler.enqueueNow(this@MainActivity)
            }
        }.withTopMargin(8))
        content.addView(sync.withTopMargin(12))

        content.addView(sectionHeading("Privacy boundary", "").withTopMargin(28))
        content.addView(emptyCard("Raw SMS bodies never leave this phone. BudgetGuard stores and syncs only normalized transaction fields. Sender names can be spoofed, so captured messages remain bookkeeping input—not proof of payment.").withTopMargin(12))

        content.addView(sectionHeading("Workspace", "").withTopMargin(28))
        val workspace = card(Palette.canvas, radius = 22, padding = 18)
        workspace.addView(moneyLine("Active entity", data.entity.name, Palette.moss))
        workspace.addView(divider().withVerticalMargin(12))
        workspace.addView(moneyLine("Active period", data.month, Palette.ink))
        workspace.addView(divider().withVerticalMargin(12))
        workspace.addView(moneyLine("Accounts", data.accounts.size.toString(), Palette.ink))
        workspace.addView(divider().withVerticalMargin(12))
        workspace.addView(moneyLine("Categories", data.categories.size.toString(), Palette.ink))
        workspace.addView(divider().withVerticalMargin(12))
        workspace.addView(moneyLine("Entities", data.entities.size.toString(), Palette.ink))
        content.addView(workspace.withTopMargin(12))
        return content
    }

    private fun showSideNavigation(data: MobileDashboard) {
        val dialog = Dialog(this)
        val shell = vertical().apply {
            setBackgroundColor(Palette.ink)
            setPadding(dp(20), dp(22), dp(20), dp(18))
        }

        val top = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(brandMark(48).apply {
            background = rounded(Palette.mint, 17)
            setTextColor(Palette.ink)
        })
        val brand = vertical().apply {
            setPadding(dp(12), 0, 0, 0)
            addView(label("BUDGETGUARD", 13f, Color.WHITE, bold = true).apply { letterSpacing = 0.09f })
            addView(label("Mobile ledger", 12f, Palette.inkMuted).withTopMargin(3))
        }
        top.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(label("×", 28f, Palette.inkMuted).apply {
            gravity = Gravity.CENTER
            contentDescription = "Close navigation"
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        shell.addView(top)

        val profile = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Palette.inkRaised, 20)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        profile.addView(label(initials(), 15f, Palette.ink, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(Palette.mint, 17)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val identity = vertical().apply {
            setPadding(dp(12), 0, 0, 0)
            addView(label("YOUR PROFILE", 10f, Palette.mint, bold = true).apply { letterSpacing = 0.08f })
            addView(label(userEmail ?: "BudgetGuard account", 13f, Color.WHITE, bold = true).apply { maxLines = 1 }.withTopMargin(4))
        }
        profile.addView(identity, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        profile.setOnClickListener {
            selectedScreen = Screen.PROFILE
            dialog.dismiss()
            renderDashboard()
        }
        shell.addView(profile.withTopMargin(24))

        shell.addView(label("ACTIVE ENTITY", 10f, Palette.inkMuted, bold = true).apply { letterSpacing = 0.1f }.withTopMargin(24))
        val entity = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Palette.inkRaised, 18)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(label(entityInitials(data.entity.name), 14f, Palette.ink, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(Palette.sage, 14)
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            val copy = vertical().apply {
                setPadding(dp(11), 0, 0, 0)
                addView(label(data.entity.name, 14f, Color.WHITE, bold = true).apply { maxLines = 1 })
                addView(label("${entityKindLabel(data.entity.kind)} · ${data.accounts.size} accounts", 11f, Palette.inkMuted).withTopMargin(3))
            }
            addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("Switch ›", 11f, Palette.mint, bold = true))
            setOnClickListener {
                dialog.dismiss()
                showEntityPicker(data)
            }
        }
        shell.addView(entity.withTopMargin(9))

        shell.addView(label("NAVIGATE", 10f, Palette.inkMuted, bold = true).apply { letterSpacing = 0.1f }.withTopMargin(24))
        shell.addView(drawerNavItem("⌂", "Home", "Today's position", Screen.HOME, dialog).withTopMargin(10))
        shell.addView(drawerNavItem("↕", "Activity", if (data.transactionsNeedingReview > 0) "${data.transactionsNeedingReview} need review" else "Transactions are clear", Screen.TRANSACTIONS, dialog).withTopMargin(6))
        shell.addView(drawerNavItem("▧", "Invoice inbox", if (data.invoicesNeedingReview > 0) "${data.invoicesNeedingReview} need review" else "Forward PDF expenses", Screen.INVOICES, dialog).withTopMargin(6))
        shell.addView(drawerNavItem("▤", "Accounts", "Balances & SMS mappings", Screen.ACCOUNTS, dialog).withTopMargin(6))
        shell.addView(drawerNavItem("●", "Profile & sync", collectorStatus.displayText, Screen.PROFILE, dialog).withTopMargin(6))

        shell.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        shell.addView(label("▣  Private by design\nRaw bank messages stay on this phone.", 11f, Palette.inkMuted).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        shell.addView(action("Sign out", primary = false).apply {
            background = rounded(Color.TRANSPARENT, 18, Palette.inkRaised)
            setTextColor(Color.WHITE)
            setOnClickListener {
                dialog.dismiss()
                performSignOut()
            }
        }.withTopMargin(18))

        dialog.setContentView(shell)
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setDimAmount(0.48f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.START)
        }
        dialog.show()
        dialog.window?.apply {
            setWindowAnimations(R.style.BudgetGuardDrawerAnimation)
            setLayout(minOf((resources.displayMetrics.widthPixels * 0.88f).toInt(), dp(380)), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        applySystemInsets(shell)
    }

    private fun drawerNavItem(icon: String, title: String, detail: String, screen: Screen, dialog: Dialog): View = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(if (selectedScreen == screen) Palette.inkRaised else Color.TRANSPARENT, 18)
        setPadding(dp(13), dp(12), dp(13), dp(12))
        addView(label(icon, 18f, if (selectedScreen == screen) Palette.mint else Palette.inkMuted, bold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(34), dp(38)))
        val copy = vertical().apply {
            setPadding(dp(10), 0, 0, 0)
            addView(label(title, 14f, Color.WHITE, bold = true))
            addView(label(detail, 11f, Palette.inkMuted).apply { maxLines = 1 }.withTopMargin(3))
        }
        addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(label("›", 22f, Palette.inkMuted))
        setOnClickListener {
            selectedScreen = screen
            dialog.dismiss()
            renderDashboard()
        }
    }

    private fun performSignOut() {
        lifecycleScope.launch {
            runCatching { applicationState.collectorClient?.signOut() }
            authMode = AuthMode.SIGN_IN
            selectedScreen = Screen.HOME
            selectedEntityId = null
            selectedPeriodStart = null
            renderAuth()
        }
    }

    private fun initials(): String = userEmail
        ?.substringBefore('@')
        ?.split(Regex("[^A-Za-z0-9]+"))
        ?.filter(String::isNotBlank)
        ?.take(2)
        ?.joinToString("") { it.first().uppercase() }
        ?.ifBlank { "BG" }
        ?: "BG"

    private fun buildHeader(context: String): View {
        val row = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(action("☰", primary = false, compact = true).apply {
            contentDescription = "Open navigation"
            setOnClickListener { dashboard?.let(::showSideNavigation) }
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        row.addView(space(12))
        val title = vertical()
        title.addView(label("BUDGETGUARD", 12f, Palette.moss, bold = true).apply { letterSpacing = 0.11f })
        dashboard?.let { data ->
            title.addView(label("${data.entity.name}  ▾", 14f, Palette.ink, bold = true).apply {
                contentDescription = "Switch budget entity"
                setPadding(0, dp(3), 0, dp(3))
                setOnClickListener { showEntityPicker(data) }
            }.withTopMargin(2))
            title.addView(label("$context · ${data.month}  ▾", 11f, Palette.moss, bold = true).apply {
                contentDescription = "Select budget period"
                setPadding(0, dp(3), 0, dp(3))
                setOnClickListener { showPeriodPicker(data) }
            })
        }
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(action("↻", primary = false, compact = true).apply {
            contentDescription = "Refresh dashboard"
            setOnClickListener { loadDashboard(keepContentVisible = true) }
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        return row
    }

    private fun showPeriodPicker(data: MobileDashboard) {
        val periods = data.periods.sortedByDescending { it.startsOn }
        val latest = periods.maxOfOrNull { YearMonth.from(LocalDate.parse(it.startsOn)) } ?: YearMonth.now()
        val next = maxOf(latest, YearMonth.now()).plusMonths(1).atDay(1).toString()
        val options = periods.map { period ->
            val marker = if (period.startsOn == data.period.startsOn) "✓  " else ""
            "$marker${formatPeriodRange(period.startsOn)}"
        } + "+  Plan ${formatPeriodRange(next)}"

        AlertDialog.Builder(this)
            .setTitle("Select budget period")
            .setItems(options.toTypedArray()) { _, index ->
                selectedPeriodStart = if (index < periods.size) periods[index].startsOn else next
                loadDashboard()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEntityPicker(data: MobileDashboard) {
        val entities = data.entities.sortedWith(compareBy<Entity> { !it.isDefault }.thenBy { it.displayOrder }.thenBy { it.name })
        val options = entities.map { entity ->
            val marker = if (entity.id == data.entity.id) "✓  " else ""
            "$marker${entity.name}  ·  ${entityKindLabel(entity.kind)}"
        } + listOf("✎  Edit ${data.entity.name}", "+  Add another entity")

        AlertDialog.Builder(this)
            .setTitle("Select entity")
            .setItems(options.toTypedArray()) { _, index ->
                when {
                    index < entities.size -> {
                        selectedEntityId = entities[index].id
                        loadDashboard()
                    }
                    index == entities.size -> showEntityEditor(data.entity)
                    else -> showEntityEditor(null)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEntityEditor(entity: Entity?) {
        val kinds = listOf("Personal" to "personal", "Company" to "company", "Other" to "other")
        val container = vertical().apply { setPadding(dp(22), dp(4), dp(22), dp(12)) }
        container.addView(label(
            if (entity == null) "Each entity gets its own periods, plan, accounts, balances, and activity."
            else "Renaming this entity keeps its accounts, plans, and transaction history together.",
            13f,
            Palette.muted,
        ).apply { setLineSpacing(dp(2).toFloat(), 1f) })
        val name = input("Entity name, e.g. Kaleva Media").apply { setText(entity?.name.orEmpty()) }
        val kind = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, kinds.map { it.first })
            setSelection(kinds.indexOfFirst { it.second == entity?.kind }.coerceAtLeast(if (entity == null) 1 else 0))
            background = rounded(Palette.paper, 16, Palette.line)
            minimumHeight = dp(52)
            setPadding(dp(10), 0, dp(10), 0)
        }
        container.addView(name.withTopMargin(16))
        container.addView(kind.withTopMargin(10))
        if (entity?.isDefault == true) {
            container.addView(label("${entity.name} is your default destination for newly auto-detected SMS accounts. You can move them later.", 12f, Palette.moss, bold = true).withTopMargin(10))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (entity == null) "Add entity" else "Edit entity")
            .setView(container)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entityName = name.text.toString().trim()
                if (entityName.isBlank()) {
                    Toast.makeText(this, "Enter an entity name.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                save.isEnabled = false
                lifecycleScope.launch {
                    runCatching {
                        applicationState.collectorClient?.saveEntity(
                            entityId = entity?.id,
                            name = entityName,
                            kind = kinds[kind.selectedItemPosition].second,
                        ) ?: error("BudgetGuard is not configured.")
                    }.onSuccess { savedId ->
                        dialog.dismiss()
                        selectedEntityId = savedId
                        loadDashboard()
                    }.onFailure {
                        save.isEnabled = true
                        Toast.makeText(this@MainActivity, "An entity with that name may already exist.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun entityKindLabel(kind: String): String = when (kind) {
        "personal" -> "Personal"
        "company" -> "Company"
        else -> "Other"
    }

    private fun entityInitials(name: String): String = name
        .split(Regex("[^A-Za-z0-9]+"))
        .filter(String::isNotBlank)
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "BG" }

    private fun plannedIncomeRow(data: MobileDashboard, item: PlannedItem): View = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        val accountName = data.accounts.firstOrNull { it.id == item.accountId }?.name ?: "No account assigned"
        val schedule = buildString {
            append(accountName)
            item.dueDay?.let { append(" · expected day $it") }
            if (item.recurrence == "monthly") append(" · monthly")
        }
        val copy = vertical().apply {
            addView(label(item.name, 14f, Palette.ink, bold = true))
            addView(label(schedule, 11f, Palette.muted).withTopMargin(3))
        }
        addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val amount = vertical().apply {
            gravity = Gravity.END
            addView(label(formatZar(item.plannedCents), 15f, Palette.moss, bold = true).apply { gravity = Gravity.END })
            addView(label("Edit", 11f, Palette.moss, bold = true).apply { gravity = Gravity.END }.withTopMargin(3))
        }
        addView(amount)
        setPadding(0, dp(4), 0, dp(4))
        isClickable = true
        isFocusable = true
        contentDescription = "Edit ${item.name} income"
        setOnClickListener { showIncomeEditor(data, item) }
    }

    private fun showIncomeEditor(data: MobileDashboard, item: PlannedItem?) {
        val container = vertical().apply { setPadding(dp(22), dp(4), dp(22), dp(12)) }
        container.addView(label("Income belongs to the selected period: ${data.month}.", 13f, Palette.muted))
        val name = input("Income name, e.g. Salary").apply { setText(item?.name.orEmpty()) }
        val amount = input("Amount in rand", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            item?.let { setText(BigDecimal.valueOf(it.plannedCents, 2).toPlainString()) }
        }
        val accountOptions = listOf("No account assigned") + data.accounts.map { "${it.institution.uppercase()} · ${it.name}" }
        val accountSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, accountOptions)
            setSelection(item?.accountId?.let { id -> data.accounts.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) } ?: 0)
            background = rounded(Palette.paper, 16, Palette.line)
            minimumHeight = dp(52)
            setPadding(dp(10), 0, dp(10), 0)
        }
        val dueDay = input("Expected day of month (optional)", InputType.TYPE_CLASS_NUMBER).apply {
            item?.dueDay?.let { setText(String.format(Locale.ROOT, "%d", it)) }
        }
        val recurring = CheckBox(this).apply {
            text = getString(R.string.repeat_income_monthly)
            textSize = 14f
            setTextColor(Palette.ink)
            buttonTintList = ColorStateList.valueOf(Palette.moss)
            isChecked = item?.recurrence?.let { it == "monthly" } ?: true
            setPadding(0, dp(5), 0, dp(5))
        }
        container.addView(name.withTopMargin(16))
        container.addView(amount.withTopMargin(10))
        container.addView(accountSpinner.withTopMargin(10))
        container.addView(dueDay.withTopMargin(10))
        container.addView(recurring.withTopMargin(8))

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (item == null) "Add income" else "Edit income")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply { if (item != null) setNeutralButton("Delete income", null) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val incomeName = name.text.toString().trim()
                val cents = parseMoneyCents(amount.text.toString())
                val expectedDay = dueDay.text.toString().trim().takeIf(String::isNotEmpty)?.toIntOrNull()
                val invalidDay = expectedDay != null && expectedDay !in 1..31
                if (incomeName.isBlank() || cents == null || invalidDay) {
                    Toast.makeText(this, "Enter a name, a positive amount, and an optional day from 1 to 31.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                saveButton.isEnabled = false
                lifecycleScope.launch {
                    runCatching {
                        applicationState.collectorClient?.savePlannedIncome(
                            entityId = data.entity.id,
                            periodId = data.period.id,
                            itemId = item?.id,
                            name = incomeName,
                            plannedCents = cents,
                            accountId = data.accounts.getOrNull(accountSpinner.selectedItemPosition - 1)?.id,
                            dueDay = expectedDay,
                            recurrence = if (recurring.isChecked) "monthly" else "once",
                        )
                    }.onSuccess {
                        dialog.dismiss()
                        loadDashboard(keepContentVisible = true)
                    }.onFailure {
                        saveButton.isEnabled = true
                        Toast.makeText(this@MainActivity, "Couldn't save this income.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            if (item != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Delete ${item.name}?")
                        .setMessage("This removes the income from ${data.month} only.")
                        .setPositiveButton("Delete") { _, _ ->
                            lifecycleScope.launch {
                                runCatching { applicationState.collectorClient?.deletePlannedIncome(data.period.id, item.id) }
                                    .onSuccess {
                                        dialog.dismiss()
                                        loadDashboard(keepContentVisible = true)
                                    }
                                    .onFailure {
                                        Toast.makeText(this@MainActivity, "Couldn't delete this income.", Toast.LENGTH_LONG).show()
                                    }
                            }
                        }
                        .setNegativeButton("Keep", null)
                        .show()
                }
            }
        }
        dialog.show()
    }

    private fun plannedExpenseRow(data: MobileDashboard, item: PlannedItem): View = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        val accountName = data.accounts.firstOrNull { it.id == item.accountId }?.name ?: "No account assigned"
        val categoryName = data.categories.firstOrNull { it.id == item.categoryId }?.name ?: "Uncategorised"
        val kindName = when (item.kind) {
            "variable_expense" -> "Variable expense"
            "savings" -> "Savings contribution"
            "debt_payment" -> "Debt payment"
            else -> "Fixed expense"
        }
        val schedule = buildString {
            append(accountName)
            item.dueDay?.let { append(" · due day $it") }
            if (item.recurrence == "monthly") append(" · monthly")
        }
        val copy = vertical().apply {
            addView(label(item.name, 14f, Palette.ink, bold = true))
            addView(label("$kindName · $categoryName", 11f, Palette.coral, bold = true).withTopMargin(3))
            addView(label(schedule, 11f, Palette.muted).withTopMargin(2))
        }
        addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val amount = vertical().apply {
            gravity = Gravity.END
            addView(label(formatZar(item.plannedCents), 15f, Palette.ink, bold = true).apply { gravity = Gravity.END })
            addView(label("Edit", 11f, Palette.moss, bold = true).apply { gravity = Gravity.END }.withTopMargin(3))
        }
        addView(amount)
        setPadding(0, dp(4), 0, dp(4))
        isClickable = true
        isFocusable = true
        contentDescription = "Edit ${item.name} expense"
        setOnClickListener { showExpenseEditor(data, item) }
    }

    private fun showExpenseEditor(data: MobileDashboard, item: PlannedItem?) {
        val expenseTypes = listOf(
            "Fixed expense" to "fixed_expense",
            "Variable expense" to "variable_expense",
            "Savings contribution" to "savings",
            "Debt payment" to "debt_payment",
        )
        val container = vertical().apply { setPadding(dp(22), dp(4), dp(22), dp(12)) }
        container.addView(label("Plan this expense for ${data.month}. Assigning an account and category makes the forecast easier to act on.", 13f, Palette.muted).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        val name = input("Expense name, e.g. Rent").apply { setText(item?.name.orEmpty()) }
        val amount = input("Amount in rand", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            item?.let { setText(BigDecimal.valueOf(it.plannedCents, 2).toPlainString()) }
        }
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, expenseTypes.map { it.first })
            setSelection(expenseTypes.indexOfFirst { it.second == item?.kind }.coerceAtLeast(0))
            background = rounded(Palette.paper, 16, Palette.line)
            minimumHeight = dp(52)
            setPadding(dp(10), 0, dp(10), 0)
        }
        val accountOptions = listOf("No account assigned") + data.accounts.map { "${it.institution.uppercase()} · ${it.name}" }
        val accountSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, accountOptions)
            setSelection(item?.accountId?.let { id -> data.accounts.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) } ?: 0)
            background = rounded(Palette.paper, 16, Palette.line)
            minimumHeight = dp(52)
            setPadding(dp(10), 0, dp(10), 0)
        }
        val categoryOptions = listOf("Uncategorised") + data.categories.map { it.name }
        val categorySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, categoryOptions)
            setSelection(item?.categoryId?.let { id -> data.categories.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) } ?: 0)
            background = rounded(Palette.paper, 16, Palette.line)
            minimumHeight = dp(52)
            setPadding(dp(10), 0, dp(10), 0)
        }
        val dueDay = input("Due day of month (optional)", InputType.TYPE_CLASS_NUMBER).apply {
            item?.dueDay?.let { setText(String.format(Locale.ROOT, "%d", it)) }
        }
        val recurring = CheckBox(this).apply {
            text = getString(R.string.repeat_expense_monthly)
            textSize = 14f
            setTextColor(Palette.ink)
            buttonTintList = ColorStateList.valueOf(Palette.moss)
            isChecked = item?.recurrence?.let { it == "monthly" } ?: true
            setPadding(0, dp(5), 0, dp(5))
        }
        container.addView(name.withTopMargin(16))
        container.addView(amount.withTopMargin(10))
        container.addView(typeSpinner.withTopMargin(10))
        container.addView(accountSpinner.withTopMargin(10))
        container.addView(categorySpinner.withTopMargin(10))
        container.addView(dueDay.withTopMargin(10))
        container.addView(recurring.withTopMargin(8))

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (item == null) "Add expense" else "Edit expense")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply { if (item != null) setNeutralButton("Delete expense", null) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val expenseName = name.text.toString().trim()
                val cents = parseMoneyCents(amount.text.toString())
                val expectedDay = dueDay.text.toString().trim().takeIf(String::isNotEmpty)?.toIntOrNull()
                val invalidDay = expectedDay != null && expectedDay !in 1..31
                if (expenseName.isBlank() || cents == null || invalidDay) {
                    Toast.makeText(this, "Enter a name, a positive amount, and an optional day from 1 to 31.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                saveButton.isEnabled = false
                lifecycleScope.launch {
                    runCatching {
                        applicationState.collectorClient?.savePlannedExpense(
                            entityId = data.entity.id,
                            periodId = data.period.id,
                            itemId = item?.id,
                            name = expenseName,
                            kind = expenseTypes[typeSpinner.selectedItemPosition].second,
                            plannedCents = cents,
                            accountId = data.accounts.getOrNull(accountSpinner.selectedItemPosition - 1)?.id,
                            categoryId = data.categories.getOrNull(categorySpinner.selectedItemPosition - 1)?.id,
                            dueDay = expectedDay,
                            recurrence = if (recurring.isChecked) "monthly" else "once",
                        )
                    }.onSuccess {
                        dialog.dismiss()
                        loadDashboard(keepContentVisible = true)
                    }.onFailure {
                        saveButton.isEnabled = true
                        Toast.makeText(this@MainActivity, "Couldn't save this expense.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            if (item != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Delete ${item.name}?")
                        .setMessage("This removes the expense from ${data.month} only.")
                        .setPositiveButton("Delete") { _, _ ->
                            lifecycleScope.launch {
                                runCatching { applicationState.collectorClient?.deletePlannedExpense(data.period.id, item.id) }
                                    .onSuccess {
                                        dialog.dismiss()
                                        loadDashboard(keepContentVisible = true)
                                    }
                                    .onFailure {
                                        Toast.makeText(this@MainActivity, "Couldn't delete this expense.", Toast.LENGTH_LONG).show()
                                    }
                            }
                        }
                        .setNegativeButton("Keep", null)
                        .show()
                }
            }
        }
        dialog.show()
    }

    private fun parseMoneyCents(value: String): Long? = runCatching {
        BigDecimal(value.replace(",", "").trim())
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()?.takeIf { it > 0 }

    private fun budgetRow(data: MobileDashboard, budget: Budget): View {
        val category = data.categories.firstOrNull { it.id == budget.categoryId }
        val root = card(Palette.canvas, radius = 20, padding = 16)
        root.addView(moneyLine(category?.name ?: "Flexible budget", "${budget.percentage}%", Palette.ink))
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = budget.percentage
            progressTintList = ColorStateList.valueOf(parseColour(category?.colour, Palette.sage))
            progressBackgroundTintList = ColorStateList.valueOf(Palette.line)
        }
        root.addView(progress.withHeight(5).withTopMargin(10))
        root.addView(label("${formatZar(budget.usedCents)} of ${formatZar(budget.limitCents)}", 12f, Palette.muted).withTopMargin(6))
        return root
    }

    private fun transactionRow(data: MobileDashboard, transaction: Transaction, compact: Boolean): View {
        val row = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (compact) 14 else 15), dp(14), dp(if (compact) 14 else 15), dp(14))
        }
        val direction = FrameLayout(this).apply {
            background = rounded(if (transaction.amountCents < 0) Palette.peach else Palette.sage, 16)
            addView(label(if (transaction.amountCents < 0) "↗" else "↙", 18f, Palette.ink, bold = true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(dp(42), dp(42)))
        }
        row.addView(direction, LinearLayout.LayoutParams(dp(42), dp(42)))

        val copy = vertical().apply {
            setPadding(dp(12), 0, dp(8), 0)
            addView(label(transaction.merchant, 14f, Palette.ink, bold = true).apply { maxLines = 1 })
            val account = data.accounts.firstOrNull { it.id == transaction.accountId }?.name ?: "Account"
            addView(label("${formatTransactionDate(transaction.occurredOn)} · $account · ${transaction.status}", 11f, Palette.muted).apply { maxLines = 1 }.withTopMargin(3))
            if (!compact) {
                val category = data.categories.firstOrNull { it.id == transaction.categoryId }
                val categoryButton = action(category?.name ?: "Choose category", primary = false, compact = true).apply {
                    if (category == null) {
                        setTextColor(Palette.coral)
                        background = rounded(Palette.peach, 14)
                    }
                    setOnClickListener { showCategoryPicker(data, transaction) }
                }
                addView(categoryButton.withTopMargin(9))
            }
        }
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label(formatZar(transaction.amountCents, showSign = true), 14f, if (transaction.amountCents < 0) Palette.ink else Palette.moss, bold = true).apply { gravity = Gravity.END })
        return row
    }

    private fun accountCard(account: Account): View {
        val root = card(Palette.canvas, radius = 22, padding = 18)
        val heading = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        val copy = vertical()
        copy.addView(label(account.name, 17f, Palette.ink, bold = true))
        copy.addView(label("${account.institution.uppercase()} · ${account.purpose.ifBlank { account.type.replace('_', ' ') }}", 12f, Palette.muted).withTopMargin(3))
        heading.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        heading.addView(label(formatZar(account.currentBalanceCents), 15f, Palette.ink, bold = true))
        root.addView(heading)

        val mapping = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(if (account.mappingNeedsAttention) Palette.peach else Palette.paper, 16)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val mappingCopy = vertical()
        mappingCopy.addView(label(if (account.mappingNeedsAttention) "SMS mapping needed" else account.externalKey, 13f, if (account.mappingNeedsAttention) Palette.coral else Palette.ink, bold = true))
        mappingCopy.addView(label("Display mask · ${account.mask}", 11f, Palette.muted).withTopMargin(2))
        mapping.addView(mappingCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        mapping.addView(label("Edit →", 12f, Palette.moss, bold = true))
        mapping.setOnClickListener { showAccountEditor(account) }
        root.addView(mapping.withTopMargin(14))
        return root
    }

    private fun showCategoryPicker(data: MobileDashboard, transaction: Transaction) {
        val options = listOf("Uncategorised") + data.categories.map { it.name }
        AlertDialog.Builder(this)
            .setTitle("Sort ${transaction.merchant}")
            .setItems(options.toTypedArray()) { _, index ->
                val categoryId = if (index == 0) null else data.categories[index - 1].id
                updateTransactionCategory(transaction.id, categoryId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateTransactionCategory(transactionId: String, categoryId: String?) {
        lifecycleScope.launch {
            runCatching { applicationState.collectorClient?.updateTransactionCategory(transactionId, categoryId) }
                .onSuccess { loadDashboard(keepContentVisible = true) }
                .onFailure { Toast.makeText(this@MainActivity, "Couldn't update that transaction.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun showBankAccountSearch(data: MobileDashboard) {
        val banks = arrayOf("Absa", "FNB")
        AlertDialog.Builder(this)
            .setTitle("Which bank should we search?")
            .setItems(banks) { _, index ->
                val institution = banks[index].lowercase()
                showMessageSearchDisclosure(institution, data.entity.id, data.entity.name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMessageSearchDisclosure(institution: String, entityId: String, entityName: String) {
        val bankName = institution.uppercase()
        AlertDialog.Builder(this)
            .setTitle("Search $bankName messages?")
            .setMessage(
                "BudgetGuard will search your inbox only when you tap continue, using $bankName text to identify account labels and the newest available balance. " +
                    "You will choose which accounts belong to $entityName before anything is saved. Raw message text stays on this phone and is discarded as the search runs.",
            )
            .setPositiveButton("Continue") { _, _ ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
                    scanMessageAccounts(institution, entityId)
                } else {
                    pendingAccountScan = institution to entityId
                    smsHistoryPermission.launch(Manifest.permission.READ_SMS)
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun scanMessageAccounts(institution: String, entityId: String) {
        val bankName = institution.uppercase()
        val progress = AlertDialog.Builder(this)
            .setTitle("Searching $bankName messages")
            .setMessage("Account identifiers are being extracted locally…")
            .setView(ProgressBar(this).apply { isIndeterminate = true })
            .setCancelable(false)
            .create()
        progress.show()

        lifecycleScope.launch {
            val result = runCatching {
                val candidates = withContext(Dispatchers.IO) {
                    SmsAccountScanner(this@MainActivity).scan(institution)
                }
                val assignments = applicationState.collectorClient?.loadAccountAssignments(institution).orEmpty()
                candidates to assignments
            }
            progress.dismiss()
            result.onSuccess { (candidates, assignments) ->
                if (candidates.isEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("No $bankName accounts found")
                        .setMessage("No supported $bankName account identifiers were present in the inbox. You can still add an account manually, and new-message auto-detection remains active.")
                        .setPositiveButton("Okay", null)
                        .show()
                } else {
                    showDiscoveredAccounts(institution, entityId, candidates, assignments)
                }
            }.onFailure {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("We couldn't search the messages")
                    .setMessage("Message access may have changed, or the inbox provider is temporarily unavailable. Nothing was uploaded or saved.")
                    .setPositiveButton("Okay", null)
                    .show()
            }
        }
    }

    private fun showDiscoveredAccounts(
        institution: String,
        entityId: String,
        candidates: List<AccountMessageCandidate>,
        assignments: List<app.budgetguard.android.sync.AccountAssignment>,
    ) {
        val data = dashboard ?: return
        val targetName = data.entities.firstOrNull { it.id == entityId }?.name ?: data.entity.name
        val entityNames = data.entities.associate { it.id to it.name }
        val assignmentsByKey = assignments.associateBy { it.externalKey }
        val checked = BooleanArray(candidates.size) { index ->
            val assignment = assignmentsByKey[candidates[index].externalKey]
            assignment?.entityId == entityId && assignment.isActive
        }
        val dialog = Dialog(this)
        val sheet = card(Palette.paper, radius = 30, padding = 0).apply {
            clipToOutline = true
        }

        val handle = View(this).apply {
            background = rounded(Palette.line, 2)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
            }
        }
        sheet.addView(handle)

        val header = card(Palette.ink, radius = 26, padding = 20).apply {
            val context = horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(institution.uppercase(), 10f, Palette.mint, bold = true).apply {
                    letterSpacing = 0.12f
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = rounded(Palette.inkRaised, 12)
                })
                addView(label("  MESSAGE DISCOVERY", 10f, Palette.inkMuted, bold = true).apply {
                    letterSpacing = 0.08f
                })
            }
            addView(context)
            addView(label("Choose the accounts\nthat belong here.", 26f, Color.WHITE, bold = true).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
            }.withTopMargin(14))
            addView(label("Assigning to $targetName", 13f, Palette.mint, bold = true).withTopMargin(10))
        }
        sheet.addView(header.withTopMargin(10))

        val list = vertical().apply {
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        var selectedCountLabel: TextView? = null

        candidates.forEachIndexed { index, candidate ->
            val assignment = assignmentsByKey[candidate.externalKey]
            val location = when {
                assignment == null -> "NEW ACCOUNT"
                !assignment.isActive -> "STOPPED · ${entityNames[assignment.entityId]?.uppercase() ?: "OTHER ENTITY"}"
                assignment.entityId == entityId -> "ALREADY IN ${targetName.uppercase()}"
                else -> "MOVE FROM ${entityNames[assignment.entityId]?.uppercase() ?: "OTHER ENTITY"}"
            }
            val locationColour = when {
                assignment?.entityId == entityId && assignment.isActive -> Palette.moss
                assignment != null -> Palette.coral
                else -> Palette.moss
            }
            val row = card(Palette.canvas, radius = 21, padding = 15)
            val top = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
            val selector = label("", 16f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                contentDescription = "Select ${candidate.suggestedName}"
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            }
            top.addView(selector)
            top.addView(space(12))
            top.addView(label(institution.take(1).uppercase(), 16f, Palette.ink, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(Palette.sage, 15)
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            top.addView(space(12))
            top.addView(vertical().apply {
                addView(label(candidate.suggestedName, 16f, Palette.ink, bold = true))
                addView(label(candidate.mask, 11f, Palette.muted).withTopMargin(3))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            candidate.latestBalanceCents?.let { balance ->
                top.addView(vertical().apply {
                    gravity = Gravity.END
                    addView(label(formatZar(balance), 15f, Palette.ink, bold = true).apply { gravity = Gravity.END })
                    addView(label("latest", 10f, Palette.muted).apply { gravity = Gravity.END }.withTopMargin(2))
                })
            }
            row.addView(top)

            val detail = horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(location, 9f, locationColour, bold = true).apply {
                    letterSpacing = 0.07f
                    setPadding(dp(9), dp(5), dp(9), dp(5))
                    background = rounded(if (locationColour == Palette.coral) Palette.peach else Palette.sage, 11)
                })
                addView(label(
                    "  ${candidate.messageCount} ${if (candidate.messageCount == 1) "message" else "messages"} found",
                    11f,
                    Palette.muted,
                ))
            }
            row.addView(detail.withTopMargin(12))

            fun updateSelection() {
                val selected = checked[index]
                selector.text = if (selected) "✓" else ""
                selector.background = rounded(if (selected) Palette.moss else Palette.paper, 10, if (selected) Palette.moss else Palette.line)
                row.background = rounded(if (selected) Palette.sage else Palette.canvas, 21, if (selected) Palette.moss else Palette.line)
                row.contentDescription = "${candidate.suggestedName}, ${if (selected) "selected" else "not selected"}"
            }
            row.setOnClickListener {
                checked[index] = !checked[index]
                updateSelection()
                val selectedCount = checked.count { it }
                selectedCountLabel?.text = if (selectedCount == 0) "Select at least one account" else "$selectedCount selected for $targetName"
            }
            updateSelection()
            list.addView(row.withTopMargin(if (index == 0) 0 else 10))
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(list)
        }
        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = vertical().apply {
            setPadding(dp(16), dp(12), dp(16), dp(18))
        }
        footer.addView(divider())
        val selectedCount = label("", 12f, Palette.muted, bold = true).apply { gravity = Gravity.CENTER }.withTopMargin(12) as TextView
        fun updateSelectedCount() {
            val count = checked.count { it }
            selectedCount.text = if (count == 0) "Select at least one account" else "$count selected for $targetName"
        }
        updateSelectedCount()
        selectedCountLabel = selectedCount
        footer.addView(selectedCount)
        val actions = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(action("Not now", primary = false).apply {
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.38f))
        actions.addView(space(10))
        actions.addView(action(getString(R.string.assign_selected), primary = true).apply {
            setOnClickListener {
                val selected = candidates.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Choose at least one account first.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                isEnabled = false
                alpha = 0.65f
                text = getString(R.string.assigning)
                lifecycleScope.launch {
                    runCatching {
                        applicationState.collectorClient?.assignDiscoveredAccounts(entityId, selected)
                            ?: error("BudgetGuard is not configured.")
                    }.onSuccess { report ->
                        dialog.dismiss()
                        val details = buildList {
                            if (report.created > 0) add("${report.created} added")
                            if (report.moved > 0) add("${report.moved} moved")
                            if (report.reactivated > 0) add("${report.reactivated} restarted")
                        }.ifEmpty { listOf("${selected.size} confirmed") }.joinToString(" · ")
                        Toast.makeText(this@MainActivity, "$targetName: $details", Toast.LENGTH_LONG).show()
                        loadDashboard(keepContentVisible = true)
                    }.onFailure {
                        isEnabled = true
                        alpha = 1f
                        text = getString(R.string.assign_selected)
                        Toast.makeText(this@MainActivity, "Couldn't assign those accounts. Try again shortly.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.62f))
        footer.addView(actions.withTopMargin(10))
        sheet.addView(footer)

        dialog.setContentView(sheet)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.91f).toInt())
                setGravity(Gravity.BOTTOM)
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                attributes = attributes.apply { dimAmount = 0.58f }
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setWindowAnimations(R.style.BudgetGuardSheetAnimation)
                decorView.setPadding(dp(10), 0, dp(10), dp(10))
            }
        }
        dialog.show()
    }

    private fun showAccountEditor(account: Account?) {
        val entities = dashboard?.entities.orEmpty()
        val institutions = listOf("Absa" to "absa", "FNB" to "fnb")
        val accountTypes = listOf(
            "Cheque" to "cheque",
            "Savings" to "savings",
            "Credit card" to "credit_card",
            "Home loan" to "home_loan",
            "Rewards" to "rewards",
            "Cash" to "cash",
        )
        val container = vertical().apply { setPadding(dp(22), dp(4), dp(22), dp(12)) }
        container.addView(label("Assign this account to an entity and give it a clear job. Its balance will update from the latest matching bank SMS.", 13f, Palette.muted).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        })

        val name = input("Account name, e.g. Everyday account").apply { setText(account?.name.orEmpty()) }
        val purpose = input("Typical use, e.g. Salary and debit orders").apply { setText(account?.purpose.orEmpty()) }
        val externalKey = input("SMS identifier, e.g. CHEQ1607 or FNB-4242").apply { setText(account?.externalKey.orEmpty()) }
        val mask = input("Display mask, e.g. •••• 1607").apply { setText(account?.mask.orEmpty()) }
        val entitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                entities.map { "${it.name} · ${entityKindLabel(it.kind)}" },
            )
            val initialId = account?.entityId ?: dashboard?.entity?.id
            setSelection(entities.indexOfFirst { it.id == initialId }.coerceAtLeast(0))
            background = rounded(Palette.paper, 16, Palette.line)
            minimumHeight = dp(52)
            setPadding(dp(10), 0, dp(10), 0)
        }
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                accountTypes.map { it.first },
            )
            setSelection(accountTypes.indexOfFirst { it.second == account?.type }.coerceAtLeast(0))
            background = rounded(Palette.paper, 16, Palette.line)
            minimumHeight = dp(52)
            setPadding(dp(10), 0, dp(10), 0)
        }
        val institutionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                institutions.map { it.first },
            )
            setSelection(institutions.indexOfFirst { it.second == account?.institution }.coerceAtLeast(0))
            background = rounded(Palette.paper, 16, Palette.line)
            minimumHeight = dp(52)
            setPadding(dp(10), 0, dp(10), 0)
        }
        val include = CheckBox(this).apply {
            text = getString(R.string.include_in_safe_to_spend)
            textSize = 14f
            setTextColor(Palette.ink)
            buttonTintList = ColorStateList.valueOf(Palette.moss)
            isChecked = account?.includeInSafeToSpend ?: false
            setPadding(0, dp(5), 0, dp(5))
        }

        container.addView(name.withTopMargin(16))
        container.addView(entitySpinner.withTopMargin(10))
        container.addView(institutionSpinner.withTopMargin(10))
        container.addView(typeSpinner.withTopMargin(10))
        container.addView(purpose.withTopMargin(10))
        container.addView(externalKey.withTopMargin(10))
        container.addView(mask.withTopMargin(10))
        container.addView(include.withTopMargin(8))
        container.addView(label("Auto-detection remains active. New SMS accounts start in Personal and can be reassigned here.", 12f, Palette.moss, bold = true).withTopMargin(8))

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (account == null) "Add account" else "Edit ${account.name}")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply { if (account != null) setNeutralButton("Stop tracking", null) }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val accountName = name.text.toString().trim()
                val accountPurpose = purpose.text.toString().trim()
                val key = externalKey.text.toString().trim()
                val displayMask = mask.text.toString().trim()
                if (accountName.isBlank() || accountPurpose.isBlank() || key.isBlank() || displayMask.isBlank()) {
                    Toast.makeText(this, "Complete the name, purpose, SMS identifier, and display mask.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                saveButton.isEnabled = false
                lifecycleScope.launch {
                    runCatching {
                        applicationState.collectorClient?.saveAccount(
                            accountId = account?.id,
                            entityId = entities.getOrNull(entitySpinner.selectedItemPosition)?.id
                                ?: error("Select an entity first."),
                            institution = institutions[institutionSpinner.selectedItemPosition].second,
                            name = accountName,
                            type = accountTypes[typeSpinner.selectedItemPosition].second,
                            purpose = accountPurpose,
                            externalKey = key,
                            mask = displayMask,
                            includeInSafeToSpend = include.isChecked,
                        )
                    }.onSuccess {
                        dialog.dismiss()
                        applicationState.statusStore.markQueued()
                        SyncScheduler.enqueueNow(this@MainActivity)
                        loadDashboard(keepContentVisible = true)
                    }.onFailure {
                        saveButton.isEnabled = true
                        Toast.makeText(this@MainActivity, "That SMS identifier may already be in use.", Toast.LENGTH_LONG).show()
                    }
                }
            }

            if (account != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    val stopButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    stopButton.isEnabled = false
                    lifecycleScope.launch {
                        runCatching { applicationState.collectorClient?.stopTrackingAccount(account.id) }
                            .onSuccess {
                                dialog.dismiss()
                                loadDashboard(keepContentVisible = true)
                            }
                            .onFailure {
                                stopButton.isEnabled = true
                                Toast.makeText(this@MainActivity, "Couldn't stop tracking this account.", Toast.LENGTH_LONG).show()
                            }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun observeCollectorState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    applicationState.repository.transactionCount,
                    applicationState.repository.pendingSyncCount,
                    applicationState.statusStore.status,
                ) { captured, pending, status -> Triple(captured, pending, status) }
                    .collect { (captured, pending, status) ->
                        capturedCount = captured
                        pendingCount = pending
                        collectorStatus = status
                        updateCollectorWidgets()
                    }
            }
        }
    }

    private fun updateCollectorWidgets() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        collectorHealthText?.text = buildString {
            append(if (granted) "● SMS capture is on" else "○ SMS permission needed")
            append("\n$capturedCount captured · $pendingCount waiting\n")
            append(collectorStatus.displayText)
        }
        permissionAction?.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun renderLoading(message: String) {
        val content = pageColumn(horizontal = 30, top = 0, bottom = 0).apply {
            gravity = Gravity.CENTER
            addView(brandMark(68))
            addView(label(message, 15f, Palette.muted, bold = true).withTopMargin(18))
        }
        setInsetContentView(FrameLayout(this).apply {
            setBackgroundColor(Palette.paper)
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
    }

    private fun renderConfigurationRequired() {
        val content = pageColumn(horizontal = 28, top = 44, bottom = 36)
        content.addView(brandMark(64))
        content.addView(label("This build needs a home.", 32f, Palette.ink, bold = true).withTopMargin(24))
        content.addView(label("Add the BudgetGuard Supabase URL and publishable key to the Android build configuration. Secret and service-role keys must never be placed in the app.", 15f, Palette.muted).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }.withTopMargin(12))
        setInsetContentView(ScrollView(this).apply { setBackgroundColor(Palette.paper); addView(content) })
    }

    private fun renderError(title: String, detail: String?) {
        val content = pageColumn(horizontal = 28, top = 44, bottom = 36)
        content.addView(brandMark(64))
        content.addView(label(title, 29f, Palette.ink, bold = true).withTopMargin(24))
        content.addView(label(detail ?: "Check your connection and try again.", 14f, Palette.muted).withTopMargin(10))
        content.addView(action("Try again", primary = true).apply { setOnClickListener { loadDashboard() } }.withTopMargin(22))
        content.addView(action("Sign out", primary = false).apply {
            setOnClickListener {
                lifecycleScope.launch {
                    runCatching { applicationState.collectorClient?.signOut() }
                    renderAuth()
                }
            }
        }.withTopMargin(8))
        setInsetContentView(ScrollView(this).apply { setBackgroundColor(Palette.paper); addView(content) })
    }

    private fun metricCard(overline: String, value: String, detail: String, colour: Int): LinearLayout = card(colour, radius = 22, padding = 16).apply {
        minimumHeight = dp(126)
        addView(label(overline, 10f, Palette.inkSoft, bold = true).apply { letterSpacing = 0.08f })
        addView(label(value, 22f, Palette.ink, bold = true).apply { maxLines = 1 }.withTopMargin(12))
        addView(label(detail, 12f, Palette.inkSoft).withTopMargin(3))
    }

    private fun sectionHeading(title: String, action: String): View = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label(title, 19f, Palette.ink, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action.isNotBlank()) addView(label(action, 12f, Palette.moss, bold = true))
    }

    private fun moneyLine(name: String, amount: String, amountColour: Int): View = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label(name, 14f, Palette.muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(label(amount, 15f, amountColour, bold = true))
    }

    private fun planGroupHeading(title: String, amountCents: Long, colour: Int): View = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label(title, 10f, colour, bold = true).apply { letterSpacing = 0.09f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(label(formatZar(amountCents), 13f, colour, bold = true))
    }

    private fun emptyCard(message: String): LinearLayout = card(Palette.canvas, radius = 20, padding = 18).apply {
        addView(label(message, 14f, Palette.muted).apply { setLineSpacing(dp(2).toFloat(), 1f) })
    }

    private fun tab(title: String, active: Boolean, onClick: () -> Unit): TextView = label(title, 13f, if (active) Color.WHITE else Palette.muted, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(43)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(if (active) Palette.ink else Color.TRANSPARENT, 15)
        setOnClickListener { onClick() }
    }

    private fun action(title: String, primary: Boolean, compact: Boolean = false): TextView = label(
        title,
        if (compact) 12f else 14f,
        if (primary) Color.WHITE else Palette.ink,
        bold = true,
    ).apply {
        gravity = Gravity.CENTER
        minHeight = dp(if (compact) 38 else 50)
        setPadding(dp(if (compact) 12 else 16), dp(if (compact) 7 else 12), dp(if (compact) 12 else 16), dp(if (compact) 7 else 12))
        background = rounded(if (primary) Palette.moss else Palette.paper, if (compact) 14 else 18, Palette.line)
        isClickable = true
        isFocusable = true
    }

    private fun input(hintText: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(this).apply {
        hint = hintText
        inputType = type
        textSize = 14f
        setTextColor(Palette.ink)
        setHintTextColor(Palette.muted)
        setSingleLine(true)
        background = rounded(Palette.paper, 16, Palette.line)
        setPadding(dp(14), dp(2), dp(14), 0)
        minHeight = dp(52)
    }

    private fun brandMark(size: Int): TextView = label("B", size * 0.42f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        background = rounded(Palette.ink, size / 3)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun pageColumn(horizontal: Int, top: Int, bottom: Int): LinearLayout = vertical().apply {
        setPadding(dp(horizontal), dp(top), dp(horizontal), dp(bottom))
    }

    private fun vertical(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun card(colour: Int, radius: Int, padding: Int): LinearLayout = vertical().apply {
        background = rounded(colour, radius)
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    private fun label(value: String, size: Float, colour: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(colour)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(0f, 1.08f)
    }

    private fun divider(horizontalInset: Int = 0): View = View(this).apply {
        setBackgroundColor(Palette.line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            leftMargin = dp(horizontalInset)
            rightMargin = dp(horizontalInset)
        }
    }

    private fun space(width: Int): Space = Space(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }

    private fun rounded(colour: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(colour)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun parseColour(value: String?, fallback: Int): Int = runCatching { value?.toColorInt() ?: fallback }.getOrDefault(fallback)

    private fun friendlyAuthError(raw: String?): String = when {
        raw.isNullOrBlank() -> "We couldn't connect. Check your details and try again."
        raw.contains("invalid", ignoreCase = true) -> "That email and password combination wasn't recognised."
        raw.contains("network", ignoreCase = true) -> "BudgetGuard couldn't reach the server. Check your connection."
        else -> raw
    }

    private fun View.withTopMargin(value: Int): View = apply {
        val params = (layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(value)
        layoutParams = params
    }

    private fun View.withVerticalMargin(value: Int): View = apply {
        val params = (layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(value)
        params.bottomMargin = dp(value)
        layoutParams = params
    }

    private fun View.withHeight(value: Int): View = apply {
        val params = (layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(value))
        params.height = dp(value)
        layoutParams = params
    }

    private fun setInsetContentView(root: View) {
        applySystemInsets(root)
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun applySystemInsets(view: View) {
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(baseLeft + bars.left, baseTop + bars.top, baseRight + bars.right, baseBottom + bars.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private object Palette {
        val paper = Color.rgb(244, 241, 232)
        val canvas = Color.rgb(255, 253, 247)
        val ink = Color.rgb(25, 31, 26)
        val inkSoft = Color.rgb(61, 70, 61)
        val inkRaised = Color.rgb(48, 59, 50)
        val inkMuted = Color.rgb(184, 197, 185)
        val muted = Color.rgb(103, 109, 101)
        val moss = Color.rgb(52, 99, 71)
        val mint = Color.rgb(173, 226, 188)
        val sage = Color.rgb(210, 230, 199)
        val peach = Color.rgb(246, 217, 196)
        val coral = Color.rgb(168, 70, 51)
        val line = Color.rgb(224, 223, 214)
    }
}
