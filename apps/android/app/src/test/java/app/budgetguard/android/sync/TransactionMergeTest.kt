package app.budgetguard.android.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionMergeTest {
    @Test
    fun includesOlderPendingNotOnPage() {
        val page = (1..100).map { i ->
            txRow("page$i", "2026-10-%02d".format(30 - i / 4), "posted")
        }
        val allPending = listOf(
            txRow("old1", "2026-08-15", "pending"),
            txRow("old2", "2026-07-20", "pending"),
        )

        val merged = mergeDashboardTransactions(page, allPending)

        assertEquals(102, merged.size)
        val oldIds = merged.filter { it.id.startsWith("old") }.map { it.id }
        assertEquals(listOf("old1", "old2"), oldIds)
    }

    @Test
    fun deduplicatesPendingOnBothLists() {
        val page = listOf(
            txRow("tx1", "2026-10-05", "pending"),
            txRow("tx2", "2026-10-04", "posted"),
            txRow("tx3", "2026-10-03", "posted"),
        )
        val allPending = listOf(
            txRow("tx1", "2026-10-05", "pending"),
            txRow("tx4", "2026-08-15", "pending"),
        )

        val merged = mergeDashboardTransactions(page, allPending)

        assertEquals(4, merged.size)
        val ids = merged.map { it.id }
        assertEquals(listOf("tx1", "tx2", "tx3", "tx4"), ids)
    }

    @Test
    fun sortsByOccurredOnDescending() {
        val page = listOf(
            txRow("tx1", "2026-10-05", "posted"),
            txRow("tx2", "2026-10-03", "posted"),
        )
        val allPending = listOf(
            txRow("tx3", "2026-08-15", "pending"),
            txRow("tx4", "2026-10-04", "pending"),
        )

        val merged = mergeDashboardTransactions(page, allPending)

        val dates = merged.map { it.occurredOn }
        assertEquals(listOf("2026-10-05", "2026-10-04", "2026-10-03", "2026-08-15"), dates)
    }

    private fun txRow(id: String, occurredOn: String, status: String) = TransactionRow(
        id = id,
        accountId = "account",
        categoryId = null,
        occurredOn = occurredOn,
        occurredAt = "${occurredOn}T10:00:00Z",
        amountCents = -10000,
        status = status,
        kind = "card_purchase",
        merchant = "Merchant",
        description = null,
        needsReview = false,
    )
}
