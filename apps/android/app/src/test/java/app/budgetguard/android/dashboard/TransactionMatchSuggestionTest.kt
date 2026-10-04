package app.budgetguard.android.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionMatchSuggestionTest {
    @Test
    fun `suggests one exact expense match for confirmation`() {
        val expense = plannedExpense("rent", 2_200_000, accountId = "cheque")
        val payment = transaction("payment", -2_200_000, accountId = "cheque")

        val suggestions = dashboard(listOf(expense), listOf(payment)).exactTransactionMatchSuggestions()

        assertEquals(1, suggestions.size)
        assertEquals("rent", suggestions.single().plannedItem.id)
        assertEquals("payment", suggestions.single().transaction.id)
        assertEquals(2_200_000, suggestions.single().amountCents)
    }

    @Test
    fun `matches the exact remaining amount after a partial payment`() {
        val expense = plannedExpense("school", 2_200_000, actualCents = 500_000)
        val payment = transaction("balance", -1_700_000)

        val suggestions = dashboard(listOf(expense), listOf(payment)).exactTransactionMatchSuggestions()

        assertEquals("school", suggestions.single().plannedItem.id)
        assertEquals(1_700_000, suggestions.single().amountCents)
    }

    @Test
    fun `does not suggest an assigned account mismatch`() {
        val expense = plannedExpense("rent", 2_200_000, accountId = "cheque")
        val payment = transaction("payment", -2_200_000, accountId = "credit")

        assertTrue(dashboard(listOf(expense), listOf(payment)).exactTransactionMatchSuggestions().isEmpty())
    }

    @Test
    fun `does not guess when an exact amount is ambiguous`() {
        val items = listOf(
            plannedExpense("rent", 2_200_000),
            plannedExpense("school", 2_200_000),
        )
        val payment = transaction("payment", -2_200_000)

        assertTrue(dashboard(items, listOf(payment)).exactTransactionMatchSuggestions().isEmpty())
    }

    @Test
    fun `does not suggest an already matched transaction`() {
        val expense = plannedExpense("rent", 2_200_000)
        val payment = transaction("payment", -2_200_000, plannedItemIds = listOf("rent"))

        assertTrue(dashboard(listOf(expense), listOf(payment)).exactTransactionMatchSuggestions().isEmpty())
    }

    @Test
    fun `matched transaction no longer needs quick sort review`() {
        val expense = plannedExpense("rent", 2_200_000, actualCents = 2_200_000)
        val payment = transaction("payment", -2_200_000, plannedItemIds = listOf("rent"))

        assertEquals(0, dashboard(listOf(expense), listOf(payment)).transactionsNeedingReview)
    }

    @Test
    fun `manual matching offers unfinished items even when amount and account differ`() {
        val expense = plannedExpense("rent", 2_200_000, accountId = "cheque")
        val payment = transaction("payment", -2_150_000, accountId = "credit")

        val candidates = dashboard(listOf(expense), listOf(payment)).manualMatchCandidates(payment)

        assertEquals(listOf("rent"), candidates.map(PlannedItem::id))
    }

    @Test
    fun `manual matching excludes settled wrong-direction and existing items`() {
        val settled = plannedExpense("settled", 50_000, actualCents = 50_000)
        val alreadyLinked = plannedExpense("linked", 60_000)
        val income = PlannedItem("salary", "income", "income", "Salary", 70_000, 0, null, null, 1, 0)
        val payment = transaction(
            "payment",
            -60_000,
            plannedItemIds = listOf("linked"),
            plannedItemMatchAmounts = mapOf("linked" to 20_000),
        )

        assertTrue(
            dashboard(listOf(settled, alreadyLinked, income), listOf(payment))
                .manualMatchCandidates(payment)
                .isEmpty(),
        )
    }

    @Test
    fun `manual matching exposes only the unallocated transaction amount`() {
        val payment = transaction(
            "payment",
            -100_000,
            plannedItemIds = listOf("first"),
            plannedItemMatchAmounts = mapOf("first" to 35_000),
        )

        assertEquals(65_000, payment.unallocatedMatchCents())
    }

    private fun plannedExpense(
        id: String,
        plannedCents: Long,
        actualCents: Long = 0,
        accountId: String? = null,
    ) = PlannedItem(
        id = id,
        direction = "expense",
        kind = "fixed_expense",
        name = id.replaceFirstChar(Char::uppercase),
        plannedCents = plannedCents,
        actualCents = actualCents,
        accountId = accountId,
        categoryId = null,
        dueDay = 1,
        sortOrder = 0,
    )

    private fun transaction(
        id: String,
        amountCents: Long,
        accountId: String = "cheque",
        plannedItemIds: List<String> = emptyList(),
        plannedItemMatchAmounts: Map<String, Long> = emptyMap(),
    ) = Transaction(
        id = id,
        accountId = accountId,
        categoryId = null,
        occurredOn = "2026-09-01",
        occurredAt = null,
        amountCents = amountCents,
        status = "posted",
        kind = "bank_payment",
        merchant = "Example merchant",
        description = "Payment",
        needsReview = true,
        plannedItemIds = plannedItemIds,
        plannedItemMatchAmounts = plannedItemMatchAmounts,
    )

    private fun dashboard(
        plannedItems: List<PlannedItem>,
        transactions: List<Transaction>,
    ): MobileDashboard {
        val entity = Entity("personal", "Personal", "personal", true, 0, 28)
        val period = BudgetPeriod("period", "2026-09-01", "active", 0)
        return MobileDashboard(
            month = formatPeriodRange(period.startsOn, entity.budgetCycleDay),
            profileDisplayName = "Edward",
            entity = entity,
            entities = listOf(entity),
            period = period,
            periods = listOf(period),
            accounts = emptyList(),
            categories = emptyList(),
            budgets = emptyList(),
            plannedItems = plannedItems,
            transactions = transactions,
            invoiceInbox = null,
            invoices = emptyList(),
        )
    }
}
