package app.budgetguard.android.sync

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import app.budgetguard.android.BuildConfig
import app.budgetguard.android.data.LocalTransactionEntity
import app.budgetguard.android.dashboard.Account
import app.budgetguard.android.dashboard.Budget
import app.budgetguard.android.dashboard.BudgetPeriod
import app.budgetguard.android.dashboard.Category
import app.budgetguard.android.dashboard.Entity
import app.budgetguard.android.dashboard.Invoice
import app.budgetguard.android.dashboard.InvoiceInbox
import app.budgetguard.android.dashboard.MobileDashboard
import app.budgetguard.android.dashboard.PlannedItem
import app.budgetguard.android.dashboard.Transaction
import app.budgetguard.android.dashboard.formatPeriodRange
import app.budgetguard.android.sms.AccountMessageCandidate
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class UploadReport(val detectedAccounts: Set<String>)

data class AccountAssignment(
    val externalKey: String,
    val accountName: String,
    val entityId: String,
    val isActive: Boolean,
)

data class AccountDiscoveryImportReport(
    val created: Int,
    val moved: Int,
    val reactivated: Int,
)

class NotAuthenticatedException : Exception()

class SupabaseCollectorClient private constructor(
    context: Context,
    private val client: SupabaseClient,
) {
    private val preferences = context.getSharedPreferences("collector_identity", Context.MODE_PRIVATE)

    suspend fun signIn(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signUp(email: String, password: String) {
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signOut() = client.auth.signOut()

    suspend fun currentUserEmail(): String? {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull()?.email
    }

    fun isOnboardingComplete(): Boolean {
        val userId = client.auth.currentUserOrNull()?.id ?: return false
        return preferences.getBoolean("onboarding_complete_$userId", false)
    }

    fun finishOnboarding() {
        val userId = client.auth.currentUserOrNull()?.id ?: return
        preferences.edit { putBoolean("onboarding_complete_$userId", true) }
    }

    suspend fun loadDashboard(requestedEntityId: String? = null, requestedPeriodStart: String? = null): MobileDashboard {
        val userId = authenticatedUserId()
        val entityRows = client.from("entities")
            .select {
                filter { eq("is_active", true) }
                order("display_order", Order.ASCENDING)
            }
            .decodeList<EntityRow>()
        val preferredEntityId = requestedEntityId
            ?: preferences.getString("selected_entity_$userId", null)
        val entityRow = entityRows.firstOrNull { it.id == preferredEntityId }
            ?: entityRows.firstOrNull { it.isDefault }
            ?: entityRows.firstOrNull()
            ?: error("Your account does not have an active budget entity.")
        preferences.edit { putString("selected_entity_$userId", entityRow.id) }
        val periodStart = requestedPeriodStart ?: YearMonth.now().atDay(1).toString()
        var periodRows = synchroniseCarryovers(loadPeriods(entityRow.id))
        if (periodRows.none { it.startsOn == periodStart }) {
            val previous = periodRows.lastOrNull { it.startsOn < periodStart }
            val carryover = previous?.let { projectedSurplus(it) } ?: 0
            val createdPeriod = client.from("budget_periods").insert(
                NewBudgetPeriod(
                    userId = userId,
                    entityId = entityRow.id,
                    startsOn = periodStart,
                    status = if (periodStart > YearMonth.now().atDay(1).toString()) "draft" else "active",
                    carryoverCents = carryover,
                ),
            ) { select() }.decodeSingle<BudgetPeriodRow>()
            if (previous != null) copyPlanForward(userId, entityRow.id, previous, createdPeriod)
            periodRows = synchroniseCarryovers(loadPeriods(entityRow.id))
        }
        val periodRow = periodRows.single { it.startsOn == periodStart }
        val periodEnd = YearMonth.from(LocalDate.parse(periodStart)).plusMonths(1).atDay(1).toString()

        val profile = client.from("profiles")
            .select { limit(1) }
            .decodeList<ProfileRow>()
            .single()

        val accounts = client.from("accounts")
            .select {
                filter {
                    eq("entity_id", entityRow.id)
                    eq("is_active", true)
                }
                order("display_order", Order.ASCENDING)
            }
            .decodeList<AccountRow>()
        val categories = client.from("categories")
            .select { order("sort_order", Order.ASCENDING) }
            .decodeList<CategoryRow>()
        val budgets = client.from("budget_progress")
            .select {
                filter {
                    eq("entity_id", entityRow.id)
                    eq("period_start", periodStart)
                }
            }
            .decodeList<BudgetRow>()
        val plannedItems = client.from("planned_item_progress")
            .select {
                filter { eq("budget_period_id", periodRow.id) }
                order("sort_order", Order.ASCENDING)
            }
            .decodeList<PlannedItemRow>()
        val periodTransactions = client.from("transactions")
            .select {
                filter {
                    eq("entity_id", entityRow.id)
                    gte("occurred_on", periodStart)
                    lt("occurred_on", periodEnd)
                }
                order("occurred_on", Order.DESCENDING)
                limit(100)
            }
            .decodeList<TransactionRow>()
        
        val stsAccountIds = accounts
            .filter { it.includeInSafeToSpend }
            .map { it.id }
        
        val allPendingOutflows = if (stsAccountIds.isNotEmpty()) {
            client.from("transactions")
                .select {
                    filter {
                        eq("entity_id", entityRow.id)
                        eq("status", "pending")
                        isIn("account_id", stsAccountIds)
                    }
                }
                .decodeList<TransactionRow>()
                .filter { it.amountCents < 0 && it.kind != "transfer" && it.kind != "reversal" }
        } else {
            emptyList()
        }
        
        val transactions = (periodTransactions + allPendingOutflows)
            .distinctBy { it.id }
            .sortedByDescending { it.occurredOn }
        val matchRows = client.from("planned_item_matches")
            .select {
                filter {
                    eq("user_id", userId)
                }
            }
            .decodeList<MatchRow>()
        val matchesByTransaction = matchRows.groupBy({ it.transactionId }, { it.plannedItemId })
        val invoiceInbox = client.from("invoice_inboxes")
            .select {
                filter { eq("is_active", true) }
                limit(1)
            }
            .decodeList<InvoiceInboxRow>()
            .firstOrNull()
        val invoices = client.from("invoices")
            .select {
                order("received_at", Order.DESCENDING)
                limit(50)
            }
            .decodeList<InvoiceRow>()

        return MobileDashboard(
            month = formatPeriodRange(periodStart),
            profileDisplayName = profile.displayName,
            entity = entityRow.toModel(),
            entities = entityRows.map(EntityRow::toModel),
            period = BudgetPeriod(
                id = periodRow.id,
                startsOn = periodRow.startsOn,
                status = periodRow.status,
                carryoverCents = periodRow.carryoverCents,
            ),
            periods = periodRows.sortedByDescending { it.startsOn }.map { row ->
                BudgetPeriod(row.id, row.startsOn, row.status, row.carryoverCents)
            },
            accounts = accounts.map { row ->
                Account(
                    id = row.id,
                    entityId = row.entityId,
                    institution = row.institution,
                    externalKey = row.externalKey,
                    name = row.name,
                    type = row.type,
                    role = row.role,
                    purpose = row.purpose,
                    mask = row.mask,
                    currentBalanceCents = row.currentBalanceCents,
                    creditLimitCents = row.creditLimitCents,
                    includeInSafeToSpend = row.includeInSafeToSpend,
                    displayOrder = row.displayOrder,
                )
            },
            categories = categories.map { Category(it.id, it.name, it.colour, it.icon) },
            budgets = budgets.map { Budget(it.id, it.categoryId, it.limitCents, it.spentCents, it.committedCents) },
            plannedItems = plannedItems.map { row ->
                PlannedItem(
                    id = row.id,
                    direction = row.direction,
                    kind = row.kind,
                    name = row.name,
                    plannedCents = row.plannedCents,
                    actualCents = row.actualCents,
                    accountId = row.accountId,
                    categoryId = row.categoryId,
                    dueDay = row.dueDay,
                    sortOrder = row.sortOrder,
                    recurrence = row.recurrence,
                )
            },
            transactions = transactions.map { row ->
                Transaction(
                    id = row.id,
                    accountId = row.accountId,
                    categoryId = row.categoryId,
                    occurredOn = row.occurredOn,
                    occurredAt = row.occurredAt,
                    amountCents = row.amountCents,
                    status = row.status,
                    kind = row.kind,
                    merchant = row.merchant ?: "Unknown transaction",
                    description = row.description.orEmpty(),
                    needsReview = row.needsReview,
                    plannedItemIds = matchesByTransaction[row.id].orEmpty(),
                )
            },
            invoiceInbox = invoiceInbox?.let { InvoiceInbox(it.address) },
            invoices = invoices.map(InvoiceRow::toModel),
        )
    }

    suspend fun updateProfile(displayName: String) {
        val userId = authenticatedUserId()
        client.from("profiles").update(ProfileUpdate(displayName.trim())) {
            filter { eq("id", userId) }
        }
    }

    suspend fun saveEntity(entityId: String?, name: String, kind: String): String {
        val userId = authenticatedUserId()
        val cleanName = name.trim()
        return if (entityId == null) {
            client.from("entities").insert(
                NewEntity(
                    userId = userId,
                    name = cleanName,
                    kind = kind,
                    displayOrder = 100,
                ),
            ) { select() }.decodeSingle<EntityId>().id
        } else {
            client.from("entities").update(EntityDetailsUpdate(cleanName, kind)) {
                filter { eq("id", entityId) }
                select()
            }.decodeSingle<EntityId>().id
        }
    }

    suspend fun saveAccount(
        accountId: String?,
        entityId: String,
        institution: String,
        name: String,
        type: String,
        purpose: String,
        externalKey: String,
        mask: String,
        includeInSafeToSpend: Boolean,
    ) {
        val userId = authenticatedUserId()
        val normalizedKey = normalizeExternalKey(institution, externalKey)
        if (accountId == null) {
            client.from("accounts").insert(
                NewAccount(
                    userId = userId,
                    entityId = entityId,
                    institution = institution,
                    externalKey = normalizedKey,
                    name = name.trim(),
                    type = type,
                    role = roleFor(type),
                    purpose = purpose.trim(),
                    mask = mask.trim(),
                    includeInSafeToSpend = includeInSafeToSpend,
                    displayOrder = 100,
                ),
            )
        } else {
            client.from("accounts").update(
                AccountDetailsUpdate(
                    entityId = entityId,
                    institution = institution,
                    externalKey = normalizedKey,
                    name = name.trim(),
                    type = type,
                    role = roleFor(type),
                    purpose = purpose.trim(),
                    mask = mask.trim(),
                    includeInSafeToSpend = includeInSafeToSpend,
                ),
            ) {
                filter { eq("id", accountId) }
            }
        }
    }

    suspend fun loadAccountAssignments(institution: String): List<AccountAssignment> {
        authenticatedUserId()
        return client.from("accounts")
            .select {
                filter { eq("institution", institution) }
                order("display_order", Order.ASCENDING)
            }
            .decodeList<AccountAssignmentRow>()
            .map { AccountAssignment(it.externalKey, it.name, it.entityId, it.isActive) }
    }

    suspend fun assignDiscoveredAccounts(
        entityId: String,
        candidates: List<AccountMessageCandidate>,
    ): AccountDiscoveryImportReport {
        val userId = authenticatedUserId()
        var createdCount = 0
        var movedCount = 0
        var reactivatedCount = 0

        candidates.forEach { candidate ->
            val existing = findAccountAssignment(candidate.institution, candidate.externalKey)
            val accountId = if (existing == null) {
                createdCount += 1
                client.from("accounts").insert(
                    NewAccount(
                        userId = userId,
                        entityId = entityId,
                        institution = candidate.institution,
                        externalKey = candidate.externalKey,
                        name = candidate.suggestedName,
                        type = candidate.type,
                        role = candidate.role,
                        purpose = "Discovered from existing ${candidate.institution.uppercase()} messages — review this account",
                        mask = candidate.mask,
                        includeInSafeToSpend = false,
                        displayOrder = 100,
                    ),
                ) { select() }.decodeSingle<AccountLookup>().id
            } else {
                if (existing.entityId != entityId) movedCount += 1
                if (!existing.isActive) reactivatedCount += 1
                client.from("accounts").update(AccountEntityActiveUpdate(entityId, true)) {
                    filter { eq("id", existing.id) }
                }
                existing.id
            }

            val balanceCents = candidate.latestBalanceCents
            val balanceAtMillis = candidate.latestBalanceAtMillis
            val balanceAt = balanceAtMillis?.let(java.time.Instant::ofEpochMilli)
            val existingSyncAt = existing?.lastSyncedAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            if (balanceCents != null && balanceAt != null && (existingSyncAt == null || balanceAt.isAfter(existingSyncAt))) {
                client.from("accounts").update(AccountBalanceUpdate(balanceCents, balanceAt.toString())) {
                    filter { eq("id", accountId) }
                }
            }
        }

        return AccountDiscoveryImportReport(createdCount, movedCount, reactivatedCount)
    }

    suspend fun stopTrackingAccount(accountId: String) {
        authenticatedUserId()
        client.from("accounts").update(AccountActiveUpdate(false)) {
            filter { eq("id", accountId) }
        }
    }

    suspend fun savePlannedIncome(
        entityId: String,
        periodId: String,
        itemId: String?,
        name: String,
        plannedCents: Long,
        accountId: String?,
        dueDay: Int?,
        recurrence: String,
    ) {
        val userId = authenticatedUserId()
        if (itemId == null) {
            client.from("planned_items").insert(
                NewPlannedItem(
                    userId = userId,
                    entityId = entityId,
                    budgetPeriodId = periodId,
                    direction = "income",
                    kind = "income",
                    name = name.trim(),
                    plannedCents = plannedCents,
                    accountId = accountId,
                    categoryId = null,
                    dueDay = dueDay,
                    recurrence = recurrence,
                    sortOrder = 1000,
                ),
            )
        } else {
            client.from("planned_items").update(
                PlannedIncomeUpdate(
                    name = name.trim(),
                    plannedCents = plannedCents,
                    accountId = accountId,
                    dueDay = dueDay,
                    recurrence = recurrence,
                ),
            ) {
                filter {
                    eq("id", itemId)
                    eq("budget_period_id", periodId)
                }
            }
        }
    }

    suspend fun deletePlannedIncome(periodId: String, itemId: String) {
        authenticatedUserId()
        client.from("planned_items").delete {
            filter {
                eq("id", itemId)
                eq("budget_period_id", periodId)
                eq("direction", "income")
            }
        }
    }

    suspend fun savePlannedExpense(
        entityId: String,
        periodId: String,
        itemId: String?,
        name: String,
        kind: String,
        plannedCents: Long,
        accountId: String?,
        categoryId: String?,
        dueDay: Int?,
        recurrence: String,
    ) {
        val userId = authenticatedUserId()
        if (itemId == null) {
            client.from("planned_items").insert(
                NewPlannedItem(
                    userId = userId,
                    entityId = entityId,
                    budgetPeriodId = periodId,
                    direction = "expense",
                    kind = kind,
                    name = name.trim(),
                    plannedCents = plannedCents,
                    accountId = accountId,
                    categoryId = categoryId,
                    dueDay = dueDay,
                    recurrence = recurrence,
                    sortOrder = 1000,
                ),
            )
        } else {
            client.from("planned_items").update(
                PlannedExpenseUpdate(
                    kind = kind,
                    name = name.trim(),
                    plannedCents = plannedCents,
                    accountId = accountId,
                    categoryId = categoryId,
                    dueDay = dueDay,
                    recurrence = recurrence,
                ),
            ) {
                filter {
                    eq("id", itemId)
                    eq("budget_period_id", periodId)
                    eq("direction", "expense")
                }
            }
        }
    }

    suspend fun deletePlannedExpense(periodId: String, itemId: String) {
        authenticatedUserId()
        client.from("planned_items").delete {
            filter {
                eq("id", itemId)
                eq("budget_period_id", periodId)
                eq("direction", "expense")
            }
        }
    }

    suspend fun updateTransactionCategory(transactionId: String, categoryId: String?) {
        authenticatedUserId()
        client.from("transactions").update(
            TransactionCategoryUpdate(categoryId = categoryId, needsReview = categoryId == null),
        ) {
            filter { eq("id", transactionId) }
        }
    }

    suspend fun updateInvoiceDraft(
        invoiceId: String,
        supplierName: String,
        invoiceNumber: String?,
        outstandingCents: Long,
    ) {
        authenticatedUserId()
        client.from("invoices").update(
            InvoiceDraftUpdate(
                supplierName = supplierName.trim(),
                invoiceNumber = invoiceNumber?.trim()?.ifBlank { null },
                outstandingCents = outstandingCents,
            ),
        ) {
            filter {
                eq("id", invoiceId)
                eq("status", "needs_review")
            }
        }
    }

    suspend fun approveInvoice(
        invoiceId: String,
        entityId: String,
        periodId: String,
        categoryId: String?,
        accountId: String?,
        paymentCents: Long,
    ): String {
        authenticatedUserId()
        val parameters = buildJsonObject {
            put("p_invoice_id", invoiceId)
            put("p_entity_id", entityId)
            put("p_budget_period_id", periodId)
            if (categoryId == null) put("p_category_id", JsonNull) else put("p_category_id", categoryId)
            if (accountId == null) put("p_account_id", JsonNull) else put("p_account_id", accountId)
            put("p_payment_cents", paymentCents)
        }
        return client.postgrest.rpc("approve_invoice", parameters).decodeAs()
    }

    suspend fun rejectInvoice(invoiceId: String) {
        authenticatedUserId()
        client.from("invoices").update(InvoiceStatusUpdate("rejected")) {
            filter {
                eq("id", invoiceId)
                eq("status", "needs_review")
            }
        }
    }

    suspend fun upload(rows: List<LocalTransactionEntity>, onUploaded: suspend (Long) -> Unit): UploadReport {
        client.auth.awaitInitialization()
        if (client.auth.currentUserOrNull() == null) throw NotAuthenticatedException()
        client.auth.refreshCurrentSession()
        val userId = client.auth.currentUserOrNull()?.id ?: throw NotAuthenticatedException()
        val deviceId = deviceId()
        registerDevice(userId, deviceId)

        val detected = linkedSetOf<String>()
        for (row in rows) {
            val resolution = resolveAccount(userId, row.institution, row.accountExternalKey)
            val accountId = resolution.id
            if (resolution.wasCreated) detected += row.accountExternalKey
            client.from("transactions").upsert(
                TransactionUpload(
                    userId = userId,
                    entityId = resolution.entityId,
                    accountId = accountId,
                    deviceId = deviceId,
                    occurredOn = row.occurredOn,
                    amountCents = row.amountCents,
                    availableBalanceCents = row.availableBalanceCents,
                    status = row.status,
                    kind = row.kind,
                    source = "sms",
                    sourceFingerprint = row.sourceFingerprint,
                    merchant = row.merchant,
                    description = row.description,
                    reference = row.reference,
                    parserVersion = row.parserVersion,
                    parseConfidence = row.parseConfidence,
                    needsReview = row.needsReview,
                ),
            ) {
                onConflict = "user_id,source,source_fingerprint"
                ignoreDuplicates = true
            }
            refreshAccountBalance(accountId)
            onUploaded(row.id)
        }
        return UploadReport(detected)
    }

    private suspend fun findAccount(institution: String, externalKey: String): AccountLookup? = client.from("accounts")
        .select {
            filter {
                eq("institution", institution)
                eq("external_key", externalKey)
            }
        }
        .decodeList<AccountLookup>()
        .singleOrNull()

    private suspend fun findAccountAssignment(institution: String, externalKey: String): AccountAssignmentRow? = client.from("accounts")
        .select {
            filter {
                eq("institution", institution)
                eq("external_key", externalKey)
            }
        }
        .decodeList<AccountAssignmentRow>()
        .singleOrNull()

    private suspend fun resolveAccount(userId: String, institution: String, externalKey: String): AccountResolution {
        findAccount(institution, externalKey)?.let { return AccountResolution(it.id, it.entityId, false) }
        val defaultEntityId = client.from("entities")
            .select { filter { eq("is_default", true) } }
            .decodeList<EntityId>()
            .single()
            .id
        val inferredType = inferAccountType(externalKey)
        val created = client.from("accounts").upsert(
            NewAccount(
                userId = userId,
                entityId = defaultEntityId,
                institution = institution,
                externalKey = externalKey,
                name = "${institution.uppercase()} ${externalKey.takeLast(8)}",
                type = inferredType,
                role = roleFor(inferredType),
                purpose = "Auto-detected from a ${institution.uppercase()} transaction SMS — review this account",
                mask = externalKey.takeLast(4),
                includeInSafeToSpend = false,
                displayOrder = 100,
            ),
        ) {
            onConflict = "user_id,institution,external_key"
            select()
        }.decodeSingle<AccountLookup>()
        return AccountResolution(created.id, created.entityId, true)
    }

    private suspend fun refreshAccountBalance(accountId: String) {
        val latest = client.from("transactions")
            .select {
                filter { eq("account_id", accountId) }
                order("occurred_on", Order.DESCENDING)
                order("created_at", Order.DESCENDING)
                limit(1)
            }
            .decodeList<AccountBalanceRow>()
            .singleOrNull()
            ?: return
        val balance = latest.availableBalanceCents ?: return
        client.from("accounts").update(
            AccountBalanceUpdate(
                currentBalanceCents = balance,
                lastSyncedAt = java.time.Instant.now().toString(),
            ),
        ) {
            filter { eq("id", accountId) }
        }
    }

    private suspend fun loadPeriods(entityId: String): List<BudgetPeriodRow> = client.from("budget_periods")
        .select {
            filter { eq("entity_id", entityId) }
            order("starts_on", Order.ASCENDING)
        }
        .decodeList()

    private suspend fun synchroniseCarryovers(periods: List<BudgetPeriodRow>): List<BudgetPeriodRow> {
        if (periods.size < 2) return periods
        val updated = periods.sortedBy { it.startsOn }.toMutableList()
        for (index in 1 until updated.size) {
            val desired = projectedSurplus(updated[index - 1])
            val current = updated[index]
            if (current.carryoverCents != desired) {
                client.from("budget_periods").update(BudgetPeriodCarryoverUpdate(desired)) {
                    filter { eq("id", current.id) }
                }
                updated[index] = current.copy(carryoverCents = desired)
            }
        }
        return updated
    }

    private suspend fun projectedSurplus(period: BudgetPeriodRow): Long {
        val items = client.from("planned_item_progress")
            .select { filter { eq("budget_period_id", period.id) } }
            .decodeList<PlannedItemRow>()
        val income = items.filter { it.direction == "income" }.sumOf { it.plannedCents }
        val expenses = items.filter { it.direction == "expense" }.sumOf { it.plannedCents }
        return period.carryoverCents + income - expenses
    }

    private suspend fun copyPlanForward(
        userId: String,
        entityId: String,
        previous: BudgetPeriodRow,
        target: BudgetPeriodRow,
    ) {
        val recurringItems = client.from("planned_items")
            .select { filter { eq("budget_period_id", previous.id) } }
            .decodeList<PlannedItemCloneRow>()
            .filter { it.recurrence == "monthly" }
        recurringItems.forEach { item ->
            client.from("planned_items").insert(
                NewPlannedItem(
                    userId = userId,
                    entityId = entityId,
                    budgetPeriodId = target.id,
                    direction = item.direction,
                    kind = item.kind,
                    name = item.name,
                    plannedCents = item.plannedCents,
                    accountId = item.accountId,
                    categoryId = item.categoryId,
                    dueDay = item.dueDay,
                    recurrence = item.recurrence,
                    sortOrder = item.sortOrder,
                ),
            )
        }

        val budgets = client.from("budgets")
            .select {
                filter {
                    eq("entity_id", entityId)
                    eq("period_start", previous.startsOn)
                }
            }
            .decodeList<BudgetCloneRow>()
        budgets.forEach { budget ->
            client.from("budgets").insert(
                NewBudget(
                    userId = userId,
                    entityId = entityId,
                    categoryId = budget.categoryId,
                    periodStart = target.startsOn,
                    limitCents = budget.limitCents,
                    rollover = budget.rollover,
                ),
            )
        }
    }

    private fun inferAccountType(externalKey: String): String = when {
        externalKey.contains("SAVE", ignoreCase = true) -> "savings"
        externalKey.contains("CARD", ignoreCase = true) || externalKey.startsWith("CC", ignoreCase = true) -> "credit_card"
        else -> "cheque"
    }

    private fun normalizeExternalKey(institution: String, externalKey: String): String {
        val key = externalKey.trim()
        if (institution != "fnb") return key
        val suffix = key.removePrefix("FNB-").trim().takeIf { it.matches(Regex("\\d{4,10}")) }
        return suffix?.let { "FNB-$it" } ?: key.uppercase()
    }

    private fun roleFor(type: String): String = when (type) {
        "savings" -> "savings"
        "home_loan" -> "liability"
        "rewards" -> "rewards"
        else -> "operational"
    }

    private suspend fun authenticatedUserId(): String {
        client.auth.awaitInitialization()
        if (client.auth.currentUserOrNull() == null) throw NotAuthenticatedException()
        client.auth.refreshCurrentSession()
        return client.auth.currentUserOrNull()?.id ?: throw NotAuthenticatedException()
    }

    private suspend fun registerDevice(userId: String, deviceId: String) {
        client.from("devices").upsert(
            DeviceUpload(
                id = deviceId,
                userId = userId,
                name = listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim(),
                appVersion = BuildConfig.VERSION_NAME,
                lastSeenAt = java.time.Instant.now().toString(),
            ),
        ) {
            onConflict = "id"
        }
    }

    private fun deviceId(): String {
        preferences.getString("device_id", null)?.let { return it }
        return UUID.randomUUID().toString().also {
            preferences.edit { putString("device_id", it) }
        }
    }

    companion object {
        fun create(context: Context): SupabaseCollectorClient? {
            if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()) return null
            val client = createSupabaseClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            ) {
                install(Auth) {
                    alwaysAutoRefresh = false
                    enableLifecycleCallbacks = false
                }
                install(Postgrest)
            }
            return SupabaseCollectorClient(context.applicationContext, client)
        }
    }
}

