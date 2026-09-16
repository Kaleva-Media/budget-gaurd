package app.budgetguard.android.sms

import android.content.Context
import android.provider.Telephony

data class AccountMessageCandidate(
    val institution: String,
    val externalKey: String,
    val suggestedName: String,
    val type: String,
    val role: String,
    val mask: String,
    val messageCount: Int,
    val latestBalanceCents: Long?,
    val latestBalanceAtMillis: Long?,
)

class SmsAccountScanner(context: Context) {
    private val contentResolver = context.applicationContext.contentResolver
    private val discovery = BankAccountDiscovery()
    private val transactionParser = BankSmsParser()

    fun scan(institution: String, maximumMessages: Int = 2_500): List<AccountMessageCandidate> {
        val pattern = when (institution) {
            "absa" -> "%Absa:%"
            "fnb" -> "%FNB%"
            else -> return emptyList()
        }
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )
        val selection = "${Telephony.Sms.BODY} LIKE ?"
        val candidates = linkedMapOf<String, MutableCandidate>()

        contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            selection,
            arrayOf(pattern),
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val bodyColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            var inspected = 0
            while (cursor.moveToNext() && inspected < maximumMessages) {
                inspected += 1
                val body = cursor.getString(bodyColumn).orEmpty()
                val identifier = discovery.discover(institution, body) ?: continue
                val receivedAt = cursor.getLong(dateColumn)
                val candidate = candidates.getOrPut(identifier.externalKey) {
                    MutableCandidate(
                        institution = identifier.institution,
                        externalKey = identifier.externalKey,
                        suggestedName = identifier.suggestedName,
                        type = identifier.type,
                        role = identifier.role,
                        mask = identifier.mask,
                    )
                }
                candidate.messageCount += 1

                val parsed = runCatching { transactionParser.parse(body) }.getOrNull()
                if (
                    parsed?.institution == institution &&
                    parsed.accountExternalKey.equals(identifier.externalKey, ignoreCase = true) &&
                    (candidate.latestBalanceAtMillis == null || receivedAt > candidate.latestBalanceAtMillis!!)
                ) {
                    candidate.latestBalanceCents = parsed.availableBalanceCents
                    candidate.latestBalanceAtMillis = receivedAt
                }
            }
        }

        return candidates.values
            .map(MutableCandidate::toModel)
            .sortedWith(compareBy<AccountMessageCandidate> { it.type }.thenBy { it.suggestedName })
    }

    private data class MutableCandidate(
        val institution: String,
        val externalKey: String,
        val suggestedName: String,
        val type: String,
        val role: String,
        val mask: String,
        var messageCount: Int = 0,
        var latestBalanceCents: Long? = null,
        var latestBalanceAtMillis: Long? = null,
    ) {
        fun toModel() = AccountMessageCandidate(
            institution = institution,
            externalKey = externalKey,
            suggestedName = suggestedName,
            type = type,
            role = role,
            mask = mask,
            messageCount = messageCount,
            latestBalanceCents = latestBalanceCents,
            latestBalanceAtMillis = latestBalanceAtMillis,
        )
    }
}
