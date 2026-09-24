package app.budgetguard.android.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtModelsTest {
    private val card = Debt(
        id = "card",
        name = "Credit card",
        type = "credit_card",
        balanceCents = 1_000_000,
        annualInterestBps = 2_400,
        minimumPaymentCents = 50_000,
        remainingTermMonths = null,
        dueDay = 20,
        secured = false,
        inArrears = false,
    )
    private val loan = Debt(
        id = "loan",
        name = "Personal loan",
        type = "personal_loan",
        balanceCents = 2_000_000,
        annualInterestBps = 1_200,
        minimumPaymentCents = 80_000,
        remainingTermMonths = 36,
        dueDay = 1,
        secured = false,
        inArrears = false,
    )

    @Test
    fun `avalanche sends extra money to the highest rate first`() {
        val plan = dashboard(
            debts = listOf(loan, card),
            preferences = DebtPreferences(goal = "lowest_cost"),
        ).debtPlan()!!

        assertEquals(DebtStrategy.AVALANCHE, plan.strategy)
        assertEquals(listOf("card", "loan"), plan.payoffOrder.map(Debt::id))
        assertEquals(170_000, plan.extraPaymentCents)
        assertNotNull(plan.projection)
        assertTrue(plan.projection!!.payoffMonths < 24)
    }

    @Test
    fun `balanced preference takes a quick win then switches to avalanche`() {
        val plan = dashboard(
            debts = listOf(loan, card.copy(balanceCents = 200_000)),
            preferences = DebtPreferences(goal = "balanced"),
        ).debtPlan()!!

        assertEquals(DebtStrategy.HYBRID, plan.strategy)
        assertEquals("card", plan.payoffOrder.first().id)
    }

    @Test
    fun `arrears route the user to registered support`() {
        val plan = dashboard(debts = listOf(card.copy(inArrears = true))).debtPlan()!!

        assertEquals(DebtStrategy.FORMAL_SUPPORT, plan.strategy)
        assertNull(plan.projection)
    }

    @Test
    fun `unaffordable minimums route the user to registered support`() {
        val plan = dashboard(
            debts = listOf(card.copy(minimumPaymentCents = 350_000)),
        ).debtPlan()!!

        assertEquals(DebtStrategy.FORMAL_SUPPORT, plan.strategy)
        assertEquals(300_000, plan.availableForDebtCents)
    }

    @Test
    fun `category limits replace smaller planned category amounts without double counting`() {
        val plan = dashboard(
            debts = listOf(card),
            plannedExpenses = listOf(
                PlannedItem("food", "expense", "variable_expense", "Food", 100_000, 0, null, "groceries", null, 2),
            ),
            budgets = listOf(Budget("food-budget", "groceries", 250_000, 0, 0)),
        ).debtPlan()!!

        assertEquals(1_150_000, plan.monthlyLivingPlanCents)
        assertEquals(50_000, plan.availableForDebtCents)
    }

    @Test
    fun `a verified cheaper affordable offer recommends consolidation review`() {
        val plan = dashboard(
            debts = listOf(card, loan),
            preferences = DebtPreferences(
                goal = "lowest_cost",
                consolidationAprBps = 0,
                consolidationTermMonths = 12,
                consolidationFeesCents = 0,
            ),
            monthlyIncomeCents = 1_150_000,
        ).debtPlan()!!

        assertEquals(DebtStrategy.CONSOLIDATION_REVIEW, plan.strategy)
        assertEquals(12, plan.projection?.payoffMonths)
    }

    @Test
    fun `an explicit approach overrides a qualifying consolidation recommendation`() {
        val plan = dashboard(
            debts = listOf(card, loan),
            preferences = DebtPreferences(
                goal = "lowest_cost",
                preferredStrategy = "avalanche",
                consolidationAprBps = 0,
                consolidationTermMonths = 12,
            ),
            monthlyIncomeCents = 1_150_000,
        ).debtPlan()!!

        assertEquals(DebtStrategy.AVALANCHE, plan.strategy)
    }

    @Test
    fun `simulation rejects payments below combined minimums`() {
        assertNull(simulateDebtPayoff(listOf(card, loan), 100_000))
    }

    @Test
    fun `simulation converts annual basis points to monthly interest`() {
        val oneMonth = simulateDebtPayoff(
            listOf(card.copy(annualInterestBps = 1_200, minimumPaymentCents = 1)),
            1_010_000,
        )

        assertEquals(1, oneMonth?.payoffMonths)
        assertEquals(10_000L, oneMonth?.totalInterestCents)
        assertEquals(0L, oneMonth?.trajectory?.last()?.remainingBalanceCents)
        assertEquals("card", oneMonth?.payoffEvents?.single()?.debtId)
    }

    @Test
    fun `plan includes milestones comparisons and a monthly target`() {
        val plan = dashboard(debts = listOf(loan, card), preferences = DebtPreferences(goal = "lowest_cost")).debtPlan()!!

        assertEquals(listOf(25, 50, 75, 100), plan.projection?.milestones?.map(DebtMilestone::percentage))
        assertEquals(listOf(DebtStrategy.AVALANCHE, DebtStrategy.SNOWBALL, DebtStrategy.HYBRID), plan.comparisons.map(DebtStrategyComparison::strategy))
        assertEquals("card", plan.projection?.trajectory?.get(1)?.targetDebtId)
    }

    @Test
    fun `spending suggestions require two months and enough categorised coverage`() {
        val base = dashboard(
            debts = listOf(card),
            budgets = listOf(
                Budget("food-budget", "groceries", 300_000, 0, 0),
                Budget("debt-budget", "debt-category", 100_000, 0, 0),
            ),
        )
        val data = base.copy(debtSpendingHistory = listOf(
            DebtSpendingMonth("2026-07-01", "groceries", "Groceries", null, 200_000),
            DebtSpendingMonth("2026-07-01", null, null, null, 20_000),
            DebtSpendingMonth("2026-08-01", "groceries", "Groceries", null, 300_000),
            DebtSpendingMonth("2026-08-01", null, null, null, 30_000),
            DebtSpendingMonth("2026-08-01", "debt-category", "Debt", "debt", 50_000),
        ))

        val analysis = data.debtSpendingAnalysis()
        assertTrue(analysis.isReady)
        assertEquals(91, analysis.coveragePercentage)
        assertEquals(250_000L, analysis.suggestions.single().medianMonthlySpendCents)
    }

    @Test
    fun `simulation refuses a projection that would overflow integer cents`() {
        assertNull(simulateDebtPayoff(
            listOf(card.copy(
                balanceCents = Long.MAX_VALUE / 2,
                annualInterestBps = 100_000,
                minimumPaymentCents = 1,
            )),
            1,
        ))
    }

    private fun dashboard(
        debts: List<Debt>,
        preferences: DebtPreferences = DebtPreferences(),
        monthlyIncomeCents: Long = 1_200_000,
        plannedExpenses: List<PlannedItem> = emptyList(),
        budgets: List<Budget> = emptyList(),
    ): MobileDashboard {
        val period = BudgetPeriod("period", "2026-09-01", "active", 900_000)
        val baseItems = listOf(
            PlannedItem("salary", "income", "income", "Salary", monthlyIncomeCents, 0, null, null, 25, 1),
            PlannedItem("rent", "expense", "fixed_expense", "Rent", 700_000, 0, null, null, 1, 1),
            PlannedItem("living", "expense", "variable_expense", "Living", 200_000, 0, null, null, null, 2),
        )
        return MobileDashboard(
            month = "September – October 2026",
            profileDisplayName = "Test",
            entity = Entity("personal", "Personal", "personal", true, 0),
            entities = listOf(Entity("personal", "Personal", "personal", true, 0)),
            period = period,
            periods = listOf(period),
            accounts = emptyList(),
            categories = emptyList(),
            budgets = budgets,
            plannedItems = baseItems + plannedExpenses,
            transactions = emptyList(),
            invoiceInbox = null,
            invoices = emptyList(),
            debts = debts,
            debtPreferences = preferences,
        )
    }
}