@Serializable
private data class EntityId(val id: String)

@Serializable
private data class EntityRow(
    val id: String,
    val name: String,
    val kind: String,
    @SerialName("is_default") val isDefault: Boolean,
    @SerialName("display_order") val displayOrder: Int,
) {
    fun toModel() = Entity(id, name, kind, isDefault, displayOrder)
}

@Serializable
private data class NewEntity(
    @SerialName("user_id") val userId: String,
    val name: String,
    val kind: String,
    @SerialName("display_order") val displayOrder: Int,
)

@Serializable
private data class EntityDetailsUpdate(
    val name: String,
    val kind: String,
)

@Serializable
private data class AccountLookup(
    val id: String,
    @SerialName("entity_id") val entityId: String,
)

@Serializable
private data class AccountAssignmentRow(
    val id: String,
    @SerialName("entity_id") val entityId: String,
    @SerialName("external_key") val externalKey: String,
    val name: String,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("last_synced_at") val lastSyncedAt: String?,
)

@Serializable
private data class NewBudgetPeriod(
    @SerialName("user_id") val userId: String,
    @SerialName("entity_id") val entityId: String,
    @SerialName("starts_on") val startsOn: String,
    val status: String,
    @SerialName("carryover_cents") val carryoverCents: Long,
)

@Serializable
private data class BudgetPeriodCarryoverUpdate(
    @SerialName("carryover_cents") val carryoverCents: Long,
)

