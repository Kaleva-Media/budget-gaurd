package app.budgetguard.android.data

import app.budgetguard.android.sms.BankSmsParser
import app.budgetguard.android.sms.ParsedTransaction

class TransactionRepository(private val dao: TransactionDao) {
    val transactionCount = dao.observeCount()
    val pendingSyncCount = dao.observePendingSyncCount()

    suspend fun ingest(transaction: ParsedTransaction): Boolean {
        val rowId = dao.insert(
            LocalTransactionEntity(
                institution = transaction.institution,
                accountExternalKey = transaction.accountExternalKey,
                occurredOn = transaction.occurredOn.toString(),
                amountCents = transaction.amountCents,
                availableBalanceCents = transaction.availableBalanceCents,
                status = transaction.status.name.lowercase(),
                kind = transaction.kind.name.lowercase(),
                merchant = transaction.merchant,
                description = transaction.description,
                reference = transaction.reference,
                sourceFingerprint = transaction.sourceFingerprint,
                parserVersion = BankSmsParser.PARSER_VERSION,
                parseConfidence = transaction.parseConfidence,
                needsReview = transaction.needsReview,
            ),
        )
        return rowId != -1L
    }

    suspend fun pendingBatch(limit: Int = 50): List<LocalTransactionEntity> = dao.pendingSync(limit)

    suspend fun markSynced(id: Long) = dao.markSynced(id)
}
