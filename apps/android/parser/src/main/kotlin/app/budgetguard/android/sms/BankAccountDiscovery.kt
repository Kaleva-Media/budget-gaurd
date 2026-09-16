package app.budgetguard.android.sms

data class DiscoveredAccountIdentifier(
    val institution: String,
    val externalKey: String,
    val suggestedName: String,
    val type: String,
    val role: String,
    val mask: String,
)

class BankAccountDiscovery {
    private val absaAccount = Regex(
        pattern = """^Absa:\s*(?<account>[^,]{4,32}),""",
        option = RegexOption.IGNORE_CASE,
    )
    private val fnbAccount = Regex(
        pattern = """\b(?:a/c|account|acc|card)(?:\s*(?:ending\s+in)?\s*[.*xX-]*)?(?<account>\d{4,10})\b""",
        option = RegexOption.IGNORE_CASE,
    )

    fun discover(institution: String, message: String): DiscoveredAccountIdentifier? {
        val normalized = message.trim().replace(Regex("\\s+"), " ")
        return when (institution.lowercase()) {
            "absa" -> discoverAbsa(normalized)
            "fnb" -> discoverFnb(normalized)
            else -> null
        }
    }

    private fun discoverAbsa(message: String): DiscoveredAccountIdentifier? {
        val match = absaAccount.find(message) ?: return null
        val key = match.groups["account"]?.value?.trim()?.uppercase() ?: return null
        if (!key.any(Char::isDigit) || !key.takeLast(6).count(Char::isDigit).let { it >= 4 }) return null
        val type = inferType(key)
        return DiscoveredAccountIdentifier(
            institution = "absa",
            externalKey = key,
            suggestedName = "Absa ${typeLabel(type)} ${key.takeLast(4)}",
            type = type,
            role = roleFor(type),
            mask = "•••• ${key.takeLast(4)}",
        )
    }

    private fun discoverFnb(message: String): DiscoveredAccountIdentifier? {
        if (!Regex("""^FNB\s*:-\)?\s+""", RegexOption.IGNORE_CASE).containsMatchIn(message)) return null
        val match = fnbAccount.find(message) ?: return null
        val suffix = match.groups["account"]?.value ?: return null
        val contextStart = (match.range.first - 28).coerceAtLeast(0)
        val context = message.substring(contextStart, match.range.first)
        val type = when {
            context.contains("saving", ignoreCase = true) -> "savings"
            context.contains("credit", ignoreCase = true) -> "credit_card"
            else -> "cheque"
        }
        return DiscoveredAccountIdentifier(
            institution = "fnb",
            externalKey = "FNB-$suffix",
            suggestedName = "FNB ${typeLabel(type)} ${suffix.takeLast(4)}",
            type = type,
            role = roleFor(type),
            mask = "•••• ${suffix.takeLast(4)}",
        )
    }

    private fun inferType(key: String): String = when {
        key.contains("SAVE", ignoreCase = true) -> "savings"
        key.contains("CARD", ignoreCase = true) || key.startsWith("CC", ignoreCase = true) -> "credit_card"
        key.contains("LOAN", ignoreCase = true) -> "home_loan"
        else -> "cheque"
    }

    private fun roleFor(type: String): String = when (type) {
        "savings" -> "savings"
        "home_loan" -> "liability"
        "rewards" -> "rewards"
        else -> "operational"
    }

    private fun typeLabel(type: String): String = when (type) {
        "savings" -> "savings"
        "credit_card" -> "card"
        "home_loan" -> "home loan"
        else -> "account"
    }
}
