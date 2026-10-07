package app.budgetguard.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D-027: one outflow's |amountCents| is allocated once across planned-item matches.
 */
class MatchAllocationTest {
    @Test
    fun allowsThreeWaySplitWithinLimit() {
        assertNull(checkMatchAllocation(90_000, emptyList(), "itemA", 30_000))
        assertNull(
            checkMatchAllocation(
                90_000,
                listOf("itemA" to 30_000),
                "itemB",
                30_000,
            ),
        )
        assertNull(
            checkMatchAllocation(
                90_000,
                listOf("itemA" to 30_000, "itemB" to 30_000),
                "itemC",
                30_000,
            ),
        )
    }

    @Test
    fun rejectsFourthAllocationExceedingLimit() {
        val error = checkMatchAllocation(
            90_000,
            listOf("itemA" to 30_000, "itemB" to 30_000, "itemC" to 30_000),
            "itemD",
            30_000,
        )
        assertEquals(
            "Cannot allocate R300.00 to this item. " +
                "Transaction amount is R900.00, " +
                "R900.00 already allocated to other items, " +
                "only R0.00 remaining.",
            error,
        )
    }

    @Test
    fun rejectsOverAllocationWithTwoItems() {
        val error = checkMatchAllocation(
            90_000,
            listOf("itemA" to 60_000),
            "itemB",
            40_000,
        )
        assertEquals(
            "Cannot allocate R400.00 to this item. " +
                "Transaction amount is R900.00, " +
                "R600.00 already allocated to other items, " +
                "only R300.00 remaining.",
            error,
        )
    }

    @Test
    fun allowsReallocationToSameItem() {
        assertNull(
            checkMatchAllocation(
                90_000,
                listOf("itemA" to 30_000, "itemB" to 25_000, "itemC" to 25_000),
                "itemA",
                40_000,
            ),
        )
    }

    @Test
    fun handlesNegativeTransactionAmount() {
        assertNull(
            checkMatchAllocation(
                -90_000,
                listOf("itemA" to 30_000, "itemB" to 30_000),
                "itemC",
                30_000,
            ),
        )
        val error = checkMatchAllocation(
            -90_000,
            listOf("itemA" to 30_000, "itemB" to 30_000, "itemC" to 30_000),
            "itemD",
            30_000,
        )
        assertEquals(
            "Cannot allocate R300.00 to this item. " +
                "Transaction amount is R900.00, " +
                "R900.00 already allocated to other items, " +
                "only R0.00 remaining.",
            error,
        )
    }

    @Test
    fun floorsRemainingAtZero() {
        val error = checkMatchAllocation(
            90_000,
            listOf("itemA" to 60_000, "itemB" to 40_000),
            "itemC",
            10_000,
        )
        assertNotNull(error)
        assertEquals(
            "Cannot allocate R100.00 to this item. " +
                "Transaction amount is R900.00, " +
                "R1,000.00 already allocated to other items, " +
                "only R0.00 remaining.",
            error,
        )
        assertFalse(error!!.contains("R-"))
        assertFalse(error.contains("−R"))
        assertFalse(error.contains("only -"))
    }

    @Test
    fun formatsMoneyWithCents() {
        val error = checkMatchAllocation(
            100_050,
            emptyList(),
            "itemA",
            100_051,
        )
        assertEquals(
            "Cannot allocate R1,000.51 to this item. " +
                "Transaction amount is R1,000.50, " +
                "R0.00 already allocated to other items, " +
                "only R1,000.50 remaining.",
            error,
        )
    }
}
