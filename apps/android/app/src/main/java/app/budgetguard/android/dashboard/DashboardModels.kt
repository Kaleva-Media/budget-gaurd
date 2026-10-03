package app.budgetguard.android.dashboard

import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class MobileDashboard(
    val month: String,
    val profileDisplayName: String,
    val entity: Entity,
    val entities: List<Entity>,
    val period: BudgetPeriod,
    val periods: List<BudgetPeriod>,
    val accounts: List<Account>,
    val categories: List<Category>,
    val budgets: List<Budget>,
    val plannedItems: List<PlannedItem>,
    val transactions: List<Transaction>,
    val invoiceInbox: InvoiceInbox?,
    val invoices: List<Invoice>,
    val debts: List<Debt> = emptyList(),
    val debtPreferences: DebtPreferences = DebtPreferences(),
    val debtCheckIns: List<DebtCheckIn> = emptyList(),
    val debtSpendingHistory: List<DebtSpendingMonth> = emptyList(),
) {
    val transactionsNeedingReview: Int
        get() = transactions.count {
            it.plannedItemIds.isEmpty() &&
                it.amountCents < 0 &&
                it.kind !in setOf("transfer", "reversal") &&
                (it.needsReview || it.categoryId == null)
        }

    val invoicesNeedingReview: Int
        get() = invoices.count { it.status == "needs_review" }
}

data class InvoiceInbox(val address: String)

data class Invoice(
    val id: String,
    val entityId: String?,
    val status: String,
    val extractionStatus: String,
    val extractionConfidence: Double,
    val supplierName: String?,
    val supplierRegistrationNumber: String?,
    val supplierVatNumber: String?,
    val supplierContactEmail: String?,
    val invoiceNumber: String?,
    val issueDate: String?,
    val dueDate: String?,
    val currency: String,
    val totalCents: Long?,
    val outstandingCents: Long?,
    val paymentReference: String?,
    val bankName: String?,
    val bankAccountHolder: String?,
    val bankAccountNumber: String?,
    val bankBranchCode: String?,
    val bankAccountType: String?,
    val warnings: List<String>,
    val receivedAt: String,
    val originalFileName: String,
)

data class Entity(
    val id: String,
    val name: String,
    val kind: String,
    val isDefault: Boolean,
    val displayOrder: Int,
    val budgetCycleDay: Int = 1,
)

fun categoryScopeForEntityKind(kind: String): String =
    if (kind == "personal") "personal" else "business"

data class BudgetPeriod(
    val id: String,
    val startsOn: String,
    val status: String,
    val carryoverCents: Long,
)

data class BudgetCycleBounds(
    val startsOn: LocalDate,
    val endsOnExclusive: LocalDate,
)

fun budgetPeriodLabelFor(date: LocalDate, cycleDay: Int): String {
    require(cycleDay in 1..28) { "Budget cycle day must be between 1 and 28." }
    val labelMonth = when {
        cycleDay == 1 -> YearMonth.from(date)
        date.dayOfMonth >= cycleDay -> YearMonth.from(date).plusMonths(1)
        else -> YearMonth.from(date)
    }
    return labelMonth.atDay(1).toString()
}

fun budgetCycleBounds(periodLabelStart: String, cycleDay: Int): BudgetCycleBounds {
    require(cycleDay in 1..28) { "Budget cycle day must be between 1 and 28." }
    val labelMonth = YearMonth.from(LocalDate.parse(periodLabelStart))
    val start = if (cycleDay == 1) labelMonth.atDay(1) else labelMonth.minusMonths(1).atDay(cycleDay)
    val end = if (cycleDay == 1) labelMonth.plusMonths(1).atDay(1) else labelMonth.atDay(cycleDay)
    return BudgetCycleBounds(start, end)
}

