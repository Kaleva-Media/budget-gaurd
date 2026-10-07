package app.budgetguard.android.sync

import app.budgetguard.android.dashboard.formatZar
import kotlin.math.abs

/**
 * Checks if a new match allocation would exceed the transaction amount.
 *
 * Returns null if the allocation is allowed, or an error message if it would exceed.
 * Enforces D-027: allocations for one transaction must sum to at most |transaction amount|.
 *
 * @param transactionAmountCents The transaction amount (may be negative)
 * @param existing List of (plannedItemId, amountCents) for existing allocations
 * @param plannedItemId The planned item being allocated to
 * @param newAmountCents The new allocation amount
 * @return null if allowed, error message if rejected
 */
internal fun checkMatchAllocation(
    transactionAmountCents: Long,
    existing: List<Pair<String, Long>>,
    plannedItemId: String,
    newAmountCents: Long,
): String? {
    val maxAvailable = abs(transactionAmountCents)
    val existingTotal = existing
        .filter { it.first != plannedItemId }
        .sumOf { it.second }
    val newTotal = existingTotal + newAmountCents

    if (newTotal > maxAvailable) {
        val remaining = (maxAvailable - existingTotal).coerceAtLeast(0)
        return "Cannot allocate ${formatZar(newAmountCents)} to this item. " +
               "Transaction amount is ${formatZar(maxAvailable)}, " +
               "${formatZar(existingTotal)} already allocated to other items, " +
               "only ${formatZar(remaining)} remaining."
    }

    return null
}
