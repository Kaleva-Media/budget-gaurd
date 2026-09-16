package app.budgetguard.android.sms

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AbsaSmsParser {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")
    private val settlementPattern = Regex(
        pattern = """^Absa:\s*(?<account>[^,]+),\s*(?<event>[^,]+),\s*(?<date>\d{2}/\d{2}/\d{2})\s+(?:SETTLEMENT/C|HEADOFFICE)\s*-\s*(?<payload>.+),\s*R(?<amount>-?[\d,]+\.\d{2}),\s*Available\s+R(?<balance>[\d,]+\.\d{2})\.\s*Help\b.*$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val reservationPattern = Regex(
        pattern = """^Absa:\s*(?<account>[^,]+),\s*(?<date>\d{2}/\d{2}/\d{2})\s+(?<merchant>.+?)\s+reserved\s+R(?<amount>[\d,]+\.\d{2})\s+for\s+a\s+purchase\.\s+Your\s+available\s+balance:\s*R(?<balance>[\d,]+\.\d{2})\s+Help\b.*$""",
        option = RegexOption.IGNORE_CASE,
    )

    fun parse(message: String): ParsedTransaction? {
        val normalized = message.trim().replace(Regex("\\s+"), " ")
        if (!normalized.startsWith("Absa:", ignoreCase = true)) return null
        return parseReservation(normalized) ?: parseSettlement(normalized)
    }

    private fun parseReservation(message: String): ParsedTransaction? {
        val match = reservationPattern.matchEntire(message) ?: return null
        return ParsedTransaction(
            institution = "absa",
            accountExternalKey = match.required("account").trim(),
            occurredOn = LocalDate.parse(match.required("date"), dateFormatter),
            amountCents = -parseCents(match.required("amount")),
            availableBalanceCents = parseCents(match.required("balance")),
            status = TransactionStatus.PENDING,
            kind = TransactionKind.CARD_PURCHASE,
            merchant = match.required("merchant").trim(),
            description = "Card reservation",
            reference = null,
            sourceFingerprint = fingerprint(message),
            parseConfidence = 0.99,
            needsReview = true,
        )
    }

    private fun parseSettlement(message: String): ParsedTransaction? {
        val match = settlementPattern.matchEntire(message) ?: return null
        val event = match.required("event").trim()
        val payload = match.required("payload").trim()
        val description = payload.substringBefore(',').trim()
        val reference = payload.substringAfter(',', missingDelimiterValue = "").trim().ifBlank { null }
        val amountCents = parseCents(match.required("amount"))
        val isReversal = event.equals("Returned", ignoreCase = true) ||
            description.contains("UNPAID", ignoreCase = true)
        val kind = classify(event, description, amountCents, isReversal)

        return ParsedTransaction(
            institution = "absa",
            accountExternalKey = match.required("account").trim(),
            occurredOn = LocalDate.parse(match.required("date"), dateFormatter),
            amountCents = amountCents,
            availableBalanceCents = parseCents(match.required("balance")),
            status = if (isReversal) TransactionStatus.REVERSED else TransactionStatus.POSTED,
            kind = kind,
            merchant = reference,
            description = description,
            reference = reference,
            sourceFingerprint = fingerprint(message),
            parseConfidence = 0.98,
            needsReview = kind == TransactionKind.TRANSFER || kind == TransactionKind.OTHER,
        )
    }

    private fun classify(event: String, description: String, amountCents: Long, isReversal: Boolean): TransactionKind {
        if (isReversal) return TransactionKind.REVERSAL
        if (description.contains("PAYSHAP", ignoreCase = true) || description.contains("TRANSF", ignoreCase = true)) {
            return TransactionKind.TRANSFER
        }
        if (event.equals("Sch t", ignoreCase = true) || description.contains("DC INTERNAL", ignoreCase = true) || description.contains("DC TRACK", ignoreCase = true)) {
            return TransactionKind.SCHEDULED_PAYMENT
        }
        if (description.contains("FEE", ignoreCase = true)) return TransactionKind.FEE
        if (description.contains("ATM", ignoreCase = true) || description.contains("CASH", ignoreCase = true)) return TransactionKind.CASH_WITHDRAWAL
        if (event.equals("Dep", ignoreCase = true) || amountCents > 0) return TransactionKind.INCOME
        return TransactionKind.OTHER
    }

    private fun parseCents(value: String): Long = BigDecimal(value.replace(",", ""))
        .movePointRight(2)
        .setScale(0, RoundingMode.UNNECESSARY)
        .longValueExact()

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.lowercase().toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun MatchResult.required(name: String): String = groups[name]?.value
        ?: error("Parser pattern did not provide required group: $name")
}
