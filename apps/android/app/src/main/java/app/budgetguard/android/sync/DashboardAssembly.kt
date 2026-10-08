package app.budgetguard.android.sync

import app.budgetguard.android.dashboard.Account
import app.budgetguard.android.dashboard.Budget
import app.budgetguard.android.dashboard.BudgetPeriod
import app.budgetguard.android.dashboard.Category
import app.budgetguard.android.dashboard.Debt
import app.budgetguard.android.dashboard.DebtCheckIn
import app.budgetguard.android.dashboard.DebtPreferences
import app.budgetguard.android.dashboard.DebtSpendingMonth
import app.budgetguard.android.dashboard.Entity
import app.budgetguard.android.dashboard.Invoice
import app.budgetguard.android.dashboard.InvoiceInbox
import app.budgetguard.android.dashboard.MobileDashboard
import app.budgetguard.android.dashboard.PlannedItem
import app.budgetguard.android.dashboard.Transaction

internal data class DashboardMatch(
    val transactionId: String,
    val plannedItemId: String,
    val amountCents: Long,
)

internal fun TransactionRow.toDashboardTransaction(
    matches: List<DashboardMatch> = emptyList(),
): Transaction = Transaction(
    id = id,
    accountId = accountId,
    categoryId = categoryId,
    occurredOn = occurredOn,
    occurredAt = occurredAt,
    amountCents = amountCents,
    status = status,
    kind = kind,
    merchant = merchant ?: "Unknown transaction",
    description = description.orEmpty(),
    needsReview = needsReview,
    plannedItemIds = matches.map(DashboardMatch::plannedItemId),
    plannedItemMatchAmounts = matches.associate { match -> match.plannedItemId to match.amountCents },
)

/**
 * Pure dashboard assembly: cycle transactions stay on the cycle page, while
 * deduplicated pending rows feed STS via [MobileDashboard.stsPendings].
 */
internal fun assembleDashboard(
    month: String,
    profileDisplayName: String,
    entity: Entity,
    entities: List<Entity>,
    period: BudgetPeriod,
    periods: List<BudgetPeriod>,
    accounts: List<Account>,
    categories: List<Category>,
    budgets: List<Budget>,
    plannedItems: List<PlannedItem>,
    cyclePage: List<TransactionRow>,
    allPendingRows: List<TransactionRow>,
    allPendingTruncated: Boolean = false,
    matches: List<DashboardMatch> = emptyList(),
    invoiceInbox: InvoiceInbox? = null,
    invoices: List<Invoice> = emptyList(),
    debts: List<Debt> = emptyList(),
    debtPreferences: DebtPreferences = DebtPreferences(),
    debtCheckIns: List<DebtCheckIn> = emptyList(),
    debtSpendingHistory: List<DebtSpendingMonth> = emptyList(),
): MobileDashboard {
    val matchesByTransaction = matches.groupBy(DashboardMatch::transactionId)
    fun TransactionRow.withMatches(): Transaction =
        toDashboardTransaction(matchesByTransaction[id].orEmpty())

    val stsPendings = (cyclePage.filter { it.status == "pending" } + allPendingRows)
        .distinctBy(TransactionRow::id)
        .sortedWith(
            compareByDescending<TransactionRow> { it.occurredOn }
                .thenByDescending { it.id },
        )
        .map { it.withMatches() }

    return MobileDashboard(
        month = month,
        profileDisplayName = profileDisplayName,
        entity = entity,
        entities = entities,
        period = period,
        periods = periods,
        accounts = accounts,
        categories = categories,
        budgets = budgets,
        plannedItems = plannedItems,
        transactions = cyclePage.map { it.withMatches() },
        stsPendings = stsPendings,
        stsPendingsTruncated = allPendingTruncated,
        invoiceInbox = invoiceInbox,
        invoices = invoices,
        debts = debts,
        debtPreferences = debtPreferences,
        debtCheckIns = debtCheckIns,
        debtSpendingHistory = debtSpendingHistory,
    )
}