data class Account(
    val id: String,
    val entityId: String,
    val institution: String,
    val externalKey: String,
    val name: String,
    val type: String,
    val role: String,
    val purpose: String,
    val mask: String,
    val currentBalanceCents: Long,
    val creditLimitCents: Long?,
    val includeInSafeToSpend: Boolean,
    val displayOrder: Int,
) {
    val mappingNeedsAttention: Boolean
        get() = externalKey.startsWith("credit-card-") || mask.equals("Add mask", ignoreCase = true)
}

data class Category(
    val id: String,
    val name: String,
    val colour: String,
    val icon: String,
    val systemKey: String? = null,
)

data class Budget(
    val id: String,
    val categoryId: String,
    val limitCents: Long,
    val spentCents: Long,
    val committedCents: Long,
) {
    val usedCents: Long get() = spentCents + committedCents
    val remainingCents: Long get() = limitCents - usedCents
    val percentage: Int
        get() = if (limitCents <= 0) 0 else ((usedCents * 100) / limitCents).coerceIn(0, 100).toInt()
}

fun availableBudgetCategories(
    categories: List<Category>,
    budgets: List<Budget>,
    editingBudgetId: String? = null,
): List<Category> {
    val assignedCategoryIds = budgets
        .filterNot { it.id == editingBudgetId }
        .mapTo(mutableSetOf(), Budget::categoryId)
    return categories.filterNot { it.id in assignedCategoryIds }
}

data class PlannedItem(
    val id: String,
    val direction: String,
    val kind: String,
    val name: String,
    val plannedCents: Long,
    val actualCents: Long,
    val accountId: String?,
    val categoryId: String?,
    val dueDay: Int?,
    val sortOrder: Int,
    val recurrence: String = "monthly",
    val manuallyPaid: Boolean = false,
) {
    val isPaid: Boolean
        get() = direction == "expense" && (manuallyPaid || actualCents >= plannedCents)
}

enum class ExpenseOrder { NAME, AMOUNT }

data class PlannedItemGroups(
    val income: List<PlannedItem>,
    val unpaidExpenses: List<PlannedItem>,
    val paidExpenses: List<PlannedItem>,
)

fun groupPlannedItems(
    items: List<PlannedItem>,
    query: String,
    expenseOrder: ExpenseOrder,
): PlannedItemGroups {
    val needle = query.trim().lowercase(Locale.forLanguageTag("en-ZA"))
    val visible = if (needle.isEmpty()) items else items.filter {
        it.name.lowercase(Locale.forLanguageTag("en-ZA")).contains(needle)
    }
    val expenseComparator = when (expenseOrder) {
        ExpenseOrder.NAME -> compareBy<PlannedItem> { it.name.lowercase(Locale.forLanguageTag("en-ZA")) }
            .thenBy { it.sortOrder }
        ExpenseOrder.AMOUNT -> compareByDescending<PlannedItem> { it.plannedCents }
            .thenBy { it.name.lowercase(Locale.forLanguageTag("en-ZA")) }
    }
    val expenses = visible.filter { it.direction == "expense" }.sortedWith(expenseComparator)
    return PlannedItemGroups(
        income = visible.filter { it.direction == "income" }
            .sortedWith(compareBy<PlannedItem> { it.sortOrder }.thenBy { it.name }),
        unpaidExpenses = expenses.filterNot { it.isPaid },
        paidExpenses = expenses.filter { it.isPaid },
    )
}

data class Transaction(
    val id: String,
    val accountId: String,
    val categoryId: String?,
    val occurredOn: String,
    val occurredAt: String?,
    val amountCents: Long,
    val status: String,
    val kind: String,
    val merchant: String,
    val description: String,
    val needsReview: Boolean,
    val plannedItemIds: List<String> = emptyList(),
)

data class TransactionMatchSuggestion(
    val transaction: Transaction,
    val plannedItem: PlannedItem,
    val amountCents: Long,
)

