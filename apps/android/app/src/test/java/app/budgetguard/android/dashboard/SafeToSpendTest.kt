package app.budgetguard.android.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Safe to spend (Home) tests - mirroring domain at 4267d9b
 * 
 * STS = B − C (may be negative, no clamp at 0)
 * B = SMS available for STS accounts
 * C = R + P
 * R = remaining planned outflows (expense+savings+debt; not income); matches include posted+pending
 * P = ALL pending outflows on STS accounts (matched + unmatched); exclude transfer/reversal
 * Match = R→P; C unchanged. Goldens must prove C stable across match.
 */
class SafeToSpendTest {
    private val stsAccount = Account(
        id = "cheque",
        entityId = "personal",
        institution = "fnb",
        externalKey = "FNB-1234",
        name = "FNB Cheque",
        type = "cheque",
        role = "operational",
        purpose = "Daily banking",
        mask = "1234",
        currentBalanceCents = 500_000,
        creditLimitCents = null,
        includeInSafeToSpend = true,
        displayOrder = 1,
    )

    private val nonStsAccount = Account(
        id = "savings",
        entityId = "personal",
        institution = "discovery",
        externalKey = "DISC-5678",
        name = "Discovery Savings",
        type = "savings",
        role = "savings",
        purpose = "Emergency fund",
        mask = "5678",
        currentBalanceCents = 200_000,
        creditLimitCents = null,
        includeInSafeToSpend = false,
        displayOrder = 2,
    )

    private val plannedExpense = PlannedItem(
        id = "rent",
        direction = "expense",
        kind = "fixed_expense",
        name = "Rent",
        plannedCents = 150_000,
        actualCents = 0,
        accountId = "cheque",
        categoryId = "housing",
        dueDay = 1,
        sortOrder = 1,
    )

    private val plannedIncome = PlannedItem(
        id = "salary",
        direction = "income",
        kind = "income",
        name = "Salary",
        plannedCents = 300_000,
        actualCents = 0,
        accountId = "cheque",
        categoryId = null,
        dueDay = 25,
        sortOrder = 0,
    )

    @Test
    fun `STS = B − C with no commitments`() {
        val dashboard = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = emptyList(),
            transactions = emptyList(),
        )

        val result = dashboard.summariseSafeToSpend()

