package app.budgetguard.android.sync

import app.budgetguard.android.dashboard.Account
import app.budgetguard.android.dashboard.BudgetPeriod
import app.budgetguard.android.dashboard.Entity
import app.budgetguard.android.dashboard.LocalBudgetInvestigator
import app.budgetguard.android.dashboard.PlannedItem
import app.budgetguard.android.dashboard.exactTransactionMatchSuggestions
import app.budgetguard.android.dashboard.summariseSafeToSpend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardAssemblyTest {
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

    private val rent = PlannedItem(
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

    @Test
    fun fetchAllPagesWalksThreePagesOf1250PendingsIntoPWithoutDuplicates() = runBlocking {
        val pendingRows = (1..1_250).map { index ->
            pendingRow(
                id = "p%04d".format(index),
                occurredOn = "2026-09-15",
                amountCents = -100,
            )
        }
        val ordered = pendingRows.sortedWith(pendingOrder)
        val fetched = fetchAllPages(pageSize = 500, maxPages = 20) { from, to ->
            slicePage(ordered, from, to)
        }

        assertFalse(fetched.truncated)
        assertEquals(1_250, fetched.rows.size)
        assertEquals(ordered.map { it.id }, fetched.rows.map { it.id })
        assertEquals(1_250, fetched.rows.map { it.id }.distinct().size)

        val dashboard = dashboardFrom(
            cyclePage = emptyList(),
            allPendingRows = fetched.rows,
            accounts = listOf(stsAccount),
        )
        val sts = dashboard.summariseSafeToSpend()

        assertEquals(1_250, dashboard.stsPendings.size)
        assertEquals(0, dashboard.transactions.size)
        assertEquals(fetched.rows.map { it.id }, dashboard.stsPendings.map { it.id })
        assertEquals(125_000L, sts.pCents)
        assertEquals(375_000L, sts.safeToSpendCents)
    }

    @Test
    fun fetchAllPagesMarksTruncatedWhenPageCapStopsAFullLastPage() = runBlocking {
        val pendingRows = (1..1_250).map { index ->
            pendingRow(id = "p%04d".format(index), occurredOn = "2026-09-15", amountCents = -100)
        }
        val ordered = pendingRows.sortedWith(pendingOrder)
        val fetched = fetchAllPages(pageSize = 500, maxPages = 2) { from, to ->
            slicePage(ordered, from, to)
        }

        assertTrue(fetched.truncated)
        assertEquals(1_000, fetched.rows.size)

        val dashboard = dashboardFrom(
            cyclePage = emptyList(),
            allPendingRows = fetched.rows,
            allPendingTruncated = fetched.truncated,
            accounts = listOf(stsAccount),
        )
        assertTrue(dashboard.stsPendingsTruncated)
        assertEquals(1_000, dashboard.stsPendings.size)
        assertEquals(100_000L, dashboard.summariseSafeToSpend().pCents)
    }

    @Test
    fun truncatedAllPendingStillCountsCyclePagePendingOnce() {
        val cyclePending = pendingRow(
            id = "cycle-pending",
            occurredOn = "2026-09-20",
            amountCents = -25_000,
        )
        val olderFetched = pendingRow(
            id = "fetched-pending",
            occurredOn = "2026-08-01",
            amountCents = -10_000,
        )
        val dashboard = dashboardFrom(
            cyclePage = listOf(cyclePending),
            allPendingRows = listOf(olderFetched),
            allPendingTruncated = true,
            accounts = listOf(stsAccount),
        )

        assertTrue(dashboard.stsPendingsTruncated)
        assertEquals(listOf("cycle-pending"), dashboard.transactions.map { it.id })
        assertEquals(1, dashboard.stsPendings.count { it.id == "cycle-pending" })
        assertEquals(setOf("cycle-pending", "fetched-pending"), dashboard.stsPendings.map { it.id }.toSet())
        assertEquals(35_000L, dashboard.summariseSafeToSpend().pCents)
    }

    @Test
    fun olderPendingCountsInPButNotCycleSurfaces() {
        val cyclePosted = pendingRow(
            id = "cycle-posted",
            occurredOn = "2026-09-20",
            amountCents = -8_000,
            status = "posted",
            categoryId = "housing",
            needsReview = false,
        )
        val oldPending = pendingRow(
            id = "old-pending",
            occurredOn = "2026-08-15",
            amountCents = -50_000,
            needsReview = true,
        )
        val dashboard = dashboardFrom(
            cyclePage = listOf(cyclePosted),
            allPendingRows = listOf(oldPending),
            accounts = listOf(stsAccount),
            plannedItems = listOf(rent.copy(plannedCents = 50_000)),
        )
        val sts = dashboard.summariseSafeToSpend()

        assertEquals(listOf("cycle-posted"), dashboard.transactions.map { it.id })
        assertEquals(listOf("old-pending"), dashboard.stsPendings.map { it.id })
        assertEquals(50_000L, sts.pCents)
        assertEquals(50_000L, sts.rCents)
        assertEquals(0, dashboard.transactionsNeedingReview)
        assertTrue(dashboard.exactTransactionMatchSuggestions().isEmpty())

        val investigation = LocalBudgetInvestigator.answer(dashboard, "Why am I in the negative?")
        assertFalse(investigation.contains("Only the latest 100 transactions are loaded"))
        assertFalse(investigation.contains("old-pending"))
    }

    @Test
    fun pendingOnCyclePageAndAllPendingIsCountedOnceInP() {
        val shared = pendingRow(id = "shared-pending", occurredOn = "2026-09-18", amountCents = -40_000)
        val dashboard = dashboardFrom(
            cyclePage = listOf(shared),
            allPendingRows = listOf(shared),
            accounts = listOf(stsAccount),
        )

        assertEquals(1, dashboard.transactions.count { it.id == "shared-pending" })
        assertEquals(1, dashboard.stsPendings.count { it.id == "shared-pending" })
        assertEquals(40_000L, dashboard.summariseSafeToSpend().pCents)
    }

    @Test
    fun ac4ExclusionsAreEachOmittedFromP() {
        val valid = pendingRow(id = "valid", occurredOn = "2026-09-18", amountCents = -10_000)
        val inflow = pendingRow(id = "inflow", occurredOn = "2026-09-18", amountCents = 99_000)
        val reversed = pendingRow(id = "reversed", occurredOn = "2026-09-18", amountCents = -99_000, status = "reversed")
        val failed = pendingRow(id = "failed", occurredOn = "2026-09-18", amountCents = -99_000, status = "failed")
        val transfer = pendingRow(id = "transfer", occurredOn = "2026-09-18", amountCents = -99_000, kind = "transfer")
        val reversal = pendingRow(id = "reversal", occurredOn = "2026-09-18", amountCents = -99_000, kind = "reversal")
        val nonSts = pendingRow(id = "non-sts", occurredOn = "2026-09-18", amountCents = -99_000, accountId = "savings")
        val otherEntity = pendingRow(
            id = "other-entity",
            occurredOn = "2026-09-18",
            amountCents = -99_000,
            accountId = "other-entity-account",
        )

        val dashboard = dashboardFrom(
            cyclePage = emptyList(),
            allPendingRows = listOf(valid, inflow, reversed, failed, transfer, reversal, nonSts, otherEntity),
            accounts = listOf(stsAccount, nonStsAccount),
        )
        val sts = dashboard.summariseSafeToSpend()

        assertEquals(setOf("valid"), dashboard.stsPendings.filter { row ->
            row.status == "pending" &&
                row.amountCents < 0 &&
                row.kind != "transfer" &&
                row.kind != "reversal" &&
                row.accountId == stsAccount.id
        }.map { it.id }.toSet())
        assertEquals(10_000L, sts.pCents)
        assertEquals(490_000L, sts.safeToSpendCents)
    }

    @Test
    fun matchedPendingMovesRtoPWithCUnchanged() {
        val unmatched = dashboardFrom(
            cyclePage = emptyList(),
            allPendingRows = emptyList(),
            accounts = listOf(stsAccount),
            plannedItems = listOf(rent),
        ).summariseSafeToSpend()
        assertEquals(150_000L, unmatched.rCents)
        assertEquals(0L, unmatched.pCents)
        assertEquals(150_000L, unmatched.cCents)

        val matchedPending = pendingRow(
            id = "rent-pending",
            occurredOn = "2026-09-17",
            amountCents = -150_000,
            kind = "scheduled_payment",
        )
        val matched = dashboardFrom(
            cyclePage = listOf(matchedPending),
            allPendingRows = listOf(matchedPending),
            accounts = listOf(stsAccount),
            plannedItems = listOf(rent.copy(actualCents = 150_000)),
            matches = listOf(DashboardMatch("rent-pending", "rent", 150_000)),
        )
        val after = matched.summariseSafeToSpend()

        assertEquals(0L, after.rCents)
        assertEquals(150_000L, after.pCents)
        assertEquals(150_000L, after.cCents)
        assertEquals(unmatched.cCents, after.cCents)
        assertEquals(unmatched.safeToSpendCents, after.safeToSpendCents)
        assertEquals(listOf("rent"), matched.stsPendings.single().plannedItemIds)
        assertEquals(listOf("rent-pending"), matched.transactions.map { it.id })
    }

    @Test
    fun manyPendingsDoNotTriggerFalseLatest100Warning() {
        val cyclePage = listOf(
            pendingRow(
                id = "cycle-1",
                occurredOn = "2026-09-20",
                amountCents = -1_000,
                status = "posted",
                categoryId = "housing",
            ),
        )
        val oldPendings = (1..120).map { index ->
            pendingRow(
                id = "old-%03d".format(index),
                occurredOn = "2026-08-01",
                amountCents = -1_000,
                needsReview = true,
            )
        }
        val dashboard = dashboardFrom(
            cyclePage = cyclePage,
            allPendingRows = oldPendings,
            accounts = listOf(stsAccount),
        )
        val investigation = LocalBudgetInvestigator.answer(dashboard, "Why am I in the negative?")
        assertEquals(1, dashboard.transactions.size)
        assertEquals(120, dashboard.stsPendings.size)
        assertFalse(investigation.contains("Only the latest 100 transactions are loaded"))
        assertEquals(0, dashboard.transactionsNeedingReview)
        assertEquals(120_000L, dashboard.summariseSafeToSpend().pCents)
    }

    private fun dashboardFrom(
        cyclePage: List<TransactionRow>,
        allPendingRows: List<TransactionRow>,
        accounts: List<Account>,
        plannedItems: List<PlannedItem> = emptyList(),
        matches: List<DashboardMatch> = emptyList(),
        allPendingTruncated: Boolean = false,
    ) = assembleDashboard(
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
        cyclePage = cyclePage,
        allPendingRows = allPendingRows,
        allPendingTruncated = allPendingTruncated,
        matches = matches,
    )

    private fun pendingRow(
        id: String,
        occurredOn: String,
        amountCents: Long,
        status: String = "pending",
        kind: String = "card_purchase",
        accountId: String = "cheque",
        categoryId: String? = null,
        needsReview: Boolean = false,
    ) = TransactionRow(
        id = id,
        accountId = accountId,
        categoryId = categoryId,
        occurredOn = occurredOn,
        occurredAt = "${occurredOn}T10:00:00Z",
        amountCents = amountCents,
        status = status,
        kind = kind,
        merchant = id,
        description = null,
        needsReview = needsReview,
    )

    private fun slicePage(rows: List<TransactionRow>, from: Long, to: Long): List<TransactionRow> {
        val start = from.toInt().coerceAtLeast(0)
        if (start >= rows.size) return emptyList()
        val endExclusive = (to.toInt() + 1).coerceAtMost(rows.size)
        return rows.subList(start, endExclusive)
    }

    companion object {
        private val pendingOrder = compareByDescending<TransactionRow> { it.occurredOn }
            .thenByDescending { it.id }
    }
}