fun MobileDashboard.exactTransactionMatchSuggestions(): List<TransactionMatchSuggestion> {
    val eligibleTransactions = transactions.filter { transaction ->
        transaction.plannedItemIds.isEmpty() &&
            transaction.amountCents != 0L &&
            transaction.amountCents != Long.MIN_VALUE &&
            transaction.status in setOf("posted", "pending") &&
            transaction.kind !in setOf("transfer", "reversal")
    }
    val eligibleItems = plannedItems.filter { item ->
        item.actualCents < item.plannedCents
    }

    val candidates = eligibleTransactions.flatMap { transaction ->
        val amountCents = kotlin.math.abs(transaction.amountCents)
        val direction = if (transaction.amountCents < 0) "expense" else "income"
        eligibleItems
            .filter { item ->
                item.direction == direction &&
                    item.plannedCents - item.actualCents == amountCents &&
                    (item.accountId == null || item.accountId == transaction.accountId)
            }
            .map { item -> TransactionMatchSuggestion(transaction, item, amountCents) }
    }
    val candidatesPerTransaction = candidates.groupingBy { it.transaction.id }.eachCount()
    val candidatesPerItem = candidates.groupingBy { it.plannedItem.id }.eachCount()

    return candidates
        .filter { suggestion ->
            candidatesPerTransaction[suggestion.transaction.id] == 1 &&
                candidatesPerItem[suggestion.plannedItem.id] == 1
        }
        .sortedByDescending { it.transaction.occurredOn }
}

data class CashflowSummary(
    val plannedIncomeCents: Long,
    val plannedExpenseCents: Long,
    val projectedSurplusCents: Long,
    val allocationPercentage: Int,
)

data class BudgetSummary(
    val limitCents: Long,
    val spentCents: Long,
    val committedCents: Long,
    val remainingCents: Long,
    val safeToSpendTodayCents: Long,
    val daysRemaining: Int,
)

data class SafeToSpendSummary(
    val bCents: Long,
    val rCents: Long,
    val pCents: Long,
    val cCents: Long,
    val safeToSpendCents: Long,
)

enum class HomeHeroMode {
    CURRENT_SAFE_TO_SPEND,
    FUTURE_DAILY_PLAN,
    PAST_PLAN_RESULT,
}

data class HomeHeroSummary(
    val mode: HomeHeroMode,
    val amountCents: Long,
    val planPositionCents: Long,
    val dayCount: Int?,
)

fun MobileDashboard.cashflowSummary(): CashflowSummary {
    val income = plannedItems.filter { it.direction == "income" }.sumOf { it.plannedCents }
    val expenses = plannedItems.filter { it.direction == "expense" }.sumOf { it.plannedCents }
    val available = period.carryoverCents + income
    return CashflowSummary(
        plannedIncomeCents = income,
        plannedExpenseCents = expenses,
        projectedSurplusCents = available - expenses,
        allocationPercentage = if (available > 0) ((expenses * 100) / available).toInt() else 0,
    )
}

fun MobileDashboard.homeHeroSummary(today: LocalDate = LocalDate.now()): HomeHeroSummary {
    val cycle = budgetCycleBounds(period.startsOn, entity.budgetCycleDay)
    val planPositionCents = cashflowSummary().projectedSurplusCents

    return when {
        today.isBefore(cycle.startsOn) -> {
            val dayCount = ChronoUnit.DAYS.between(cycle.startsOn, cycle.endsOnExclusive).toInt()
            HomeHeroSummary(
                mode = HomeHeroMode.FUTURE_DAILY_PLAN,
                amountCents = dailyPlanAmountCents(planPositionCents, dayCount),
                planPositionCents = planPositionCents,
                dayCount = dayCount,
            )
        }
        !today.isBefore(cycle.endsOnExclusive) -> HomeHeroSummary(
            mode = HomeHeroMode.PAST_PLAN_RESULT,
            amountCents = planPositionCents,
            planPositionCents = planPositionCents,
            dayCount = null,
        )
        else -> HomeHeroSummary(
            mode = HomeHeroMode.CURRENT_SAFE_TO_SPEND,
            amountCents = summariseSafeToSpend().safeToSpendCents,
            planPositionCents = planPositionCents,
            dayCount = null,
        )
    }
}