        assertEquals(500_000L, result.bCents)
        assertEquals(0L, result.rCents)
        assertEquals(0L, result.pCents)
        assertEquals(0L, result.cCents)
        assertEquals(500_000L, result.safeToSpendCents)
    }

    @Test
    fun `B sums only STS-enabled accounts`() {
        val dashboard = dashboard(
            accounts = listOf(stsAccount, nonStsAccount),
            plannedItems = emptyList(),
            transactions = emptyList(),
        )

        val result = dashboard.summariseSafeToSpend()

        assertEquals(500_000L, result.bCents)
        assertEquals(500_000L, result.safeToSpendCents)
    }

    @Test
    fun `R includes unmatched planned expense`() {
        val dashboard = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = listOf(plannedExpense),
            transactions = emptyList(),
        )

        val result = dashboard.summariseSafeToSpend()

        assertEquals(150_000L, result.rCents)
        assertEquals(0L, result.pCents)
        assertEquals(150_000L, result.cCents)
        assertEquals(350_000L, result.safeToSpendCents)
    }

    @Test
    fun `planned income does not affect STS`() {
        val dashboard = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = listOf(plannedIncome, plannedExpense),
            transactions = emptyList(),
        )

        val result = dashboard.summariseSafeToSpend()

        assertEquals(150_000L, result.rCents)
        assertEquals(350_000L, result.safeToSpendCents)
    }

    @Test
    fun `P includes unmatched pending outflows on STS accounts`() {
        val pendingTx = Transaction(
            id = "pending1",
            accountId = "cheque",
            categoryId = null,
            occurredOn = "2026-09-17",
            occurredAt = "2026-09-17T10:00:00+02:00",
            amountCents = -50_000,
            status = "pending",
            kind = "card_purchase",
            merchant = "Woolworths",
            description = "Card pending",
            needsReview = false,
            plannedItemIds = emptyList(),
        )

        val dashboard = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = emptyList(),
            transactions = listOf(pendingTx),
        )

        val result = dashboard.summariseSafeToSpend()

        assertEquals(50_000L, result.pCents)
        assertEquals(50_000L, result.cCents)
        assertEquals(450_000L, result.safeToSpendCents)
    }

    @Test
    fun `P excludes pending on non-STS accounts`() {
        val pendingTx = Transaction(
            id = "pending1",
            accountId = "savings",
            categoryId = null,
            occurredOn = "2026-09-17",
            occurredAt = "2026-09-17T10:00:00+02:00",
            amountCents = -50_000,
            status = "pending",
            kind = "card_purchase",
            merchant = "Woolworths",
            description = "Card pending",
            needsReview = false,
            plannedItemIds = emptyList(),
        )

        val dashboard = dashboard(
            accounts = listOf(stsAccount, nonStsAccount),
            plannedItems = emptyList(),
            transactions = listOf(pendingTx),
        )

        val result = dashboard.summariseSafeToSpend()

        assertEquals(0L, result.pCents)
        assertEquals(500_000L, result.safeToSpendCents)
    }

    @Test
    fun `P excludes transfers and reversals`() {
        val transfer = Transaction(
            id = "transfer1",
            accountId = "cheque",
            categoryId = null,
            occurredOn = "2026-09-17",
            occurredAt = "2026-09-17T10:00:00+02:00",
            amountCents = -50_000,
            status = "pending",
            kind = "transfer",
            merchant = "",
            description = "Transfer",
            needsReview = false,
            plannedItemIds = emptyList(),
        )

        val reversal = Transaction(
            id = "reversal1",
            accountId = "cheque",
            categoryId = null,
            occurredOn = "2026-09-17",
            occurredAt = "2026-09-17T10:00:00+02:00",
            amountCents = -50_000,
            status = "pending",
            kind = "reversal",
            merchant = "",
            description = "Reversal",
            needsReview = false,
            plannedItemIds = emptyList(),
        )

        val dashboard = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = emptyList(),
            transactions = listOf(transfer, reversal),
        )

        val result = dashboard.summariseSafeToSpend()

        assertEquals(0L, result.pCents)
        assertEquals(500_000L, result.safeToSpendCents)
    }

    @Test
    fun `posted match reduces R but not P`() {
        val postedTx = Transaction(
            id = "posted1",
            accountId = "cheque",
            categoryId = "housing",
            occurredOn = "2026-09-01",
            occurredAt = "2026-09-01T10:00:00+02:00",
            amountCents = -150_000,
            status = "posted",
            kind = "scheduled_payment",
            merchant = "Property Manager",
            description = "Rent payment",
            needsReview = false,
            plannedItemIds = listOf("rent"),
        )

        val dashboard = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = listOf(plannedExpense),
            transactions = listOf(postedTx),
        )

        val result = dashboard.summariseSafeToSpend()

        assertEquals(0L, result.rCents)
        assertEquals(0L, result.pCents)
        assertEquals(0L, result.cCents)
        assertEquals(500_000L, result.safeToSpendCents)
    }

    @Test
    fun `pending matched to plan moves R to P, C unchanged (Neo dedup)`() {
        val beforeWithoutPending = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = listOf(plannedExpense),
            transactions = emptyList(),
        ).summariseSafeToSpend()

        assertEquals(150_000L, beforeWithoutPending.rCents)
        assertEquals(0L, beforeWithoutPending.pCents)
        assertEquals(150_000L, beforeWithoutPending.cCents)

        val matchedPending = Transaction(
            id = "pending1",
            accountId = "cheque",
            categoryId = "housing",
            occurredOn = "2026-09-17",
            occurredAt = "2026-09-17T10:00:00+02:00",
            amountCents = -150_000,
            status = "pending",
            kind = "scheduled_payment",
            merchant = "Property Manager",
            description = "Rent pending",
            needsReview = false,
            plannedItemIds = listOf("rent"),
        )

        val afterMatchedPending = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = listOf(plannedExpense),
            transactions = listOf(matchedPending),
        ).summariseSafeToSpend()

        assertEquals(0L, afterMatchedPending.rCents)
        assertEquals(150_000L, afterMatchedPending.pCents)
        assertEquals(150_000L, afterMatchedPending.cCents)
        assertEquals(350_000L, afterMatchedPending.safeToSpendCents)

        // C unchanged proof
        assertEquals(beforeWithoutPending.cCents, afterMatchedPending.cCents)
        assertEquals(beforeWithoutPending.safeToSpendCents, afterMatchedPending.safeToSpendCents)
    }

    @Test
    fun `partial match reduces R proportionally`() {
        val partialTx = Transaction(
            id = "posted1",
            accountId = "cheque",
            categoryId = "housing",
            occurredOn = "2026-09-01",
            occurredAt = "2026-09-01T10:00:00+02:00",
            amountCents = -100_000,
            status = "posted",
            kind = "scheduled_payment",
            merchant = "Property Manager",
            description = "Partial rent",
            needsReview = false,
            plannedItemIds = listOf("rent"),
        )

        val dashboard = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = listOf(plannedExpense),
            transactions = listOf(partialTx),
        )

        val result = dashboard.summariseSafeToSpend()

        assertEquals(50_000L, result.rCents)
        assertEquals(0L, result.pCents)
        assertEquals(50_000L, result.cCents)
        assertEquals(450_000L, result.safeToSpendCents)
    }

    @Test
    fun `C = R + P with mixed commitments`() {
        val groceries = PlannedItem(
            id = "groceries",
            direction = "expense",
            kind = "variable_expense",
            name = "Groceries",
            plannedCents = 100_000,
            actualCents = 0,
            accountId = "cheque",
            categoryId = "food",
            dueDay = null,
            sortOrder = 2,
        )

        val pendingTx = Transaction(
            id = "pending1",
            accountId = "cheque",
            categoryId = null,
            occurredOn = "2026-09-17",
            occurredAt = "2026-09-17T10:00:00+02:00",
            amountCents = -30_000,
            status = "pending",
            kind = "card_purchase",
            merchant = "Woolworths",
            description = "Card pending",
            needsReview = false,
            plannedItemIds = emptyList(),
        )

        val dashboard = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = listOf(plannedExpense, groceries),
            transactions = listOf(pendingTx),
        )

        val result = dashboard.summariseSafeToSpend()

        assertEquals(250_000L, result.rCents)
        assertEquals(30_000L, result.pCents)
        assertEquals(280_000L, result.cCents)
        assertEquals(220_000L, result.safeToSpendCents)
    }

    @Test
    fun `STS may be negative (no clamp at 0)`() {
        val highPlanned = PlannedItem(
            id = "rent",
            direction = "expense",
            kind = "fixed_expense",
            name = "Rent",
            plannedCents = 600_000,
            actualCents = 0,
            accountId = "cheque",
            categoryId = "housing",
            dueDay = 1,
            sortOrder = 1,
        )

        val dashboard = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = listOf(highPlanned),
            transactions = emptyList(),
        )

        val result = dashboard.summariseSafeToSpend()

        assertEquals(500_000L, result.bCents)
        assertEquals(600_000L, result.cCents)
        assertEquals(-100_000L, result.safeToSpendCents)
    }

    @Test
    fun `multiple planned items with various match states`() {
        val rent = PlannedItem(
            id = "rent",
            direction = "expense",
            kind = "fixed_expense",
            name = "Rent",
            plannedCents = 150_000,
            actualCents = 150_000,
            accountId = "cheque",
            categoryId = "housing",
            dueDay = 1,
            sortOrder = 1,
        )

        val groceries = PlannedItem(
            id = "groceries",
            direction = "expense",
            kind = "variable_expense",
            name = "Groceries",
            plannedCents = 100_000,
            actualCents = 0,
            accountId = "cheque",
            categoryId = "food",
            dueDay = null,
            sortOrder = 2,
        )

        val insurance = PlannedItem(
            id = "insurance",
            direction = "expense",
            kind = "fixed_expense",
            name = "Insurance",
            plannedCents = 80_000,
            actualCents = 0,
            accountId = "cheque",
            categoryId = "insurance",
            dueDay = 15,
            sortOrder = 3,
        )

        val rentPosted = Transaction(
            id = "rent_tx",
            accountId = "cheque",
            categoryId = "housing",
            occurredOn = "2026-09-01",
            occurredAt = "2026-09-01T10:00:00+02:00",
            amountCents = -150_000,
            status = "posted",
            kind = "scheduled_payment",
            merchant = "Property Manager",
            description = "Rent",
            needsReview = false,
            plannedItemIds = listOf("rent"),
        )

        val insurancePending = Transaction(
            id = "insurance_tx",
            accountId = "cheque",
            categoryId = "insurance",
            occurredOn = "2026-09-15",
            occurredAt = "2026-09-15T10:00:00+02:00",
            amountCents = -80_000,
            status = "pending",
            kind = "scheduled_payment",
            merchant = "Discovery",
            description = "Insurance",
            needsReview = false,
            plannedItemIds = listOf("insurance"),
        )

        val unmatchedPending = Transaction(
            id = "pending1",
            accountId = "cheque",
            categoryId = null,
            occurredOn = "2026-09-17",
            occurredAt = "2026-09-17T10:00:00+02:00",
            amountCents = -25_000,
            status = "pending",
            kind = "card_purchase",
            merchant = "Woolworths",
            description = "Card pending",
            needsReview = false,
            plannedItemIds = emptyList(),
        )

        val dashboard = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = listOf(rent, groceries, insurance),
            transactions = listOf(rentPosted, insurancePending, unmatchedPending),
        )

        val result = dashboard.summariseSafeToSpend()

        assertEquals(100_000L, result.rCents)
        assertEquals(105_000L, result.pCents)
        assertEquals(205_000L, result.cCents)
        assertEquals(295_000L, result.safeToSpendCents)
    }

    @Test
    fun `savings and debt payment kinds count as planned outflows`() {
        val savings = PlannedItem(
            id = "emergency",
            direction = "expense",
            kind = "savings",
            name = "Emergency fund",
            plannedCents = 50_000,
            actualCents = 0,
            accountId = "cheque",
            categoryId = null,
            dueDay = 1,
            sortOrder = 4,
        )

        val debt = PlannedItem(
            id = "card_debt",
            direction = "expense",
            kind = "debt_payment",
            name = "Credit card",
            plannedCents = 30_000,
            actualCents = 0,
            accountId = "cheque",
            categoryId = null,
            dueDay = 10,
            sortOrder = 5,
        )

        val dashboard = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = listOf(savings, debt),
            transactions = emptyList(),
        )

        val result = dashboard.summariseSafeToSpend()

        assertEquals(80_000L, result.rCents)
        assertEquals(80_000L, result.cCents)
        assertEquals(420_000L, result.safeToSpendCents)
    }

    @Test
    fun `over-matched planned item contributes 0 to R`() {
        val overMatchedTx = Transaction(
            id = "posted1",
            accountId = "cheque",
            categoryId = "housing",
            occurredOn = "2026-09-01",
            occurredAt = "2026-09-01T10:00:00+02:00",
            amountCents = -200_000,
            status = "posted",
            kind = "scheduled_payment",
            merchant = "Property Manager",
            description = "Rent overpayment",
            needsReview = false,
            plannedItemIds = listOf("rent"),
        )

        val dashboard = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = listOf(plannedExpense),
            transactions = listOf(overMatchedTx),
        )

        val result = dashboard.summariseSafeToSpend()

        assertEquals(0L, result.rCents)
        assertEquals(500_000L, result.safeToSpendCents)
    }

    @Test
    fun `D-028 regression - older pending outflows must be included in STS calculation`() {
        val oldPending1 = Transaction(
            id = "old_pending_1",
            accountId = "cheque",
            categoryId = null,
            occurredOn = "2026-07-15",
            occurredAt = "2026-07-15T10:00:00+02:00",
            amountCents = -30_000,
            status = "pending",
            kind = "card_purchase",
            merchant = "Old Merchant 1",
            description = "Old pending 1",
            needsReview = false,
            plannedItemIds = emptyList(),
        )

        val oldPending2 = Transaction(
            id = "old_pending_2",
            accountId = "cheque",
            categoryId = null,
            occurredOn = "2026-08-20",
            occurredAt = "2026-08-20T14:30:00+02:00",
            amountCents = -25_000,
            status = "pending",
            kind = "card_purchase",
            merchant = "Old Merchant 2",
            description = "Old pending 2",
            needsReview = false,
            plannedItemIds = emptyList(),
        )

        val recentPending = Transaction(
            id = "recent_pending",
            accountId = "cheque",
            categoryId = null,
            occurredOn = "2026-09-17",
            occurredAt = "2026-09-17T10:00:00+02:00",
            amountCents = -20_000,
            status = "pending",
            kind = "card_purchase",
            merchant = "Recent Merchant",
            description = "Recent pending",
            needsReview = false,
            plannedItemIds = emptyList(),
        )

        val result = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = emptyList(),
            transactions = listOf(oldPending1, oldPending2, recentPending),
        ).summariseSafeToSpend()

        assertEquals(75_000L, result.pCents)
        assertEquals(75_000L, result.cCents)
        assertEquals(425_000L, result.safeToSpendCents)
    }

    @Test
    fun `D-027 regression - split payment equal-allocates once (not full×N)`() {
        val groceries = PlannedItem(
            id = "groceries",
            direction = "expense",
            kind = "variable_expense",
            name = "Groceries",
            plannedCents = 300_000,
            actualCents = 0,
            accountId = "cheque",
            categoryId = "food",
            dueDay = null,
            sortOrder = 1,
        )

        val fuel = PlannedItem(
            id = "fuel",
            direction = "expense",
            kind = "variable_expense",
            name = "Fuel",
            plannedCents = 200_000,
            actualCents = 0,
            accountId = "cheque",
            categoryId = "transport",
            dueDay = null,
            sortOrder = 2,
        )

        val beforeMatch = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = listOf(groceries, fuel),
            transactions = emptyList(),
        ).summariseSafeToSpend()

        assertEquals(500_000L, beforeMatch.rCents)
        assertEquals(0L, beforeMatch.pCents)
        assertEquals(500_000L, beforeMatch.cCents)

        val splitTransaction = Transaction(
            id = "split_tx",
            accountId = "cheque",
            categoryId = "food",
            occurredOn = "2026-09-10",
            occurredAt = "2026-09-10T14:00:00+02:00",
            amountCents = -50_000,
            status = "posted",
            kind = "card_purchase",
            merchant = "Pick n Pay",
            description = "Split R250 each",
            needsReview = false,
            plannedItemIds = listOf("groceries", "fuel"),
        )

        val afterMatch = dashboard(
            accounts = listOf(stsAccount),
            plannedItems = listOf(groceries, fuel),
            transactions = listOf(splitTransaction),
        ).summariseSafeToSpend()

        assertEquals(450_000L, afterMatch.rCents)
        assertEquals(0L, afterMatch.pCents)
        assertEquals(450_000L, afterMatch.cCents)
        assertEquals(50_000L, afterMatch.safeToSpendCents)
    }

    @Test
    fun `Edward golden - R500 split on two lines produces STS 3500 not 4000`() {
        val stsAccountWith4000 = stsAccount.copy(currentBalanceCents = 400_000)

        val expense1 = PlannedItem(
            id = "exp1",
            direction = "expense",
            kind = "variable_expense",
            name = "Expense 1",
            plannedCents = 50_000,
            actualCents = 0,
            accountId = "cheque",
            categoryId = "general",
            dueDay = null,
            sortOrder = 1,
        )

        val expense2 = PlannedItem(
            id = "exp2",
            direction = "expense",
            kind = "variable_expense",
            name = "Expense 2",
            plannedCents = 50_000,
            actualCents = 0,
            accountId = "cheque",
            categoryId = "general",
            dueDay = null,
            sortOrder = 2,
        )

        val splitTx = Transaction(
            id = "split",
            accountId = "cheque",
            categoryId = "general",
            occurredOn = "2026-09-10",
            occurredAt = "2026-09-10T10:00:00+02:00",
            amountCents = -50_000,
            status = "posted",
            kind = "card_purchase",
            merchant = "Merchant",
            description = "Split R500",
            needsReview = false,
            plannedItemIds = listOf("exp1", "exp2"),
        )

        val result = dashboard(
            accounts = listOf(stsAccountWith4000),
            plannedItems = listOf(expense1, expense2),
            transactions = listOf(splitTx),
        ).summariseSafeToSpend()

        assertEquals(400_000L, result.bCents)
        assertEquals(50_000L, result.rCents)
        assertEquals(50_000L, result.cCents)
        assertEquals(350_000L, result.safeToSpendCents)
    }

    private fun dashboard(
        accounts: List<Account> = emptyList(),
        plannedItems: List<PlannedItem> = emptyList(),
        transactions: List<Transaction> = emptyList(),
    ) = MobileDashboard(
        month = "September – October 2026",
        profileDisplayName = "Edward",
        entity = Entity("personal", "Personal", "personal", true, 0),
        entities = listOf(Entity("personal", "Personal", "personal", true, 0)),
        period = BudgetPeriod("period", "2026-09-01", "active", 100_000),
        periods = listOf(BudgetPeriod("period", "2026-09-01", "active", 100_000)),
        accounts = accounts,
        categories = emptyList(),
        budgets = emptyList(),
        plannedItems = plannedItems,
        transactions = transactions,
        invoiceInbox = null,
        invoices = emptyList(),
    )
}
