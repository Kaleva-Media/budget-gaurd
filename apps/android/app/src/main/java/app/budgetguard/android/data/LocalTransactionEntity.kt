package app.budgetguard.android.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_transactions",
    indices = [
        Index(value = ["source_fingerprint"], unique = true),
        Index(value = ["occurred_on"]),
        Index(value = ["sync_state"]),
    ],
)
data class LocalTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val institution: String,
    @ColumnInfo(name = "account_external_key") val accountExternalKey: String,
    @ColumnInfo(name = "occurred_on") val occurredOn: String,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @ColumnInfo(name = "available_balance_cents") val availableBalanceCents: Long,
    val status: String,
    val kind: String,
    val merchant: String?,
    val description: String,
    val reference: String?,
    @ColumnInfo(name = "source_fingerprint") val sourceFingerprint: String,
    @ColumnInfo(name = "parser_version") val parserVersion: Int,
    @ColumnInfo(name = "parse_confidence") val parseConfidence: Double,
    @ColumnInfo(name = "needs_review") val needsReview: Boolean,
    @ColumnInfo(name = "sync_state") val syncState: String = "pending",
    @ColumnInfo(name = "captured_at_epoch_ms") val capturedAtEpochMs: Long = System.currentTimeMillis(),
)