@Serializable
private data class ProfileRow(
    @SerialName("display_name") val displayName: String,
)

@Serializable
private data class ProfileUpdate(
    @SerialName("display_name") val displayName: String,
)

@Serializable
private data class BudgetPeriodRow(
    val id: String,
    @SerialName("starts_on") val startsOn: String,
    val status: String,
    @SerialName("carryover_cents") val carryoverCents: Long,
)

@Serializable
private data class AccountRow(
    val id: String,
    @SerialName("entity_id") val entityId: String,
    val institution: String,
    @SerialName("external_key") val externalKey: String,
    val name: String,
    val type: String,
    val role: String,
    val purpose: String,
    val mask: String,
    @SerialName("current_balance_cents") val currentBalanceCents: Long,
    @SerialName("credit_limit_cents") val creditLimitCents: Long?,
    @SerialName("include_in_safe_to_spend") val includeInSafeToSpend: Boolean,
    @SerialName("display_order") val displayOrder: Int,
)

@Serializable
private data class CategoryRow(
    val id: String,
    val name: String,
    val colour: String,
    val icon: String,
)

@Serializable
private data class BudgetRow(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("limit_cents") val limitCents: Long,
    @SerialName("spent_cents") val spentCents: Long,
    @SerialName("committed_cents") val committedCents: Long,
)

