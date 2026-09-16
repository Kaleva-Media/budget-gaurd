package app.budgetguard.android.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FnbSmsParserTest {
    private val parser = FnbSmsParser { LocalDate.of(2026, 9, 15) }

    @Test
    fun parsesCardReservationWithCompactDate() {
        val result = parser.parse("FNB :-) R178.00 reserved for purchase @EXAMPLE MOBILITY from FNB card a/c..914000 using card..4126. Avail R6,916.40. 14Sep 18:57")!!

        assertEquals("fnb", result.institution)
        assertEquals("FNB-914000", result.accountExternalKey)
        assertEquals(-17_800, result.amountCents)
        assertEquals(691_640, result.availableBalanceCents)
        assertEquals(TransactionKind.CARD_PURCHASE, result.kind)
        assertEquals(TransactionStatus.PENDING, result.status)
        assertEquals("EXAMPLE MOBILITY", result.merchant)
        assertEquals(LocalDate.of(2026, 9, 14), result.occurredOn)
    }

    @Test
    fun parsesIncomingCredit() {
        val result = parser.parse("FNB :-) R12,500.00 credited to account..1607. Salary payment. Avail Bal R18,220.55. 15Sep2026 07:40")!!

        assertEquals(1_250_000, result.amountCents)
        assertEquals(TransactionKind.INCOME, result.kind)
        assertEquals(TransactionStatus.POSTED, result.status)
        assertEquals(LocalDate.of(2026, 9, 15), result.occurredOn)
    }

    @Test
    fun parsesPaymentAsReviewableTransfer() {
        val result = parser.parse("FNB :-) R850.00 paid from account..1607 to Example Services. Ref. RENT-09. Available Balance R3,100.00. 15/09/26")!!

        assertEquals(-85_000, result.amountCents)
        assertEquals(TransactionKind.TRANSFER, result.kind)
        assertEquals("RENT-09", result.reference)
        assertTrue(result.needsReview)
    }

    @Test
    fun treatsPaidToAccountAsIncome() {
        val result = parser.parse("FNB :-) R2,400.00 paid to cheq a/c..252895 from Example Client. Avail R4,200.00. 15Sep 09:10")!!

        assertEquals("FNB-252895", result.accountExternalKey)
        assertEquals(240_000, result.amountCents)
        assertEquals(TransactionKind.INCOME, result.kind)
    }

    @Test
    fun acceptsLegacyPrefixAndWholeRandBalance() {
        val result = parser.parse("FNB :- R109.00 reserved for purchase @EXAMPLE STORE from FNB card a/c..914000 using card..4126. Avail R9506. 2Jan 05:05")!!

        assertEquals(950_600, result.availableBalanceCents)
        assertEquals(LocalDate.of(2026, 1, 2), result.occurredOn)
    }

    @Test
    fun parsesProvidedCurrentAccountReservation() {
        val result = parser.parse("FNB :-) R300.00 reserved for purchase @ Kota Joe Alberton from Current a/c..719394 using card..9342. Avail R467. 11Sep 21:14")!!

        assertEquals("FNB-719394", result.accountExternalKey)
        assertEquals(-30_000, result.amountCents)
        assertEquals(46_700, result.availableBalanceCents)
        assertEquals("Kota Joe Alberton", result.merchant)
        assertEquals(TransactionKind.CARD_PURCHASE, result.kind)
        assertEquals(TransactionStatus.PENDING, result.status)
        assertEquals(LocalDate.of(2026, 9, 11), result.occurredOn)
    }

    @Test
    fun ignoresProvidedElectricityTokenReceipts() {
        val receipts = listOf(
            "FNB :-) CITY OF EKURHULEN. Elec Amt: R100.00. Vat Amt: R13.04. Meter: 45049946291. Credit Token: 3377-4429-5300-5937-1341. Units: 25.8kWh.",
            "FNB :-) CITY OF EKURHULEN. Elec Amt: R500.00. Vat Amt: R65.22. Meter: 45049946291. Credit Token: 6395-5389-7296-3387-3760. Units: 129kWh.",
            "FNB :-) CITY OF EKURHULEN. Elec Amt: R150.00. Vat Amt: R19.57. Meter: 45049946291. Credit Token: 6958-2569-1129-3401-2581. Units: 38.7kWh.",
        )

        receipts.forEach { receipt -> assertNull(parser.parse(receipt)) }
    }

    @Test
    fun rejectsNonTransactionalOrIncompleteFnbMessages() {
        assertNull(parser.parse("FNB :-) Use the app to approve your login."))
        assertNull(parser.parse("FNB :-) R100.00 paid from account..1607 to Somebody. 15Sep"))
        assertNull(parser.parse("Your account..1607 has R100.00 available."))
    }
}
