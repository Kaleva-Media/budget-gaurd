package app.budgetguard.android.sms

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.util.Locale

class FnbSmsParser(private val today: () -> LocalDate = LocalDate::now) {
    private val prepaidElectricityReceipt = Regex(
        """^FNB\s*:-\)?\s+.+\.\s*Elec\s+Amt:\s*R[\d,.]+\..*\bCredit\s+Token:\s*[\d-]+""",
        RegexOption.IGNORE_CASE,
    )
    private val amountAndEvent = Regex(
        """\bR\s*(?<amount>-?[\d,]+(?:\.\d{1,2})?)\s+(?<event>reserved\s+for\s+(?:a\s+)?purchase|paid|debited|credited|received)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val account = Regex(
        """\b(?:a/c|account|acc|card)(?:\s*(?:ending\s+in)?\s*[.*xX-]*)?(?<account>\d{4,10})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val availableBalance = Regex(
        """\b(?:avail(?:able)?(?:\s+bal(?:ance)?)?|balance)\s*[:=]?\s*R\s*(?<balance>-?[\d,]+(?:\.\d{1,2})?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val numericDate = Regex("""\b(?<date>\d{2}/\d{2}/\d{2})\b""")
    private val compactDate = Regex(
        """\b(?<day>\d{1,2})\s*(?<month>Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)(?:\s*(?<year>\d{4}|\d{2})(?!\d)(?!:))?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val merchant = Regex(
        """reserved\s+for\s+(?:a\s+)?purchase\s+@\s*(?<merchant>.+?)\s+from\s+.+?(?:a/c|account|acc|card)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(message: String): ParsedTransaction? {
        val normalized = message.trim().replace(Regex("\\s+"), " ")
        if (!Regex("""^FNB\s*:-\)?\s+""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) return null
        if (prepaidElectricityReceipt.containsMatchIn(normalized)) return null

        val amountMatch = amountAndEvent.find(normalized) ?: return null
        val accountMatch = account.find(normalized) ?: return null
        val balanceMatch = availableBalance.find(normalized) ?: return null
        val occurredOn = parseDate(normalized) ?: return null
        val event = amountMatch.required("event").lowercase()
        val unsignedAmount = kotlin.math.abs(parseCents(amountMatch.required("amount")))
        val isCredit = event == "credited" || event == "received" || Regex("""\bpaid\s+to\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        val amountCents = if (isCredit) unsignedAmount else -unsignedAmount
        val description = describe(normalized, event)
        val kind = classify(event, normalized, isCredit)

        return ParsedTransaction(
            institution = "fnb",
            accountExternalKey = "FNB-${accountMatch.required("account")}",
            occurredOn = occurredOn,
            amountCents = amountCents,
            availableBalanceCents = parseCents(balanceMatch.required("balance")),
            status = if (event.startsWith("reserved")) TransactionStatus.PENDING else TransactionStatus.POSTED,
            kind = kind,
            merchant = merchant.find(normalized)?.groups?.get("merchant")?.value?.trim(),
            description = description,
            reference = reference(normalized),
            sourceFingerprint = fingerprint(normalized),
            parseConfidence = 0.91,
            needsReview = kind == TransactionKind.TRANSFER || kind == TransactionKind.OTHER,
        )
    }

    private fun parseDate(message: String): LocalDate? {
        numericDate.find(message)?.let { match ->
            return runCatching {
                LocalDate.parse(match.required("date"), DateTimeFormatter.ofPattern("dd/MM/yy"))
            }.getOrNull()
        }
        val match = compactDate.find(message) ?: return null
        return runCatching {
            val abbreviation = match.required("month").take(3).uppercase(Locale.ENGLISH)
            val month = Month.entries.first { it.name.startsWith(abbreviation) }
            val day = match.required("day").toInt()
            val yearText = match.groups["year"]?.value
            val year = when (yearText?.length) {
                2 -> 2000 + yearText.toInt()
                4 -> yearText.toInt()
                else -> inferYear(month, day)
            }
            LocalDate.of(year, month, day)
        }.getOrNull()
    }

    private fun inferYear(month: Month, day: Int): Int {
        val now = today()
        val candidate = LocalDate.of(now.year, month, day)
        return if (candidate.isAfter(now.plusMonths(1))) now.year - 1 else now.year
    }

    private fun classify(event: String, message: String, isCredit: Boolean): TransactionKind = when {
        event.startsWith("reserved") -> TransactionKind.CARD_PURCHASE
        message.contains("reversed", ignoreCase = true) -> TransactionKind.REVERSAL
        message.contains("debit order", ignoreCase = true) || message.contains("scheduled", ignoreCase = true) -> TransactionKind.SCHEDULED_PAYMENT
        isCredit -> TransactionKind.INCOME
        message.contains("payshap", ignoreCase = true) || message.contains("transfer", ignoreCase = true) || event == "paid" -> TransactionKind.TRANSFER
        message.contains("ATM", ignoreCase = true) || message.contains("cash", ignoreCase = true) -> TransactionKind.CASH_WITHDRAWAL
        message.contains("fee", ignoreCase = true) || message.contains("charge", ignoreCase = true) -> TransactionKind.FEE
        else -> TransactionKind.OTHER
    }

    private fun describe(message: String, event: String): String = when {
        event.startsWith("reserved") -> "FNB card reservation"
        message.contains("debit order", ignoreCase = true) -> "FNB debit order"
        event == "credited" || event == "received" -> "FNB account credit"
        event == "paid" -> "FNB payment"
        else -> "FNB account debit"
    }

    private fun reference(message: String): String? = Regex(
        """\bRef(?:erence)?[.:\s]+(?<reference>.+?)(?:\s+(?:Avail|Available|Balance)\b|$)""",
        RegexOption.IGNORE_CASE,
    ).find(message)?.groups?.get("reference")?.value?.trim()?.trimEnd('.')

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
