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
        val futurePeriod = BudgetPeriod("future", "2026-10-01", "draft", 397_100)
        val futureDashboard = dashboard(
            selectedPeriod = futurePeriod,
            plannedItems = listOf(
                PlannedItem("salary", "income", "income", "Salary", 12_453_255, 0, null, null, 25, 1),
                PlannedItem("rent", "expense", "fixed_expense", "Rent", 11_177_188, 0, null, null, 1, 2),
            ),
        )

        val summary = futureDashboard.homeHeroSummary(LocalDate.of(2026, 9, 21))

        assertEquals(HomeHeroMode.FUTURE_DAILY_PLAN, summary.mode)
        assertEquals(31, summary.dayCount)
        assertEquals(1_673_167, summary.planPositionCents)
        assertEquals(53_973, summary.amountCents)
    }

    @Test
    fun usesAccountBackedSafeToSpendOnlyForTheCurrentPeriod() {
        val currentDashboard = dashboard(
            plannedItems = listOf(
                PlannedItem("rent", "expense", "fixed_expense", "Rent", 600_000, 0, null, null, 1, 1),
            ),
        )

        val summary = currentDashboard.homeHeroSummary(LocalDate.of(2026, 9, 21))

        assertEquals(HomeHeroMode.CURRENT_SAFE_TO_SPEND, summary.mode)
        assertEquals(-600_000, summary.amountCents)
        assertEquals(null, summary.dayCount)
    }

    @Test
    fun preservesAFuturePlanShortfallWhenConvertingItToADailyAmount() {
        val futurePeriod = BudgetPeriod("future", "2026-10-01", "draft", 0)
        val futureDashboard = dashboard(
            selectedPeriod = futurePeriod,
            plannedItems = listOf(
                PlannedItem("expense", "expense", "fixed_expense", "Expense", 100_000, 0, null, null, 1, 1),
            ),
        )

        val summary = futureDashboard.homeHeroSummary(LocalDate.of(2026, 9, 21))

        assertEquals(HomeHeroMode.FUTURE_DAILY_PLAN, summary.mode)
        assertEquals(-100_000, summary.planPositionCents)
        assertEquals(-3_226, summary.amountCents)
    }

    @Test
    fun pastPeriodsShowTheirPlanResultInsteadOfCurrentAccountBalances() {
        val pastPeriod = BudgetPeriod("past", "2026-08-01", "closed", 250_000)
        val pastDashboard = dashboard(
            selectedPeriod = pastPeriod,
            plannedItems = listOf(
                PlannedItem("income", "income", "income", "Income", 1_000_000, 0, null, null, 25, 1),
                PlannedItem("expenses", "expense", "fixed_expense", "Expenses", 900_000, 0, null, null, 1, 2),
            ),
        )

        val summary = pastDashboard.homeHeroSummary(LocalDate.of(2026, 9, 21))

        assertEquals(HomeHeroMode.PAST_PLAN_RESULT, summary.mode)
        assertEquals(350_000, summary.amountCents)
        assertEquals(null, summary.dayCount)
    }

    @Test
    fun separatesPersonalAndBusinessExpenseCategories() {
        assertEquals("personal", categoryScopeForEntityKind("personal"))
        assertEquals("business", categoryScopeForEntityKind("company"))
        assertEquals("business", categoryScopeForEntityKind("other"))
    }

    @Test
    fun offersOnlyUnassignedCategoriesWhenSettingFlexibleBudgets() {
        val categories = listOf(
            Category("food", "Groceries", "#000000", "basket"),
            Category("fuel", "Fuel", "#000000", "car"),
            Category("fun", "Entertainment", "#000000", "ticket"),
        )
        val budgets = listOf(
            Budget("food-budget", "food", 300_000, 100_000, 0),
            Budget("fuel-budget", "fuel", 150_000, 50_000, 0),
        )

        assertEquals(listOf("fun"), availableBudgetCategories(categories, budgets).map { it.id })
        assertEquals(
            listOf("food", "fun"),
            availableBudgetCategories(categories, budgets, editingBudgetId = "food-budget").map { it.id },
        )
    }

    @Test
    fun flexibleBudgetShowsMoneyLeftOrOverspent() {
        val withinLimit = Budget("food", "food", 300_000, 100_000, 50_000)
        val overspent = Budget("fuel", "fuel", 100_000, 120_000, 10_000)

        assertEquals(150_000, withinLimit.remainingCents)
        assertEquals(-30_000, overspent.remainingCents)
    }

    @Test
    fun searchesPlansAndGroupsPaidExpensesSeparately() {
        val items = listOf(
            PlannedItem("salary", "income", "income", "Salary", 500_000, 0, null, null, 25, 1),
            PlannedItem("food", "expense", "variable_expense", "Food", 100_000, 40_000, null, null, null, 2),
            PlannedItem("fuel", "expense", "variable_expense", "Fuel", 150_000, 0, null, null, null, 3, manuallyPaid = true),
            PlannedItem("phone", "expense", "fixed_expense", "Phone", 180_000, 0, null, null, 20, 4),
        )

        val filtered = groupPlannedItems(items, "f", ExpenseOrder.AMOUNT)
        val alphabetical = groupPlannedItems(items, "", ExpenseOrder.NAME)
        val byAmount = groupPlannedItems(items, "", ExpenseOrder.AMOUNT)

        assertEquals(emptyList<PlannedItem>(), filtered.income)
        assertEquals(listOf("Food"), filtered.unpaidExpenses.map { it.name })
        assertEquals(listOf("Fuel"), filtered.paidExpenses.map { it.name })
        assertEquals(listOf("Food", "Phone"), alphabetical.unpaidExpenses.map { it.name })
        assertEquals(listOf("Phone", "Food"), byAmount.unpaidExpenses.map { it.name })
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