@Serializable
private data class PlannedItemRow(
    val id: String,
    val direction: String,
    val kind: String,
    val name: String,
    @SerialName("planned_cents") val plannedCents: Long,
    @SerialName("actual_cents") val actualCents: Long,
    @SerialName("account_id") val accountId: String?,
    @SerialName("category_id") val categoryId: String?,
    @SerialName("due_day") val dueDay: Int?,
    val recurrence: String,
    @SerialName("sort_order") val sortOrder: Int,
)

@Serializable
private data class TransactionRow(
    val id: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("category_id") val categoryId: String?,
    @SerialName("occurred_on") val occurredOn: String,
    @SerialName("occurred_at") val occurredAt: String?,
    @SerialName("amount_cents") val amountCents: Long,
    val status: String,
    val kind: String,
    val merchant: String?,
    val description: String?,
    @SerialName("needs_review") val needsReview: Boolean,
)

@Serializable
private data class MatchRow(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("planned_item_id") val plannedItemId: String,
)

@Serializable
private data class TransactionCategoryUpdate(
    @SerialName("category_id") val categoryId: String?,
    @SerialName("needs_review") val needsReview: Boolean,
)

@Serializable
private data class InvoiceInboxRow(val address: String)

@Serializable
private data class InvoiceRow(
    val id: String,
    @SerialName("entity_id") val entityId: String?,
    val status: String,
    @SerialName("extraction_status") val extractionStatus: String,
    @SerialName("extraction_confidence") val extractionConfidence: Double,
    @SerialName("supplier_name") val supplierName: String?,
    @SerialName("supplier_registration_number") val supplierRegistrationNumber: String?,
    @SerialName("supplier_vat_number") val supplierVatNumber: String?,
    @SerialName("supplier_contact_email") val supplierContactEmail: String?,
    @SerialName("invoice_number") val invoiceNumber: String?,
    @SerialName("issue_date") val issueDate: String?,
    @SerialName("due_date") val dueDate: String?,
    val currency: String,
    @SerialName("total_cents") val totalCents: Long?,
    @SerialName("outstanding_cents") val outstandingCents: Long?,
    @SerialName("payment_reference") val paymentReference: String?,
    @SerialName("bank_name") val bankName: String?,
    @SerialName("bank_account_holder") val bankAccountHolder: String?,
    @SerialName("bank_account_number") val bankAccountNumber: String?,
    @SerialName("bank_branch_code") val bankBranchCode: String?,
    @SerialName("bank_account_type") val bankAccountType: String?,
    @SerialName("extraction_warnings") val warnings: List<String>,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("original_file_name") val originalFileName: String,
) {
    fun toModel() = Invoice(
        id = id,
        entityId = entityId,
        status = status,
        extractionStatus = extractionStatus,
        extractionConfidence = extractionConfidence,
        supplierName = supplierName,
        supplierRegistrationNumber = supplierRegistrationNumber,
        supplierVatNumber = supplierVatNumber,
        supplierContactEmail = supplierContactEmail,
        invoiceNumber = invoiceNumber,
        issueDate = issueDate,
        dueDate = dueDate,
        currency = currency,
        totalCents = totalCents,
        outstandingCents = outstandingCents,
        paymentReference = paymentReference,
        bankName = bankName,
        bankAccountHolder = bankAccountHolder,
        bankAccountNumber = bankAccountNumber,
        bankBranchCode = bankBranchCode,
        bankAccountType = bankAccountType,
        warnings = warnings,
        receivedAt = receivedAt,
        originalFileName = originalFileName,
    )
}

