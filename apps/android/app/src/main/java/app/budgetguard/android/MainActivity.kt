package app.budgetguard.android

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
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
import app.budgetguard.android.dashboard.Debt
import app.budgetguard.android.dashboard.DebtCheckInBalance
import app.budgetguard.android.dashboard.DebtStrategy
import app.budgetguard.android.dashboard.DebtTrajectoryView
import app.budgetguard.android.dashboard.Entity
import app.budgetguard.android.dashboard.ExpenseOrder
import app.budgetguard.android.dashboard.HomeHeroMode
import app.budgetguard.android.dashboard.Invoice
import app.budgetguard.android.dashboard.MobileDashboard
import app.budgetguard.android.dashboard.PlannedItem
import app.budgetguard.android.dashboard.Transaction
import app.budgetguard.android.dashboard.availableBudgetCategories
import app.budgetguard.android.dashboard.budgetSummary
import app.budgetguard.android.dashboard.cashflowSummary
import app.budgetguard.android.dashboard.debtPlan
import app.budgetguard.android.dashboard.debtSpendingAnalysis
import app.budgetguard.android.dashboard.debtTypeLabel
import app.budgetguard.android.dashboard.formatPeriodRange
import app.budgetguard.android.dashboard.formatTransactionDate
import app.budgetguard.android.dashboard.formatZar
import app.budgetguard.android.dashboard.groupPlannedItems
import app.budgetguard.android.dashboard.homeHeroSummary
import app.budgetguard.android.sms.AccountMessageCandidate
import app.budgetguard.android.sms.SmsAccountScanner
import app.budgetguard.android.sync.CollectorStatus
import app.budgetguard.android.sync.DebtReminderScheduler
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
    private enum class Screen { HOME, DEBT, TRANSACTIONS, INVOICES, ACCOUNTS, PROFILE }
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
    private var planSearchQuery = ""
    private var expenseOrder = ExpenseOrder.NAME

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

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "In-app check-ins will still work without notifications.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("open_debt_freedom", false) == true) selectedScreen = Screen.DEBT
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
                    DebtReminderScheduler.sync(
                        this@MainActivity,
                        it.entity.id,
                        it.debtPreferences.reminderEnabled,
                        it.debtPreferences.reminderDay,
                        it.debtCheckIns.maxByOrNull { checkIn -> checkIn.month }?.month,
                    )
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
                Screen.DEBT -> buildDebtFreedom(data)
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

        val cashflow = data.cashflowSummary()
        val heroSummary = data.homeHeroSummary()
        val heroLabel = when (heroSummary.mode) {
            HomeHeroMode.FUTURE_DAILY_PLAN -> if (heroSummary.planPositionCents < 0) {
                "PLANNED DAILY SHORTFALL"
            } else {
                "PLANNED DAILY ALLOWANCE"
            }
            HomeHeroMode.PAST_PLAN_RESULT -> "PERIOD PLAN RESULT"
            HomeHeroMode.CURRENT_SAFE_TO_SPEND -> if (heroSummary.amountCents < 0) {
                "SAFE-TO-SPEND SHORTFALL"
            } else {
                "SAFE TO SPEND"
            }
        }
        val heroDetail = when (heroSummary.mode) {
            HomeHeroMode.FUTURE_DAILY_PLAN -> {
                val position = if (heroSummary.planPositionCents < 0) "shortfall" else "surplus"
                "${formatZar(heroSummary.planPositionCents)} projected $position ÷ ${heroSummary.dayCount} days"
            }
            HomeHeroMode.PAST_PLAN_RESULT -> {
                val position = if (heroSummary.planPositionCents < 0) "shortfall" else "surplus"
                "Projected plan $position (not historical cash)"
            }
            HomeHeroMode.CURRENT_SAFE_TO_SPEND -> {
                if (heroSummary.amountCents < 0) {
                    "Included balances do not cover remaining commitments"
                } else {
                    "After unpaid planned expenses and pending payments"
                }
            }
        }
        val heroGuidance = when (heroSummary.mode) {
            HomeHeroMode.FUTURE_DAILY_PLAN -> "Forecast only — planned income is included; this is not cash."
            HomeHeroMode.PAST_PLAN_RESULT -> "Review activity for what actually happened."
            HomeHeroMode.CURRENT_SAFE_TO_SPEND -> "Based on accounts included in Safe to spend."
        }
        val hero = card(Palette.ink, radius = 30, padding = 22).withTopMargin(22) as LinearLayout
        hero.addView(label(heroLabel, 11f, Palette.mint, bold = true).apply { letterSpacing = 0.11f })
        hero.addView(label(formatZar(heroSummary.amountCents), 39f, Color.WHITE, bold = true).withTopMargin(7))
        hero.addView(label(heroDetail, 14f, Palette.inkMuted).withTopMargin(7))
        hero.addView(label(heroGuidance, 14f, Palette.inkMuted).withTopMargin(7))
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
        val search = input("Search income and expenses").apply { setText(planSearchQuery) }
        val orderOptions = listOf("Name A–Z", "Amount high–low")
        val order = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, orderOptions)
            setSelection(if (expenseOrder == ExpenseOrder.NAME) 0 else 1)
            background = rounded(Palette.paper, 16, Palette.line)
            setPadding(dp(10), 0, dp(10), 0)
            minimumHeight = dp(48)
            contentDescription = "Order expenses"
        }
        plan.addView(search)
        plan.addView(vertical().apply {
            addView(label("ORDER EXPENSES", 9f, Palette.muted, bold = true).apply { letterSpacing = 0.08f })
            addView(order.withTopMargin(5))
        }.withTopMargin(10))

        val planResults = vertical()
        plan.addView(planResults.withTopMargin(15))

        fun renderPlanResults() {
            planResults.removeAllViews()
            val groups = groupPlannedItems(data.plannedItems, planSearchQuery, expenseOrder)
            planResults.addView(planGroupHeading("MONEY IN", groups.income.sumOf { it.plannedCents }, Palette.moss))
            if (groups.income.isEmpty()) {
                planResults.addView(label(if (planSearchQuery.isBlank()) "No income planned yet." else "No income matches your search.", 12f, Palette.muted).withTopMargin(9))
            } else {
                groups.income.forEachIndexed { index, item ->
                    planResults.addView(plannedIncomeRow(data, item).withTopMargin(if (index == 0) 10 else 0))
                    if (index < groups.income.lastIndex) planResults.addView(divider().withVerticalMargin(10))
                }
            }

            planResults.addView(divider().withVerticalMargin(14))
            planResults.addView(planGroupHeading("TO PAY", groups.unpaidExpenses.sumOf { it.plannedCents }, Palette.coral))
            if (groups.unpaidExpenses.isEmpty()) {
                val message = if (planSearchQuery.isNotBlank() && groups.paidExpenses.isEmpty()) "No expenses match your search." else "Nothing left to pay."
                planResults.addView(label(message, 12f, Palette.muted).withTopMargin(9))
            } else {
                groups.unpaidExpenses.forEachIndexed { index, item ->
                    planResults.addView(plannedExpenseRow(data, item).withTopMargin(if (index == 0) 10 else 0))
                    if (index < groups.unpaidExpenses.lastIndex) planResults.addView(divider().withVerticalMargin(10))
                }
            }

            planResults.addView(divider().withVerticalMargin(14))
            planResults.addView(card(Palette.sage, radius = 16, padding = 12).apply {
                addView(planGroupHeading("PAID", groups.paidExpenses.sumOf { it.plannedCents }, Palette.moss))
                if (groups.paidExpenses.isEmpty()) {
                    addView(label("Paid expenses will collect here.", 12f, Palette.muted).withTopMargin(9))
                } else {
                    groups.paidExpenses.forEachIndexed { index, item ->
                        addView(plannedExpenseRow(data, item).withTopMargin(if (index == 0) 10 else 0))
                        if (index < groups.paidExpenses.lastIndex) addView(divider().withVerticalMargin(10))
                    }
                }
            })
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                planSearchQuery = value?.toString().orEmpty()
                renderPlanResults()
            }
            override fun afterTextChanged(value: Editable?) = Unit
        })
        order.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val next = if (position == 0) ExpenseOrder.NAME else ExpenseOrder.AMOUNT
                if (expenseOrder != next) {
                    expenseOrder = next
                    renderPlanResults()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        renderPlanResults()
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

        content.addView(sectionHeading(
            "Flexible budgets",
            if (data.budgets.isEmpty()) "Not set up" else "${data.budgets.size} ${if (data.budgets.size == 1) "category" else "categories"}",
        ).withTopMargin(28))
        if (data.budgets.isEmpty()) {
            val setup = card(Palette.canvas, radius = 22, padding = 18)
            setup.addView(label("Set limits for everyday spending", 17f, Palette.ink, bold = true))
            setup.addView(label(
                "Choose categories such as groceries, fuel, or meals out. BudgetGuard tracks posted and pending spending against each monthly limit.",
                13f,
                Palette.muted,
            ).apply { setLineSpacing(dp(2).toFloat(), 1f) }.withTopMargin(6))
            setup.addView(action("+  Add category limit", primary = true).apply {
                setOnClickListener { showBudgetEditor(data, null) }
            }.withTopMargin(14))
            content.addView(setup.withTopMargin(12))
        } else {
            val flexible = data.budgetSummary()
            val overview = card(Palette.sage, radius = 20, padding = 16)
            overview.addView(moneyLine("Available across flexible budgets", formatZar(flexible.remainingCents), Palette.moss))
            overview.addView(label(
                "${formatZar(flexible.safeToSpendTodayCents)} per day for ${flexible.daysRemaining} ${if (flexible.daysRemaining == 1) "day" else "days"} · ${formatZar(flexible.committedCents)} pending",
                12f,
                Palette.muted,
            ).withTopMargin(5))
            content.addView(overview.withTopMargin(12))
            data.budgets.sortedByDescending(Budget::percentage).forEach { budgetItem ->
                content.addView(budgetRow(data, budgetItem).withTopMargin(10))
            }
            if (availableBudgetCategories(data.categories, data.budgets).isNotEmpty()) {
                content.addView(action("+  Add another category limit", primary = false).apply {
                    setOnClickListener { showBudgetEditor(data, null) }
                }.withTopMargin(10))
            } else {
                content.addView(label("Every available category has a limit for this period.", 12f, Palette.muted).withTopMargin(10))
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

    private fun buildDebtFreedom(data: MobileDashboard): View {
        val content = pageColumn(horizontal = 20, top = 22, bottom = 32)
        content.addView(buildHeader("Debt freedom"))
        content.addView(label("Your route out of debt.", 30f, Palette.ink, bold = true).withTopMargin(22))
        content.addView(label(
            "A practical monthly plan, based on ${data.entity.name}'s balances, income, expenses, and flexible budgets.",
            14f,
            Palette.muted,
        ).apply { setLineSpacing(dp(3).toFloat(), 1f) }.withTopMargin(7))

        val activeDebts = data.debts.filter { it.isActive && it.balanceCents > 0 }
        if (activeDebts.isEmpty()) {
            val start = card(Palette.sage, radius = 24, padding = 20)
            start.addView(label(if (data.debts.isEmpty()) "Start with one honest number" else "You have no active debt", 20f, Palette.ink, bold = true))
            start.addView(label(
                if (data.debts.isEmpty()) "Add every credit card, loan, overdraft, store account, tax debt, or medical debt. Your records stay private to ${data.entity.name}."
                else "Paid-off and archived debts remain in your history. Add a debt if a balance still needs a plan.",
                13f,
                Palette.inkSoft,
            ).apply { setLineSpacing(dp(2).toFloat(), 1f) }.withTopMargin(7))
            start.addView(action("Add my first debt", primary = true).apply {
                setOnClickListener { showDebtEditor(data, null) }
            }.withTopMargin(16))
            content.addView(start.withTopMargin(22))

            val methods = card(Palette.canvas, radius = 22, padding = 18)
            methods.addView(label("Five possible routes", 17f, Palette.ink, bold = true))
            methods.addView(label(
                "Avalanche · Snowball · Hybrid · Consolidation review · Registered debt counselling or a debt management plan",
                13f,
                Palette.muted,
            ).apply { setLineSpacing(dp(2).toFloat(), 1f) }.withTopMargin(7))
            content.addView(methods.withTopMargin(12))
            val closedDebts = data.debts.filterNot(Debt::isActive)
            if (closedDebts.isNotEmpty()) {
                val history = card(Palette.canvas, radius = 20, padding = 16)
                history.addView(label("Paid-off & archived", 16f, Palette.ink, bold = true))
                closedDebts.forEach { debt ->
                    history.addView(label("${if (debt.closedReason == "paid_off") "✓" else "—"}  ${debt.name}", 12f, if (debt.closedReason == "paid_off") Palette.moss else Palette.muted).withTopMargin(8))
                }
                content.addView(history.withTopMargin(12))
            }
            return content
        }

        val plan = data.debtPlan() ?: return content
        val summary = card(Palette.ink, radius = 26, padding = 20)
        summary.addView(label("TOTAL DEBT", 10f, Palette.mint, bold = true).apply { letterSpacing = 0.09f })
        summary.addView(label(formatZar(plan.totalBalanceCents), 34f, Color.WHITE, bold = true).withTopMargin(7))
        summary.addView(label(
            "${formatZar(plan.minimumPaymentsCents)} minimums · ${formatZar(plan.availableForDebtCents)} estimated monthly capacity",
            12f,
            Palette.inkMuted,
        ).withTopMargin(8))
        content.addView(summary.withTopMargin(20))

        val currentMonth = YearMonth.now().atDay(1).toString()
        val checkedIn = data.debtCheckIns.any { it.month == currentMonth }
        val checkInDue = LocalDate.now().dayOfMonth >= data.debtPreferences.reminderDay && !checkedIn
        val checkIn = card(if (checkInDue) Palette.peach else Palette.canvas, radius = 20, padding = 16)
        checkIn.addView(label(if (checkedIn) "THIS MONTH IS UP TO DATE" else if (checkInDue) "MONTHLY CHECK-IN DUE" else "MONTHLY CHECK-IN", 10f, if (checkInDue) Palette.coral else Palette.moss, bold = true).apply { letterSpacing = 0.08f })
        checkIn.addView(label(
            if (checkedIn) "Balances were updated this month. You can correct them if a statement arrives later."
            else "Update every balance once a month so the actual line and forecast stay honest.",
            13f,
            Palette.inkSoft,
        ).withTopMargin(6))
        checkIn.addView(action(if (checkedIn) "Review this month's check-in" else "Update all balances", primary = checkInDue).apply {
            setOnClickListener { showDebtCheckIn(data) }
        }.withTopMargin(12))
        content.addView(checkIn.withTopMargin(12))

        if (plan.strategy != DebtStrategy.FORMAL_SUPPORT) {
            val firstTarget = plan.projection?.trajectory?.getOrNull(1)?.targetDebtName ?: plan.payoffOrder.firstOrNull()?.name
            val thisMonth = card(Palette.sage, radius = 22, padding = 18)
            thisMonth.addView(label("THIS MONTH", 10f, Palette.moss, bold = true).apply { letterSpacing = 0.08f })
            thisMonth.addView(label("Put ${formatZar(plan.availableForDebtCents)} toward debt", 21f, Palette.ink, bold = true).withTopMargin(7))
            thisMonth.addView(label(
                "Pay every minimum (${formatZar(plan.minimumPaymentsCents)} total), then send ${formatZar(plan.extraPaymentCents)} extra to ${firstTarget ?: "the first debt in your plan"}.",
                13f,
                Palette.inkSoft,
            ).apply { setLineSpacing(dp(3).toFloat(), 1f) }.withTopMargin(7))
            content.addView(thisMonth.withTopMargin(12))
        }

        val strategyColour = if (plan.strategy == DebtStrategy.FORMAL_SUPPORT) Palette.peach else Palette.sage
        val strategy = card(strategyColour, radius = 22, padding = 18)
        strategy.addView(label("RECOMMENDED ROUTE", 10f, Palette.moss, bold = true).apply { letterSpacing = 0.08f })
        strategy.addView(label(plan.title, 22f, Palette.ink, bold = true).withTopMargin(7))
        strategy.addView(label(plan.reason, 13f, Palette.inkSoft).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }.withTopMargin(8))
        plan.projection?.let { projection ->
            strategy.addView(divider().withVerticalMargin(14))
            strategy.addView(moneyLine("Estimated debt-free time", formatDebtDuration(projection.payoffMonths), Palette.moss))
            strategy.addView(moneyLine("Estimated interest", formatZar(projection.totalInterestCents), Palette.ink).withTopMargin(8))
            strategy.addView(moneyLine("Extra above minimums", formatZar(plan.extraPaymentCents), Palette.ink).withTopMargin(8))
        }
        strategy.addView(action("Adjust strategy preferences", primary = false).apply {
            setOnClickListener { showDebtPreferencesEditor(data) }
        }.withTopMargin(15))
        if (plan.strategy == DebtStrategy.FORMAL_SUPPORT && data.entity.kind == "personal") {
            strategy.addView(action("Find an NCR-registered debt counsellor", primary = true).apply {
                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ncr.org.za/register_of_registrants/registered_dc.php"))) }
            }.withTopMargin(10))
        }
        content.addView(strategy.withTopMargin(12))

        plan.projection?.let { projection ->
            content.addView(sectionHeading("Your payoff path", "Solid actual · dashed forecast").withTopMargin(26))
            val visual = card(Palette.canvas, radius = 22, padding = 16)
            val actual = data.debtCheckIns.sortedBy { it.month }.map { it.totalBalanceCents }
            visual.addView(DebtTrajectoryView(this).apply {
                setPadding(0, dp(4), 0, 0)
                setData(actual, projection.trajectory, projection.milestones)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)))
            visual.addView(label(
                "Current ${formatZar(plan.totalBalanceCents)} → debt-free in about ${formatDebtDuration(projection.payoffMonths)}. Estimated interest: ${formatZar(projection.totalInterestCents)}.",
                12f,
                Palette.muted,
            ).withTopMargin(8))
            content.addView(visual.withTopMargin(10))

            content.addView(sectionHeading("Milestones", "Keep checking in").withTopMargin(24))
            val timeline = card(Palette.canvas, radius = 22, padding = 16)
            projection.milestones.forEachIndexed { index, milestone ->
                timeline.addView(label(
                    "${if (milestone.percentage == 100) "✓" else "○"}  ${milestone.percentage}% repaid · month ${milestone.month} · ${formatZar(milestone.remainingBalanceCents)} left",
                    13f,
                    if (milestone.percentage == 100) Palette.moss else Palette.ink,
                    bold = milestone.percentage == 100,
                ).withTopMargin(if (index == 0) 0 else 10))
            }
            projection.payoffEvents.forEach { event ->
                timeline.addView(label("Debt cleared · ${event.debtName} in month ${event.month}", 11f, Palette.muted).withTopMargin(8))
            }
            content.addView(timeline.withTopMargin(10))
        }

        if (plan.comparisons.isNotEmpty()) {
            content.addView(sectionHeading("Compare approaches", "Same monthly capacity").withTopMargin(26))
            val fastest = plan.comparisons.mapNotNull { it.projection?.payoffMonths }.maxOrNull()?.coerceAtLeast(1) ?: 1
            plan.comparisons.forEach { comparison ->
                val projection = comparison.projection ?: return@forEach
                val row = card(Palette.canvas, radius = 19, padding = 15)
                row.addView(moneyLine(strategyLabel(comparison.strategy), formatDebtDuration(projection.payoffMonths), Palette.ink))
                val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = fastest
                    progress = (fastest - projection.payoffMonths + 1).coerceAtLeast(1)
                    progressTintList = ColorStateList.valueOf(if (comparison.strategy == plan.strategy) Palette.moss else Palette.muted)
                    progressBackgroundTintList = ColorStateList.valueOf(Palette.line)
                }
                row.addView(bar.withHeight(6).withTopMargin(9))
                row.addView(label("${formatZar(projection.totalInterestCents)} estimated interest", 11f, Palette.muted).withTopMargin(6))
                if (comparison.strategy in listOf(DebtStrategy.AVALANCHE, DebtStrategy.SNOWBALL, DebtStrategy.HYBRID) && comparison.strategy != plan.strategy) {
                    row.addView(action("Use this approach", primary = false, compact = true).apply {
                        setOnClickListener { useDebtStrategy(data, comparison.strategy) }
                    }.withTopMargin(9))
                } else if (comparison.strategy == plan.strategy) {
                    row.addView(label("CURRENT APPROACH", 9f, Palette.moss, bold = true).withTopMargin(9))
                }
                content.addView(row.withTopMargin(9))
            }
        }

        val spending = data.debtSpendingAnalysis()
        content.addView(sectionHeading("Find room in spending", "Your approval required").withTopMargin(26))
        val spendingCard = card(Palette.canvas, radius = 22, padding = 17)
        if (spending.isReady && spending.suggestions.isNotEmpty()) {
            spendingCard.addView(label("Based on ${spending.monthCount} completed months", 16f, Palette.ink, bold = true))
            spendingCard.addView(label("Choose flexible categories and preview a 5–30% reduction. Nothing changes until you edit the budget.", 12f, Palette.muted).withTopMargin(6))
            spending.suggestions.take(3).forEach { suggestion ->
                spendingCard.addView(moneyLine(suggestion.categoryName, "median ${formatZar(suggestion.medianMonthlySpendCents)}", Palette.ink).withTopMargin(9))
            }
            spendingCard.addView(action("Build a spending scenario", primary = true).apply {
                setOnClickListener { showDebtSpendingScenario(data) }
            }.withTopMargin(14))
        } else {
            spendingCard.addView(label("More clean history is needed", 16f, Palette.ink, bold = true))
            spendingCard.addView(label(
                "Suggestions appear after at least 2 completed months and 75% categorised coverage. Current coverage: ${spending.coveragePercentage}% across ${spending.monthCount} months.",
                12f,
                Palette.muted,
            ).withTopMargin(6))
            spendingCard.addView(action("Quick Sort transactions", primary = false).apply {
                setOnClickListener { selectedScreen = Screen.TRANSACTIONS; renderDashboard() }
            }.withTopMargin(12))
        }
        content.addView(spendingCard.withTopMargin(10))

        if (plan.warnings.isNotEmpty()) {
            val warning = card(Palette.peach, radius = 20, padding = 16)
            warning.addView(label("CHECK BEFORE YOU ACT", 10f, Palette.coral, bold = true).apply { letterSpacing = 0.08f })
            plan.warnings.forEach { message ->
                warning.addView(label("•  $message", 12f, Palette.inkSoft).apply {
                    setLineSpacing(dp(2).toFloat(), 1f)
                }.withTopMargin(8))
            }
            content.addView(warning.withTopMargin(12))
        }

        content.addView(sectionHeading("Payoff order", "${plan.payoffOrder.size} debts").withTopMargin(26))
        plan.payoffOrder.forEachIndexed { index, debt ->
            content.addView(debtCard(data, debt, index + 1).withTopMargin(10))
        }
        content.addView(action("+  Add another debt", primary = true).apply {
            setOnClickListener { showDebtEditor(data, null) }
        }.withTopMargin(12))

        val closedDebts = data.debts.filterNot(Debt::isActive)
        if (closedDebts.isNotEmpty()) {
            val history = card(Palette.canvas, radius = 20, padding = 16)
            history.addView(label("Paid-off & archived", 16f, Palette.ink, bold = true))
            closedDebts.forEach { debt ->
                history.addView(label(
                    "${if (debt.closedReason == "paid_off") "✓" else "—"}  ${debt.name} · ${debt.closedReason?.replace('_', ' ') ?: "closed"}",
                    12f,
                    if (debt.closedReason == "paid_off") Palette.moss else Palette.muted,
                ).withTopMargin(8))
            }
            content.addView(history.withTopMargin(14))
        }

        val basis = card(Palette.canvas, radius = 20, padding = 16)
        basis.addView(label("How the estimate is built", 16f, Palette.ink, bold = true))
        basis.addView(label(
            "Monthly income ${formatZar(plan.monthlyIncomeCents)} − living plan ${formatZar(plan.monthlyLivingPlanCents)} = ${formatZar(plan.availableForDebtCents)} for debt. Carry-forward cash is not repeated as monthly income.",
            12f,
            Palette.muted,
        ).apply { setLineSpacing(dp(2).toFloat(), 1f) }.withTopMargin(7))
        basis.addView(label(
            "This is an educational estimate, not regulated financial advice. Interest timing, fees, changing rates, and lender rules can change the result. Keep making required payments unless a credit provider or registered professional formally changes them.",
            11f,
            Palette.muted,
        ).apply { setLineSpacing(dp(2).toFloat(), 1f) }.withTopMargin(10))
        content.addView(basis.withTopMargin(18))
        return content
    }

    private fun debtCard(data: MobileDashboard, debt: Debt, order: Int): View {
        val root = card(Palette.canvas, radius = 20, padding = 16)
        val top = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(label(order.toString(), 15f, Palette.ink, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(Palette.sage, 15)
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        top.addView(vertical().apply {
            setPadding(dp(11), 0, 0, 0)
            addView(label(debt.name, 16f, Palette.ink, bold = true))
            addView(label(
                "${debtTypeLabel(debt.type)} · ${formatInterestRate(debt.annualInterestBps)} APR",
                11f,
                Palette.muted,
            ).withTopMargin(3))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(label(formatZar(debt.balanceCents), 15f, Palette.moss, bold = true))
        root.addView(top)
        root.addView(label(
            "Minimum ${formatZar(debt.minimumPaymentCents)}${debt.dueDay?.let { " · due day $it" }.orEmpty()}" +
                if (debt.inArrears) " · In arrears" else "",
            12f,
            if (debt.inArrears) Palette.coral else Palette.muted,
            bold = debt.inArrears,
        ).withTopMargin(10))
        root.isClickable = true
        root.isFocusable = true
        root.contentDescription = "Edit ${debt.name} debt"
        root.setOnClickListener { showDebtEditor(data, debt) }
        return root
    }

    private fun showDebtEditor(data: MobileDashboard, debt: Debt?) {
        val typeOptions = listOf(
            "credit_card" to "Credit card",
            "personal_loan" to "Personal loan",
            "overdraft" to "Overdraft",
            "vehicle_finance" to "Vehicle finance",
            "home_loan" to "Home loan",
            "store_account" to "Store account",
            "tax" to "Tax debt",
            "medical" to "Medical debt",
            "other" to "Other debt",
        )
        val form = vertical().apply { setPadding(dp(22), dp(4), dp(22), dp(16)) }
        form.addView(label("Use the latest statement. Annual interest may be labelled APR or interest rate.", 13f, Palette.muted))
        val name = input("Debt or credit provider name").apply { debt?.let { setText(it.name) } }
        form.addView(name.withTopMargin(14))
        form.addView(label("DEBT TYPE", 9f, Palette.muted, bold = true).apply { letterSpacing = 0.08f }.withTopMargin(12))
        val type = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, typeOptions.map { it.second })
            setSelection(debt?.type?.let { current -> typeOptions.indexOfFirst { it.first == current }.coerceAtLeast(0) } ?: 0)
            background = rounded(Palette.paper, 16, Palette.line)
            minimumHeight = dp(52)
            setPadding(dp(10), 0, dp(10), 0)
            contentDescription = "Debt type"
        }
        form.addView(type.withTopMargin(5))
        val balance = input("Current balance in rand", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            debt?.let { setText(BigDecimal.valueOf(it.balanceCents, 2).toPlainString()) }
        }
        val interest = input("Annual interest rate (%)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            debt?.let { setText(BigDecimal.valueOf(it.annualInterestBps.toLong(), 2).stripTrailingZeros().toPlainString()) }
        }
        val minimum = input("Minimum monthly payment in rand", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            debt?.let { setText(BigDecimal.valueOf(it.minimumPaymentCents, 2).toPlainString()) }
        }
        val term = input("Remaining term in months (optional)", InputType.TYPE_CLASS_NUMBER).apply {
            debt?.remainingTermMonths?.let { setText(it.toString()) }
        }
        val dueDay = input("Payment due day 1–31 (optional)", InputType.TYPE_CLASS_NUMBER).apply {
            debt?.dueDay?.let { setText(it.toString()) }
        }
        form.addView(balance.withTopMargin(10))
        form.addView(interest.withTopMargin(10))
        form.addView(minimum.withTopMargin(10))
        form.addView(term.withTopMargin(10))
        form.addView(dueDay.withTopMargin(10))
        val secured = CheckBox(this).apply {
            text = "This debt is secured by an asset"
            isChecked = debt?.secured == true
            setTextColor(Palette.ink)
            minimumHeight = dp(48)
        }
        val arrears = CheckBox(this).apply {
            text = "This debt is in arrears or collections"
            isChecked = debt?.inArrears == true
            setTextColor(Palette.ink)
            minimumHeight = dp(48)
        }
        form.addView(secured.withTopMargin(6))
        form.addView(arrears)
        val scroller = ScrollView(this).apply { addView(form) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (debt == null) "Add debt" else "Edit debt")
            .setView(scroller)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply { if (debt != null) setNeutralButton("Remove", null) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val balanceCents = parseMoneyCents(balance.text.toString())
                val minimumCents = parseMoneyCents(minimum.text.toString())
                val interestBps = parsePercentageBps(interest.text.toString())
                val termMonths = term.text.toString().trim().takeIf(String::isNotEmpty)?.toIntOrNull()
                val paymentDay = dueDay.text.toString().trim().takeIf(String::isNotEmpty)?.toIntOrNull()
                when {
                    name.text.isNullOrBlank() -> Toast.makeText(this, "Enter a debt name.", Toast.LENGTH_SHORT).show()
                    balanceCents == null -> Toast.makeText(this, "Enter a positive current balance.", Toast.LENGTH_SHORT).show()
                    interestBps == null -> Toast.makeText(this, "Enter an annual interest rate of zero or more.", Toast.LENGTH_SHORT).show()
                    minimumCents == null -> Toast.makeText(this, "Enter a positive minimum payment.", Toast.LENGTH_SHORT).show()
                    term.text.isNotBlank() && (termMonths == null || termMonths !in 1..1200) -> Toast.makeText(this, "Enter a valid remaining term.", Toast.LENGTH_SHORT).show()
                    dueDay.text.isNotBlank() && (paymentDay == null || paymentDay !in 1..31) -> Toast.makeText(this, "The due day must be between 1 and 31.", Toast.LENGTH_SHORT).show()
                    else -> {
                        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        save.isEnabled = false
                        lifecycleScope.launch {
                            runCatching {
                                applicationState.collectorClient?.saveDebt(
                                    entityId = data.entity.id,
                                    debtId = debt?.id,
                                    name = name.text.toString(),
                                    type = typeOptions[type.selectedItemPosition].first,
                                    balanceCents = balanceCents!!,
                                    annualInterestBps = interestBps!!,
                                    minimumPaymentCents = minimumCents!!,
                                    remainingTermMonths = termMonths,
                                    dueDay = paymentDay,
                                    secured = secured.isChecked,
                                    inArrears = arrears.isChecked,
                                ) ?: error("BudgetGuard is not configured.")
                            }.onSuccess {
                                dialog.dismiss()
                                loadDashboard(keepContentVisible = true)
                            }.onFailure {
                                save.isEnabled = true
                                Toast.makeText(this@MainActivity, "Couldn't save this debt.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            if (debt != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Archive ${debt.name}?")
                    .setMessage("This removes the debt from the active plan but keeps it in your history. It does not affect any bank or credit-provider account.")
                    .setPositiveButton("Archive") { _, _ ->
                        lifecycleScope.launch {
                            runCatching { applicationState.collectorClient?.deleteDebt(data.entity.id, debt.id) }
                                .onSuccess {
                                    dialog.dismiss()
                                    loadDashboard(keepContentVisible = true)
                                }
                                .onFailure { Toast.makeText(this@MainActivity, "Couldn't remove this debt.", Toast.LENGTH_LONG).show() }
                        }
                    }
                    .setNegativeButton("Keep", null)
                    .show()
            }
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.9).toInt())
    }

    private fun showDebtPreferencesEditor(data: MobileDashboard) {
        val goalKeys = listOf("lowest_cost", "quick_wins", "balanced")
        val goalLabels = listOf("Lowest total interest", "Quick visible wins", "Balanced")
        val form = vertical().apply { setPadding(dp(22), dp(4), dp(22), dp(14)) }
        form.addView(label("WHAT MATTERS MOST", 9f, Palette.muted, bold = true).apply { letterSpacing = 0.08f })
        val goal = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, goalLabels)
            setSelection(goalKeys.indexOf(data.debtPreferences.goal).coerceAtLeast(0))
            background = rounded(Palette.paper, 16, Palette.line)
            minimumHeight = dp(52)
            setPadding(dp(10), 0, dp(10), 0)
            contentDescription = "Debt strategy preference"
        }
        form.addView(goal.withTopMargin(5))
        form.addView(label("OPTIONAL CONSOLIDATION OFFER", 9f, Palette.muted, bold = true).apply { letterSpacing = 0.08f }.withTopMargin(18))
        form.addView(label("Enter a real written offer only. BudgetGuard will recommend reviewing it only if it is affordable and estimated to cost at least 5% less.", 12f, Palette.muted).withTopMargin(5))
        val apr = input("Offer annual interest rate (%)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            data.debtPreferences.consolidationAprBps?.let { setText(BigDecimal.valueOf(it.toLong(), 2).stripTrailingZeros().toPlainString()) }
        }
        val term = input("Offer term in months", InputType.TYPE_CLASS_NUMBER).apply {
            data.debtPreferences.consolidationTermMonths?.let { setText(it.toString()) }
        }
        val fees = input("Total fees in rand", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            if (data.debtPreferences.consolidationFeesCents > 0) setText(BigDecimal.valueOf(data.debtPreferences.consolidationFeesCents, 2).toPlainString())
        }
        form.addView(apr.withTopMargin(9))
        form.addView(term.withTopMargin(9))
        form.addView(fees.withTopMargin(9))
        form.addView(label("MONTHLY CHECK-IN REMINDER", 9f, Palette.muted, bold = true).apply { letterSpacing = 0.08f }.withTopMargin(18))
        val reminders = CheckBox(this).apply {
            text = "Remind me each month"
            isChecked = data.debtPreferences.reminderEnabled
            setTextColor(Palette.ink)
            minimumHeight = dp(48)
        }
        val reminderDay = input("Reminder day 1–28", InputType.TYPE_CLASS_NUMBER).apply {
            setText(data.debtPreferences.reminderDay.toString())
        }
        form.addView(reminders.withTopMargin(4))
        form.addView(reminderDay.withTopMargin(4))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Strategy preferences")
            .setView(form)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hasOffer = apr.text.isNotBlank() || term.text.isNotBlank() || fees.text.isNotBlank()
                val offerApr = if (hasOffer) parsePercentageBps(apr.text.toString()) else null
                val offerTerm = if (hasOffer) term.text.toString().trim().toIntOrNull() else null
                val offerFees = if (fees.text.isBlank()) 0L else parseMoneyCentsAllowZero(fees.text.toString())
                val selectedReminderDay = reminderDay.text.toString().toIntOrNull()
                if (hasOffer && (offerApr == null || offerTerm == null || offerTerm !in 1..1200 || offerFees == null)) {
                    Toast.makeText(this, "Complete the offer rate and term, and enter valid fees.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (selectedReminderDay == null || selectedReminderDay !in 1..28) {
                    Toast.makeText(this, "The reminder day must be between 1 and 28.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                save.isEnabled = false
                lifecycleScope.launch {
                    runCatching {
                        applicationState.collectorClient?.saveDebtPreferences(
                            entityId = data.entity.id,
                            goal = goalKeys[goal.selectedItemPosition],
                            consolidationAprBps = offerApr,
                            consolidationTermMonths = offerTerm,
                            consolidationFeesCents = offerFees ?: 0,
                            reminderEnabled = reminders.isChecked,
                            reminderDay = selectedReminderDay,
                            preferredStrategy = "recommended",
                        ) ?: error("BudgetGuard is not configured.")
                    }.onSuccess {
                        dialog.dismiss()
                        if (reminders.isChecked && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        loadDashboard(keepContentVisible = true)
                    }.onFailure {
                        save.isEnabled = true
                        Toast.makeText(this@MainActivity, "Couldn't save strategy preferences.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showDebtCheckIn(data: MobileDashboard) {
        val month = YearMonth.now().atDay(1).toString()
        val existing = data.debtCheckIns.firstOrNull { it.month == month }
        val existingIds = existing?.balances?.mapTo(mutableSetOf(), DebtCheckInBalance::debtId).orEmpty()
        val debts = data.debts.filter { it.isActive || it.id in existingIds }
        if (debts.isEmpty()) return
        data class Fields(val debt: Debt, val balance: EditText, val apr: EditText, val minimum: EditText, val arrears: CheckBox)
        val form = vertical().apply { setPadding(dp(22), dp(4), dp(22), dp(16)) }
        form.addView(label("Enter every latest statement balance. A zero balance marks that debt paid off; you can correct this month's check-in later.", 13f, Palette.muted))
        val fields = debts.map { debt ->
            val saved = existing?.balances?.firstOrNull { it.debtId == debt.id }
            form.addView(label(debt.name, 17f, Palette.ink, bold = true).withTopMargin(18))
            val balance = input("Current balance in rand", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
                setText(BigDecimal.valueOf(saved?.balanceCents ?: debt.balanceCents, 2).toPlainString())
            }
            val apr = input("Annual interest rate (%)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
                setText(BigDecimal.valueOf((saved?.annualInterestBps ?: debt.annualInterestBps).toLong(), 2).stripTrailingZeros().toPlainString())
            }
            val minimum = input("Minimum monthly payment in rand", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
                setText(BigDecimal.valueOf(saved?.minimumPaymentCents ?: debt.minimumPaymentCents, 2).toPlainString())
            }
            val arrears = CheckBox(this).apply {
                text = "In arrears or collections"
                isChecked = saved?.inArrears ?: debt.inArrears
                setTextColor(Palette.ink)
                minimumHeight = dp(44)
            }
            form.addView(balance.withTopMargin(8))
            form.addView(apr.withTopMargin(7))
            form.addView(minimum.withTopMargin(7))
            form.addView(arrears)
            Fields(debt, balance, apr, minimum, arrears)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Monthly debt check-in" else "Correct this month's check-in")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save all balances", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val balances = fields.mapNotNull { field ->
                    val balance = parseMoneyCentsAllowZero(field.balance.text.toString()) ?: return@mapNotNull null
                    val apr = parsePercentageBps(field.apr.text.toString()) ?: return@mapNotNull null
                    val minimum = parseMoneyCents(field.minimum.text.toString()) ?: return@mapNotNull null
                    DebtCheckInBalance(field.debt.id, balance, apr, minimum, field.arrears.isChecked)
                }
                if (balances.size != fields.size) {
                    Toast.makeText(this, "Check every balance, interest rate, and minimum payment.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                button.isEnabled = false
                lifecycleScope.launch {
                    runCatching {
                        applicationState.collectorClient?.recordDebtCheckIn(data.entity.id, month, balances)
                            ?: error("BudgetGuard is not configured.")
                    }.onSuccess {
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, "Check-in saved. Your payoff path has been recalculated.", Toast.LENGTH_LONG).show()
                        loadDashboard(keepContentVisible = true)
                    }.onFailure {
                        button.isEnabled = true
                        Toast.makeText(this@MainActivity, "Couldn't save the complete check-in.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.92).toInt())
    }

    private fun showDebtSpendingScenario(data: MobileDashboard) {
        val analysis = data.debtSpendingAnalysis()
        if (!analysis.isReady || analysis.suggestions.isEmpty()) return
        val form = vertical().apply { setPadding(dp(22), dp(4), dp(22), dp(14)) }
        form.addView(label("Select categories you are willing to reduce. This is a preview—not an automatic cut.", 13f, Palette.muted))
        val choices = analysis.suggestions.map { suggestion ->
            CheckBox(this).apply {
                text = "${suggestion.categoryName} · median ${formatZar(suggestion.medianMonthlySpendCents)}"
                isChecked = true
                tag = suggestion.categoryId
                setTextColor(Palette.ink)
                minimumHeight = dp(48)
                form.addView(this)
            } to suggestion
        }
        val reductionLabel = label("10% reduction", 15f, Palette.ink, bold = true)
        form.addView(reductionLabel.withTopMargin(12))
        val reduction = SeekBar(this).apply { max = 25; progress = 5 }
        form.addView(reduction)
        val preview = card(Palette.sage, radius = 18, padding = 14)
        form.addView(preview.withTopMargin(10))
        fun percentage() = reduction.progress + 5
        fun extraCapacity(): Long = choices.filter { it.first.isChecked }.sumOf { (_, suggestion) ->
            suggestion.medianMonthlySpendCents * percentage() / 100
        }
        fun refreshPreview() {
            reductionLabel.text = "${percentage()}% reduction"
            preview.removeAllViews()
            val extra = extraCapacity()
            val base = data.debtPlan()
            val scenario = data.debtPlan(additionalMonthlyPaymentCents = extra)
            preview.addView(label("Could free ${formatZar(extra)} per month", 17f, Palette.ink, bold = true))
            if (base?.projection != null && scenario?.projection != null) {
                val monthsSooner = (base.projection.payoffMonths - scenario.projection.payoffMonths).coerceAtLeast(0)
                val interestSaved = (base.projection.totalInterestCents - scenario.projection.totalInterestCents).coerceAtLeast(0)
                preview.addView(label("About $monthsSooner months sooner · ${formatZar(interestSaved)} less interest", 12f, Palette.inkSoft).withTopMargin(6))
            }
        }
        choices.forEach { (box, _) -> box.setOnCheckedChangeListener { _, _ -> refreshPreview() } }
        reduction.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = refreshPreview()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        refreshPreview()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Spending scenario")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Edit first selected budget", null)
            .setNegativeButton("Keep current budgets", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = choices.firstOrNull { it.first.isChecked }?.second
                if (selected == null) {
                    Toast.makeText(this, "Select at least one category.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val budget = data.budgets.firstOrNull { it.categoryId == selected.categoryId } ?: return@setOnClickListener
                val suggestedLimit = (budget.limitCents - selected.medianMonthlySpendCents * percentage() / 100).coerceAtLeast(1)
                dialog.dismiss()
                showBudgetEditor(data, budget, selected.categoryId, suggestedLimit)
            }
        }
        dialog.show()
    }

    private fun useDebtStrategy(data: MobileDashboard, strategy: DebtStrategy) {
        val goal = when (strategy) {
            DebtStrategy.AVALANCHE -> "lowest_cost"
            DebtStrategy.SNOWBALL -> "quick_wins"
            DebtStrategy.HYBRID -> "balanced"
            else -> return
        }
        lifecycleScope.launch {
            runCatching {
                applicationState.collectorClient?.saveDebtPreferences(
                    data.entity.id,
                    goal,
                    data.debtPreferences.consolidationAprBps,
                    data.debtPreferences.consolidationTermMonths,
                    data.debtPreferences.consolidationFeesCents,
                    data.debtPreferences.reminderEnabled,
                    data.debtPreferences.reminderDay,
                    strategy.name.lowercase(Locale.ROOT),
                ) ?: error("BudgetGuard is not configured.")
            }.onSuccess { loadDashboard(keepContentVisible = true) }
                .onFailure { Toast.makeText(this@MainActivity, "Couldn't change the approach.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun strategyLabel(strategy: DebtStrategy): String = when (strategy) {
        DebtStrategy.AVALANCHE -> "Avalanche · lowest interest"
        DebtStrategy.SNOWBALL -> "Snowball · quickest wins"
        DebtStrategy.HYBRID -> "Hybrid · quick win then interest"
        DebtStrategy.CONSOLIDATION_REVIEW -> "Consolidation offer"
        DebtStrategy.FORMAL_SUPPORT -> "Professional support"
    }

    private fun parsePercentageBps(value: String): Int? = runCatching {
        BigDecimal(value.replace(",", "").trim())
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .intValueExact()
    }.getOrNull()?.takeIf { it in 0..100000 }

    private fun parseMoneyCentsAllowZero(value: String): Long? = runCatching {
        BigDecimal(value.replace(",", "").trim())
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()?.takeIf { it >= 0 }

    private fun formatInterestRate(bps: Int): String = BigDecimal.valueOf(bps.toLong(), 2)
        .stripTrailingZeros()
        .toPlainString() + "%"

    private fun formatDebtDuration(months: Int): String {
        val years = months / 12
        val remaining = months % 12
        return when {
            years == 0 -> "$months ${if (months == 1) "month" else "months"}"
            remaining == 0 -> "$years ${if (years == 1) "year" else "years"}"
            else -> "$years ${if (years == 1) "year" else "years"} $remaining ${if (remaining == 1) "month" else "months"}"
        }
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
        val activeDebts = data.debts.filter(Debt::isActive)
        shell.addView(drawerNavItem("↓", "Debt freedom", if (activeDebts.isEmpty()) "Build a repayment route" else "${activeDebts.size} debts · ${formatZar(activeDebts.sumOf { it.balanceCents })}", Screen.DEBT, dialog).withTopMargin(6))
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
            if (screen == Screen.DEBT && selectedPeriodStart != YearMonth.now().atDay(1).toString()) {
                selectedPeriodStart = YearMonth.now().atDay(1).toString()
                loadDashboard(keepContentVisible = true)
            } else {
                renderDashboard()
            }
        }
    }

    private fun performSignOut() {
        dashboard?.let { DebtReminderScheduler.sync(this, it.entity.id, false, it.debtPreferences.reminderDay, null) }
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
        val moveButton = item?.let {
            action("Move to another month or entity", primary = false).also { button ->
                container.addView(button.withTopMargin(10))
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (item == null) "Add income" else "Edit income")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply { if (item != null) setNeutralButton("Delete income", null) }
            .create()
        dialog.setOnShowListener {
            if (item != null) {
                moveButton?.setOnClickListener { showPlannedItemMoveDialog(data, item, dialog) }
            }
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
            val paymentControl = when {
                item.manuallyPaid -> action("Undo paid", primary = false, compact = true).apply {
                    contentDescription = "Mark ${item.name} as unpaid"
                    setOnClickListener { setPlannedExpensePaid(item, false, this) }
                }
                item.isPaid -> label("✓  Paid", 11f, Palette.moss, bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(9), dp(6), dp(9), dp(6))
                    background = rounded(Palette.sage, 13)
                }
                else -> action("Mark paid", primary = false, compact = true).apply {
                    contentDescription = "Mark ${item.name} as paid"
                    setOnClickListener { setPlannedExpensePaid(item, true, this) }
                }
            }
            addView(paymentControl.withTopMargin(5))
            addView(label("Edit details", 10f, Palette.moss, bold = true).apply { gravity = Gravity.END }.withTopMargin(4))
        }
        addView(amount)
        setPadding(0, dp(4), 0, dp(4))
        isClickable = true
        isFocusable = true
        contentDescription = "Edit ${item.name} expense"
        setOnClickListener { showExpenseEditor(data, item) }
    }

    private fun setPlannedExpensePaid(item: PlannedItem, paid: Boolean, control: View) {
        control.isEnabled = false
        lifecycleScope.launch {
            runCatching { applicationState.collectorClient?.setPlannedExpensePaid(item.id, paid) }
                .onSuccess { loadDashboard(keepContentVisible = true) }
                .onFailure {
                    control.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        if (paid) "Couldn't mark this expense as paid." else "Couldn't undo the paid status.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
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
        val moveButton = item?.let {
            action("Move to another month or entity", primary = false).also { button ->
                container.addView(button.withTopMargin(10))
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (item == null) "Add expense" else "Edit expense")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply { if (item != null) setNeutralButton("Delete expense", null) }
            .create()
        dialog.setOnShowListener {
            if (item != null) {
                moveButton?.setOnClickListener { showPlannedItemMoveDialog(data, item, dialog) }
            }
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

    private fun showPlannedItemMoveDialog(
        data: MobileDashboard,
        item: PlannedItem,
        editorDialog: AlertDialog,
    ) {
        lifecycleScope.launch {
            val targets = runCatching {
                applicationState.collectorClient?.loadPlannedItemMoveTargets().orEmpty()
            }.getOrElse {
                Toast.makeText(this@MainActivity, "Couldn't load the available months.", Toast.LENGTH_LONG).show()
                return@launch
            }.filterNot { target ->
                target.entityId == data.entity.id && target.periodId == data.period.id
            }

            if (targets.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    "Create another budget period first, then move this entry.",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }

            val targetSpinner = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    targets.map { "${it.entityName} · ${formatPeriodRange(it.startsOn)}" },
                )
                background = rounded(Palette.paper, 16, Palette.line)
                minimumHeight = dp(52)
                setPadding(dp(10), 0, dp(10), 0)
            }
            val content = vertical().apply {
                setPadding(dp(22), dp(4), dp(22), dp(12))
                addView(label(
                    "Choose the destination. Moving between entities removes the old account, and an incompatible personal or business category is cleared.",
                    13f,
                    Palette.muted,
                ).apply { setLineSpacing(dp(2).toFloat(), 1f) })
                addView(targetSpinner.withTopMargin(14))
            }
            val moveDialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Move ${item.name}")
                .setView(content)
                .setPositiveButton("Move", null)
                .setNegativeButton("Cancel", null)
                .create()
            moveDialog.setOnShowListener {
                moveDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val target = targets[targetSpinner.selectedItemPosition]
                    val button = moveDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    button.isEnabled = false
                    lifecycleScope.launch {
                        runCatching {
                            applicationState.collectorClient?.movePlannedItem(
                                itemId = item.id,
                                targetEntityId = target.entityId,
                                targetPeriodId = target.periodId,
                            )
                        }.onSuccess {
                            moveDialog.dismiss()
                            editorDialog.dismiss()
                            selectedEntityId = target.entityId
                            selectedPeriodStart = target.startsOn
                            loadDashboard(keepContentVisible = true)
                            Toast.makeText(this@MainActivity, "Entry moved.", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            button.isEnabled = true
                            Toast.makeText(
                                this@MainActivity,
                                "Couldn't move this entry. Entries with matched payments must remain in their original period.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
            moveDialog.show()
        }
    }

    private fun parseMoneyCents(value: String): Long? = runCatching {
        BigDecimal(value.replace(",", "").trim())
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()?.takeIf { it > 0 }

    private fun showBudgetEditor(
        data: MobileDashboard,
        budget: Budget?,
        preselectedCategoryId: String? = null,
        prefillLimitCents: Long? = null,
    ) {
        val categories = availableBudgetCategories(data.categories, data.budgets, budget?.id)
        if (categories.isEmpty()) {
            if (budget == null) {
                Toast.makeText(this, "Every available category already has a limit.", Toast.LENGTH_LONG).show()
            } else {
                showBudgetDeleteConfirmation(data, budget, null)
            }
            return
        }

        val container = vertical().apply { setPadding(dp(22), dp(4), dp(22), dp(12)) }
        container.addView(label(
            "This limit applies to ${data.entity.name} for ${data.month}. Spending stays in Activity if you later remove the limit.",
            13f,
            Palette.muted,
        ).apply { setLineSpacing(dp(2).toFloat(), 1f) })
        container.addView(label("CATEGORY", 9f, Palette.muted, bold = true).apply { letterSpacing = 0.08f }.withTopMargin(16))
        val categorySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, categories.map { it.name })
            val selectedId = preselectedCategoryId ?: budget?.categoryId
            setSelection(selectedId?.let { id -> categories.indexOfFirst { it.id == id }.coerceAtLeast(0) } ?: 0)
            background = rounded(Palette.paper, 16, Palette.line)
            minimumHeight = dp(52)
            setPadding(dp(10), 0, dp(10), 0)
        }
        container.addView(categorySpinner.withTopMargin(5))
        val amount = input("Monthly limit in rand", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            val cents = prefillLimitCents ?: budget?.limitCents
            cents?.let { setText(BigDecimal.valueOf(it, 2).toPlainString()) }
        }
        container.addView(amount.withTopMargin(10))
        container.addView(label(
            "New periods copy the category limits from the previous month when the period is created.",
            12f,
            Palette.moss,
            bold = true,
        ).withTopMargin(10))

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (budget == null) "Add flexible budget" else "Edit flexible budget")
            .setView(container)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply { if (budget != null) setNeutralButton("Remove limit", null) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cents = parseMoneyCents(amount.text.toString())
                if (cents == null) {
                    Toast.makeText(this, "Enter a positive monthly limit.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val selectedCategory = categories[categorySpinner.selectedItemPosition]
                val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                saveButton.isEnabled = false
                lifecycleScope.launch {
                    runCatching {
                        applicationState.collectorClient?.saveBudgetLimit(
                            entityId = data.entity.id,
                            periodStart = data.period.startsOn,
                            budgetId = budget?.id,
                            categoryId = selectedCategory.id,
                            limitCents = cents,
                        ) ?: error("BudgetGuard is not configured.")
                    }.onSuccess {
                        dialog.dismiss()
                        loadDashboard(keepContentVisible = true)
                    }.onFailure {
                        saveButton.isEnabled = true
                        Toast.makeText(this@MainActivity, "Couldn't save this category limit.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            if (budget != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    showBudgetDeleteConfirmation(data, budget, dialog)
                }
            }
        }
        dialog.show()
    }

    private fun showBudgetDeleteConfirmation(
        data: MobileDashboard,
        budget: Budget,
        editorDialog: AlertDialog?,
    ) {
        val categoryName = data.categories.firstOrNull { it.id == budget.categoryId }?.name ?: "this category"
        AlertDialog.Builder(this)
            .setTitle("Remove $categoryName limit?")
            .setMessage("Only the monthly limit is removed. Existing transactions and their categories stay unchanged.")
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    runCatching {
                        applicationState.collectorClient?.deleteBudgetLimit(
                            entityId = data.entity.id,
                            periodStart = data.period.startsOn,
                            budgetId = budget.id,
                        ) ?: error("BudgetGuard is not configured.")
                    }.onSuccess {
                        editorDialog?.dismiss()
                        loadDashboard(keepContentVisible = true)
                    }.onFailure {
                        Toast.makeText(this@MainActivity, "Couldn't remove this category limit.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Keep", null)
            .show()
    }

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
        root.addView(label(
            if (budget.remainingCents >= 0) "${formatZar(budget.remainingCents)} left  ·  Tap to edit"
            else "${formatZar(-budget.remainingCents)} over limit  ·  Tap to edit",
            11f,
            if (budget.remainingCents >= 0) Palette.moss else Palette.coral,
            bold = true,
        ).withTopMargin(4))
        root.isClickable = true
        root.isFocusable = true
        root.contentDescription = "Edit ${category?.name ?: "flexible"} budget"
        root.setOnClickListener { showBudgetEditor(data, budget) }
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
