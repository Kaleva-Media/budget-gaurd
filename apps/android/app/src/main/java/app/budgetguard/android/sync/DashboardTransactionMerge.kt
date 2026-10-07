package app.budgetguard.android.sync

/**
 * Merges paginated posted transactions with all open pending outflows for STS.
 *
 * The recent 100 transactions may include some pendings, so we deduplicate by ID
 * and preserve the occurred_on descending sort for UI display.
 */
fun mergeDashboardTransactions(
    recentTransactions: List<TransactionRow>,
    allPending: List<TransactionRow>,
): List<TransactionRow> {
    val merged = (allPending + recentTransactions).distinctBy { it.id }
    return merged.sortedByDescending { it.occurredOn }
}