@Serializable
private data class InvoiceDraftUpdate(
    @SerialName("supplier_name") val supplierName: String,
    @SerialName("invoice_number") val invoiceNumber: String?,
    @SerialName("outstanding_cents") val outstandingCents: Long,
)

@Serializable
private data class InvoiceStatusUpdate(val status: String)


@Serializable
private data class NewAccount(
    @SerialName("user_id") val userId: String,
    @SerialName("entity_id") val entityId: String,
    val institution: String,
    @SerialName("external_key") val externalKey: String,
    val name: String,
    val type: String,
    val role: String,
    val purpose: String,
    val mask: String,
    @SerialName("include_in_safe_to_spend") val includeInSafeToSpend: Boolean,
    @SerialName("display_order") val displayOrder: Int,
)

@Serializable
private data class AccountDetailsUpdate(
    @SerialName("entity_id") val entityId: String,
    val institution: String,
    @SerialName("external_key") val externalKey: String,
    val name: String,
    val type: String,
    val role: String,
    val purpose: String,
    val mask: String,
    @SerialName("include_in_safe_to_spend") val includeInSafeToSpend: Boolean,
)

@Serializable
private data class AccountActiveUpdate(
    @SerialName("is_active") val isActive: Boolean,
)

@Serializable
private data class AccountEntityActiveUpdate(
    @SerialName("entity_id") val entityId: String,
    @SerialName("is_active") val isActive: Boolean,
)