private fun dailyPlanAmountCents(planPositionCents: Long, dayCount: Int): Long {
    val wholeCents = planPositionCents / dayCount
    val remainder = planPositionCents % dayCount
    return if (planPositionCents < 0 && remainder != 0L) wholeCents - 1 else wholeCents
}

fun MobileDashboard.budgetSummary(today: LocalDate = LocalDate.now()): BudgetSummary {
    val limit = budgets.sumOf { it.limitCents }
    val spent = budgets.sumOf { it.spentCents }
    val committed = budgets.sumOf { it.committedCents }
    val remaining = (limit - spent - committed).coerceAtLeast(0)
    val cycle = budgetCycleBounds(period.startsOn, entity.budgetCycleDay)
    val daysRemaining = when {
        today.isBefore(cycle.startsOn) -> ChronoUnit.DAYS.between(cycle.startsOn, cycle.endsOnExclusive).toInt()
        !today.isBefore(cycle.endsOnExclusive) -> 1
        else -> ChronoUnit.DAYS.between(today, cycle.endsOnExclusive).toInt().coerceAtLeast(1)
    }
    return BudgetSummary(
        limitCents = limit,
        spentCents = spent,
        committedCents = committed,
        remainingCents = remaining,
        safeToSpendTodayCents = remaining / daysRemaining,
        daysRemaining = daysRemaining,
    )
}

fun formatZar(cents: Long, showSign: Boolean = false): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-ZA"))
    val formatted = formatter.format(kotlin.math.abs(cents) / 100.0)
    if (!showSign) return if (cents < 0) "−$formatted" else formatted
    return when {
        cents > 0 -> "+$formatted"
        cents < 0 -> "−$formatted"
        else -> formatted
    }
}

fun formatTransactionDate(date: String): String = runCatching {
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
}.getOrDefault(date)

fun formatPeriodRange(startsOn: String, cycleDay: Int = 1): String = runCatching {
    if (cycleDay != 1) {
        val cycle = budgetCycleBounds(startsOn, cycleDay)
        val start = cycle.startsOn.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
        val end = cycle.endsOnExclusive.minusDays(1).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
        return@runCatching "$start – $end"
    }
    val start = YearMonth.from(LocalDate.parse(startsOn))
    val end = start.plusMonths(1)
    val startName = start.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH))
    val endName = end.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
    "$startName – $endName"
}.getOrDefault(startsOn)

fun MobileDashboard.summariseSafeToSpend(): SafeToSpendSummary {
    val bCents = accounts
        .filter { it.includeInSafeToSpend }
        .sumOf { it.currentBalanceCents }

    val matchedCentsByItem = mutableMapOf<String, Long>()
    for (tx in transactions) {
        if (tx.status == "posted" || tx.status == "pending") {
            for (plannedId in tx.plannedItemIds) {
                val current = matchedCentsByItem.getOrDefault(plannedId, 0L)
                matchedCentsByItem[plannedId] = current + kotlin.math.abs(tx.amountCents)
            }
        }
    }

    val rCents = plannedItems
        .filter { it.direction == "expense" }
        .sumOf { item ->
            val matched = if (item.manuallyPaid) {
                item.plannedCents
            } else {
                maxOf(item.actualCents, matchedCentsByItem.getOrDefault(item.id, 0L))
            }
            val remaining = maxOf(0L, item.plannedCents - matched)
            remaining
        }

    val stsAccountIds = accounts
        .filter { it.includeInSafeToSpend }
        .map { it.id }
        .toSet()

    val pCents = transactions
        .filter { tx ->
            tx.status == "pending" &&
            tx.amountCents < 0 &&
            tx.kind != "transfer" && tx.kind != "reversal" &&
            stsAccountIds.contains(tx.accountId)
        }
        .sumOf { kotlin.math.abs(it.amountCents) }

    val cCents = rCents + pCents
    val safeToSpendCents = bCents - cCents

    return SafeToSpendSummary(
        bCents = bCents,
        rCents = rCents,
        pCents = pCents,
        cCents = cCents,
        safeToSpendCents = safeToSpendCents,
    )
}
