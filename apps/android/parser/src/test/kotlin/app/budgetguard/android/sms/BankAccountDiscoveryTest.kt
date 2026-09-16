package app.budgetguard.android.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BankAccountDiscoveryTest {
    private val discovery = BankAccountDiscovery()

    @Test
    fun discoversAnAbsaChequeAccount() {
        val result = discovery.discover(
            "absa",
            "Absa: CHEQ1607, Card purchase, 11/09/26 SETTLEMENT/C - SHOP, R-100.00, Available R467.00. Help 08600",
        )

        assertEquals("CHEQ1607", result?.externalKey)
        assertEquals("cheque", result?.type)
        assertEquals("•••• 1607", result?.mask)
    }

    @Test
    fun discoversAnAbsaSavingsAccount() {
        val result = discovery.discover(
            "absa",
            "Absa: SAVE2959, Dep, 11/09/26 SETTLEMENT/C - TRANSFER, R100.00, Available R500.00. Help 08600",
        )

        assertEquals("SAVE2959", result?.externalKey)
        assertEquals("savings", result?.type)
    }

    @Test
    fun discoversTheFundingAccountInsteadOfTheFnbCard() {
        val result = discovery.discover(
            "fnb",
            "FNB :-) R300.00 reserved for purchase @ Kota Joe Alberton from Current a/c..719394 using card..9342. Avail R467. 11Sep 21:14",
        )

        assertEquals("FNB-719394", result?.externalKey)
        assertEquals("•••• 9394", result?.mask)
    }

    @Test
    fun ignoresFnbElectricityFulfilmentReceipts() {
        assertNull(
            discovery.discover(
                "fnb",
                "FNB :-) CITY OF EKURHULEN. Elec Amt: R100.00. Meter: 45049946291. Credit Token: 3377-4429-5300-5937-1341.",
            ),
        )
    }

    @Test
    fun doesNotCrossMatchTheSelectedBank() {
        assertNull(discovery.discover("absa", "FNB :-) R10 debited from a/c..1234. Avail R50. 11Sep"))
    }
}
