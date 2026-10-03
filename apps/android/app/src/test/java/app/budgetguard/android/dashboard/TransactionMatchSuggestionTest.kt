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
