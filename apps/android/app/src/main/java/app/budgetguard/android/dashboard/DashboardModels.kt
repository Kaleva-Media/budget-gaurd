package app.budgetguard.android.dashboard

import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
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
) {
    val transactionsNeedingReview: Int
        get() = transactions.count { it.needsReview || (it.amountCents < 0 && it.categoryId == null) }

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
)

data class BudgetPeriod(
    val id: String,
    val startsOn: String,
    val status: String,
    val carryoverCents: Long,
)

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
)

data class Budget(
    val id: String,
    val categoryId: String,
    val limitCents: Long,
    val spentCents: Long,
    val committedCents: Long,
) {
    val usedCents: Long get() = spentCents + committedCents
    val percentage: Int
        get() = if (limitCents <= 0) 0 else ((usedCents * 100) / limitCents).coerceIn(0, 100).toInt()
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
)

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
)

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

fun MobileDashboard.budgetSummary(today: LocalDate = LocalDate.now()): BudgetSummary {
    val limit = budgets.sumOf { it.limitCents }
    val spent = budgets.sumOf { it.spentCents }
    val committed = budgets.sumOf { it.committedCents }
    val remaining = (limit - spent - committed).coerceAtLeast(0)
    val selectedMonth = YearMonth.from(LocalDate.parse(period.startsOn))
    val currentMonth = YearMonth.from(today)
    val daysRemaining = when {
        selectedMonth.isAfter(currentMonth) -> selectedMonth.lengthOfMonth()
        selectedMonth.isBefore(currentMonth) -> 1
        else -> selectedMonth.lengthOfMonth() - today.dayOfMonth + 1
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

fun formatPeriodRange(startsOn: String): String = runCatching {
    val start = YearMonth.from(LocalDate.parse(startsOn))
    val end = start.plusMonths(1)
    val startName = start.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH))
    val endName = end.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
    "$startName – $endName"
}.getOrDefault(startsOn)