@Serializable
private data class AccountBalanceUpdate(
    @SerialName("current_balance_cents") val currentBalanceCents: Long,
    @SerialName("last_synced_at") val lastSyncedAt: String,
)

@Serializable
private data class AccountBalanceRow(
    @SerialName("available_balance_cents") val availableBalanceCents: Long?,
)

@Serializable
private data class PlannedItemCloneRow(
    val direction: String,
    val kind: String,
    val name: String,
    @SerialName("planned_cents") val plannedCents: Long,
    @SerialName("account_id") val accountId: String?,
    @SerialName("category_id") val categoryId: String?,
    @SerialName("due_day") val dueDay: Int?,
    val recurrence: String,
    @SerialName("sort_order") val sortOrder: Int,
)

@Serializable
private data class NewPlannedItem(
    @SerialName("user_id") val userId: String,
    @SerialName("entity_id") val entityId: String,
    @SerialName("budget_period_id") val budgetPeriodId: String,
    val direction: String,
    val kind: String,
    val name: String,
    @SerialName("planned_cents") val plannedCents: Long,
    @SerialName("account_id") val accountId: String?,
    @SerialName("category_id") val categoryId: String?,
    @SerialName("due_day") val dueDay: Int?,
    val recurrence: String,
    @SerialName("sort_order") val sortOrder: Int,
)

