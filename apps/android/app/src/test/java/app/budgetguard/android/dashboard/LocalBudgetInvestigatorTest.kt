package app.budgetguard.android.dashboard

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBudgetInvestigatorTest {
    private val account = Account(
        id = "cheque",
        entityId = "personal",
        institution = "absa",
        externalKey = "CHEQ0001",
        name = "Main account",
        type = "cheque",
        role = "operational",
        purpose = "Everyday spending",
        mask = "0001",
        currentBalanceCents = 100_000,
        creditLimitCents = null,
        includeInSafeToSpend = true,
        displayOrder = 0,
    )

    @Test
    fun explainsNegativeSafeToSpendFromCashAndCommitments() {
        val answer = LocalBudgetInvestigator.answer(
            dashboard(
                plannedItems = listOf(
                    PlannedItem("rent", "expense", "fixed_expense", "Rent", 150_000, 0, "cheque", null, 1, 1),
                ),
                transactions = listOf(
                    Transaction("pending", "cheque", null, "2026-08-30", null, -20_000, "pending", "card_purchase", "Grocer", "Card purchase", true),
                ),
            ),
            "Why am I in the negative?",
        )

        assertTrue(answer.contains("shortfall"))
        assertTrue(answer.contains("pending payments"))
        assertTrue(answer.contains("Grocer"))
    }

    @Test
    fun listsLargestPaymentsWithoutAPlanMatch() {
        val answer = LocalBudgetInvestigator.answer(
            dashboard(
                transactions = listOf(
                    Transaction("one", "cheque", null, "2026-09-02", null, -80_000, "posted", "card_purchase", "Unexpected repair", "", true),
                    Transaction("two", "cheque", null, "2026-09-03", null, -20_000, "posted", "transfer", "Own transfer", "", false),
                ),
            ),
            "Show my largest unplanned payments",
        )

        assertTrue(answer.contains("Unexpected repair"))
        assertTrue(!answer.contains("Own transfer"))
    }

    private fun dashboard(
        plannedItems: List<PlannedItem> = emptyList(),
        transactions: List<Transaction> = emptyList(),
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
            accounts = listOf(account),
            categories = emptyList(),
            budgets = emptyList(),
            plannedItems = plannedItems,
            transactions = transactions,
            invoiceInbox = null,
            invoices = emptyList(),
        )
    }
}
