package app.budgetguard.android.sms

import java.time.LocalDate

enum class TransactionStatus { PENDING, POSTED, REVERSED, FAILED }

enum class TransactionKind {
    CARD_PURCHASE,
    TRANSFER,
    SCHEDULED_PAYMENT,
    CASH_WITHDRAWAL,
    FEE,
    INCOME,
    REVERSAL,
    OTHER,
}

data class ParsedTransaction(
    val institution: String,
    val accountExternalKey: String,
    val occurredOn: LocalDate,
    val amountCents: Long,
    val availableBalanceCents: Long,
    val status: TransactionStatus,
    val kind: TransactionKind,
    val merchant: String?,
    val description: String,
    val reference: String?,
    val sourceFingerprint: String,
    val parseConfidence: Double,
    val needsReview: Boolean,
)
