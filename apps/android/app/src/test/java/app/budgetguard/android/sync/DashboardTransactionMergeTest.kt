package app.budgetguard.android.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardTransactionMergeTest {
    @Test
    fun mergeDeduplicatesById() {
        val recent = listOf(
            makeRow("tx1", "2026-10-05", "pending"),
            makeRow("tx2", "2026-10-04", "posted"),
            makeRow("tx3", "2026-10-03", "posted"),
        )
        val allPending = listOf(
            makeRow("tx1", "2026-10-05", "pending"),
            makeRow("tx4", "2026-08-15", "pending"),
        )

        val merged = mergeDashboardTransactions(recent, allPending)

        assertEquals(4, merged.size)
        val ids = merged.map { it.id }
        assertEquals(listOf("tx1", "tx4", "tx2", "tx3"), ids)
    }

    @Test
    fun mergeSortsByOccurredOnDescending() {
        val recent = listOf(
            makeRow("tx1", "2026-10-05", "posted"),
            makeRow("tx2", "2026-10-03", "posted"),
        )
        val allPending = listOf(
            makeRow("tx3", "2026-08-15", "pending"),
            makeRow("tx4", "2026-10-04", "pending"),
        )

        val merged = mergeDashboardTransactions(recent, allPending)

        val dates = merged.map { it.occurredOn }
        assertEquals(listOf("2026-10-05", "2026-10-04", "2026-10-03", "2026-08-15"), dates)
    }

    @Test
    fun olderPendingIncludedEvenIfBeyondPage100() {
        val recent = (1..100).map { i ->
            makeRow("recent$i", "2026-10-%02d".format(30 - i / 4), "posted")
        }
        val allPending = listOf(
            makeRow("old1", "2026-08-15", "pending"),
            makeRow("old2", "2026-07-20", "pending"),
        )

        val merged = mergeDashboardTransactions(recent, allPending)

        assertEquals(102, merged.size)
        val oldPendingIds = merged.filter { it.id.startsWith("old") }.map { it.id }
        assertEquals(listOf("old1", "old2"), oldPendingIds)
    }

    private fun makeRow(id: String, occurredOn: String, status: String) = TransactionRow(
        id = id,
        entityId = "entity",
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