@Serializable
private data class PlannedIncomeUpdate(
    val name: String,
    @SerialName("planned_cents") val plannedCents: Long,
    @SerialName("account_id") val accountId: String?,
    @SerialName("due_day") val dueDay: Int?,
    val recurrence: String,
)

@Serializable
private data class PlannedExpenseUpdate(
    val kind: String,
    val name: String,
    @SerialName("planned_cents") val plannedCents: Long,
    @SerialName("account_id") val accountId: String?,
    @SerialName("category_id") val categoryId: String?,
    @SerialName("due_day") val dueDay: Int?,
    val recurrence: String,
)

@Serializable
private data class BudgetCloneRow(
    @SerialName("category_id") val categoryId: String,
    @SerialName("limit_cents") val limitCents: Long,
    val rollover: Boolean,
)

@Serializable
private data class NewBudget(
    @SerialName("user_id") val userId: String,
    @SerialName("entity_id") val entityId: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("period_start") val periodStart: String,
    @SerialName("limit_cents") val limitCents: Long,
    val rollover: Boolean,
)

private data class AccountResolution(val id: String, val entityId: String, val wasCreated: Boolean)

@Serializable
private data class DeviceUpload(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val platform: String = "android",
    @SerialName("app_version") val appVersion: String,
    @SerialName("last_seen_at") val lastSeenAt: String,
)

@Serializable
private data class TransactionUpload(
    @SerialName("user_id") val userId: String,
    @SerialName("entity_id") val entityId: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("occurred_on") val occurredOn: String,
    @SerialName("amount_cents") val amountCents: Long,
    @SerialName("available_balance_cents") val availableBalanceCents: Long,
    val status: String,
    val kind: String,
    val source: String,
    @SerialName("source_fingerprint") val sourceFingerprint: String,
    val merchant: String?,
    val description: String,
    val reference: String?,
    @SerialName("parser_version") val parserVersion: Int,
    @SerialName("parse_confidence") val parseConfidence: Double,
    @SerialName("needs_review") val needsReview: Boolean,
)
