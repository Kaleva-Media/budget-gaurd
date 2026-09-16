package app.budgetguard.android.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AbsaSmsParserTest {
    private val parser = AbsaSmsParser()

    @Test
    fun parsesExternalDebitFromSignedAmount() {
        val result = parser.parse("Absa: CHEQ4242, Transfer, 02/07/26 SETTLEMENT/C - PayShap Ext Debit, Friend, R-1,234.50, Available R67.15. Help 0000000000; REDACTED")!!
        assertEquals(-123_450, result.amountCents)
        assertEquals(TransactionKind.TRANSFER, result.kind)
        assertEquals(TransactionStatus.POSTED, result.status)
        assertEquals(LocalDate.of(2026, 7, 2), result.occurredOn)
        assertTrue(result.needsReview)
    }

    @Test
    fun parsesCardReservationAsPendingDebit() {
        val result = parser.parse("Absa: CHEQ4242, 04/06/26 Example Mobility ZA reserved R178.00 for a purchase. Your available balance: R6,916.40 Help 0000000000; REDACTED")!!
        assertEquals(-17_800, result.amountCents)
        assertEquals(TransactionKind.CARD_PURCHASE, result.kind)
        assertEquals(TransactionStatus.PENDING, result.status)
        assertEquals("Example Mobility ZA", result.merchant)
    }

    @Test
    fun parsesScheduledPayment() {
        val result = parser.parse("Absa: CHEQ4242, Sch t, 01/06/26 SETTLEMENT/C - DC TRACK INTERNAL, LENDER 123456 260601, R-3,500.00, Available R23,000.55. Help 0000000000; REDACTED")!!
        assertEquals(TransactionKind.SCHEDULED_PAYMENT, result.kind)
        assertEquals(-350_000, result.amountCents)
    }

    @Test
    fun returnedDebitIsAReversalNotIncome() {
        val result = parser.parse("Absa: CHEQ4242, Returned, 01/06/26 HEADOFFICE - UNPAID DEBIT, SERVICE PROVIDER, R835.00, Available R346.68. Help 0000000000; REDACTED")!!
        assertEquals(83_500, result.amountCents)
        assertEquals(TransactionKind.REVERSAL, result.kind)
        assertEquals(TransactionStatus.REVERSED, result.status)
    }

    @Test
    fun creditTransferRemainsATransfer() {
        val result = parser.parse("Absa: CHEQ4242, Dep, 23/05/26 SETTLEMENT/C - DIGITAL TRANSF CR, OWN ACCOUNT BANK Transfer, R300.00, Available R529.32. Help 0000000000; REDACTED")!!
        assertEquals(TransactionKind.TRANSFER, result.kind)
        assertEquals(30_000, result.amountCents)
    }

    @Test
    fun sameMessageHasStableFingerprintAndDifferentMessageDoesNot() {
        val first = "Absa: SAVE4242, Transfer, 07/08/26 SETTLEMENT/C - DIGITAL TRANSFER DEBIT, ***1111, R-100.00, Available R1,227.62. Help 0000000000; REDACTED"
        val second = first.replace("R-100.00", "R-101.00")
        assertEquals(parser.parse(first)!!.sourceFingerprint, parser.parse(first)!!.sourceFingerprint)
        assertNotEquals(parser.parse(first)!!.sourceFingerprint, parser.parse(second)!!.sourceFingerprint)
    }

    @Test
    fun rejectsMessagesOutsideKnownContract() {
        assertNull(parser.parse("Your parcel will be delivered today."))
        assertNull(parser.parse("Absa: unfamiliar notification"))
    }
}

