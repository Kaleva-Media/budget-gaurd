package app.budgetguard.android.sync

import java.text.NumberFormat
import java.util.Locale
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
        return "Cannot allocate ${formatZarCents(newAmountCents)} to this item. " +
               "Transaction amount is ${formatZarCents(maxAvailable)}, " +
               "${formatZarCents(existingTotal)} already allocated to other items, " +
               "only ${formatZarCents(remaining)} remaining."
    }
    
    return null
}

private fun formatZarCents(cents: Long): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-ZA"))
    return formatter.format(abs(cents) / 100.0)
}
