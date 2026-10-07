package app.budgetguard.android.sync

/**
 * Merges paginated posted transactions with all open pending outflows for STS.
 *
 * The recent 100 transactions may include some pendings, so we deduplicate by ID
 * and preserve the occurred_on descending sort for UI display.
 *
 * @param recentTransactions The paginated recent transactions (limit 100)
 * @param allPending All open pending transactions (no limit, no cycle filter)
 * @return Merged and deduplicated list, sorted by occurred_on descending
 */
internal fun mergeDashboardTransactions(
    recentTransactions: List<TransactionRow>,
    allPending: List<TransactionRow>,
): List<TransactionRow> {
    val merged = (allPending + recentTransactions).distinctBy { it.id }
    return merged.sortedByDescending { it.occurredOn }
}
