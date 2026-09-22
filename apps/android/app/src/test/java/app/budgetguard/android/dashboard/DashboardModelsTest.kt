package app.budgetguard.android.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DashboardModelsTest {
    private val period = BudgetPeriod("period", "2026-09-01", "active", 100_000)

    @Test
    fun calculatesPresentTenseBudgetPosition() {
        val dashboard = dashboard(
            budgets = listOf(Budget("budget", "food", 300_000, 90_000, 30_000)),
        )

        val summary = dashboard.budgetSummary(LocalDate.of(2026, 9, 21))

        assertEquals(180_000, summary.remainingCents)
        assertEquals(18_000, summary.safeToSpendTodayCents)
        assertEquals(10, summary.daysRemaining)
    }

    @Test
    fun calculatesProjectedSurplusFromPlanAndCarryover() {
        val dashboard = dashboard(
            plannedItems = listOf(
                PlannedItem("salary", "income", "income", "Salary", 2_000_000, 0, null, null, 25, 1),
                PlannedItem("rent", "expense", "fixed_expense", "Rent", 800_000, 0, null, null, 1, 1),
                PlannedItem("save", "expense", "savings", "Savings", 300_000, 0, null, null, 2, 2),
            ),
        )

        val summary = dashboard.cashflowSummary()

        assertEquals(1_000_000, summary.projectedSurplusCents)
        assertEquals(52, summary.allocationPercentage)
    }

    @Test
    fun flagsPlaceholderCardMappings() {
        val account = Account(
            id = "card",
            entityId = "personal",
            institution = "absa",
            externalKey = "credit-card-1",
            name = "Credit card 1",
            type = "credit_card",
            role = "operational",
            purpose = "Groceries",
            mask = "Add mask",
            currentBalanceCents = 0,
            creditLimitCents = null,
            includeInSafeToSpend = true,
            displayOrder = 1,
        )

        assertEquals(true, account.mappingNeedsAttention)
    }

    @Test
    fun formatsBudgetPeriodAsAReadableRange() {
        assertEquals("September – October 2026", formatPeriodRange("2026-09-01"))
        assertEquals("December – January 2027", formatPeriodRange("2026-12-01"))
    }

    @Test
    fun givesFuturePeriodsAFullMonthOfDailyPlanning() {
        val futurePeriod = BudgetPeriod("future", "2026-10-01", "draft", 0)
        val futureDashboard = dashboard(
            selectedPeriod = futurePeriod,
            budgets = listOf(Budget("budget", "food", 310_000, 0, 0)),
        )

        val summary = futureDashboard.budgetSummary(LocalDate.of(2026, 9, 21))

        assertEquals(31, summary.daysRemaining)
        assertEquals(10_000, summary.safeToSpendTodayCents)
    }

    @Test
    fun separatesPersonalAndBusinessExpenseCategories() {
        assertEquals("personal", categoryScopeForEntityKind("personal"))
        assertEquals("business", categoryScopeForEntityKind("company"))
        assertEquals("business", categoryScopeForEntityKind("other"))
    }

    private fun dashboard(
        selectedPeriod: BudgetPeriod = period,
        budgets: List<Budget> = emptyList(),
        plannedItems: List<PlannedItem> = emptyList(),
    ) = MobileDashboard(
        month = formatPeriodRange(selectedPeriod.startsOn),
        profileDisplayName = "Edward",
        entity = Entity("personal", "Personal", "personal", true, 0),
        entities = listOf(Entity("personal", "Personal", "personal", true, 0)),
        period = selectedPeriod,
        periods = listOf(selectedPeriod),
        accounts = emptyList(),
        categories = emptyList(),
        budgets = budgets,
        plannedItems = plannedItems,
        transactions = emptyList(),
        invoiceInbox = null,
        invoices = emptyList(),
    )
}
