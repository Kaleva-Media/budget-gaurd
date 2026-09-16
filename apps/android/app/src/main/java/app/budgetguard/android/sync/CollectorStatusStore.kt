package app.budgetguard.android.sync

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class CollectorStatus(
    val state: String,
    val message: String?,
    val lastSuccessEpochMs: Long,
) {
    val displayText: String
        get() = when (state) {
            "syncing" -> "Sync in progress…"
            "queued" -> "Sync queued for the next network opportunity."
            "attention" -> message ?: "Some transactions need attention."
            "retry" -> "Network sync will retry automatically."
            "success" -> if (lastSuccessEpochMs > 0) "Last synced ${formatTime(lastSuccessEpochMs)}" else "Sync complete."
            else -> "No sync has run yet."
        }

    private fun formatTime(epochMs: Long): String = DateTimeFormatter.ofPattern("d MMM, HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
}

class CollectorStatusStore(context: Context) {
    private val preferences = context.getSharedPreferences("collector_status", Context.MODE_PRIVATE)
    private val mutableStatus = MutableStateFlow(read())
    val status: StateFlow<CollectorStatus> = mutableStatus

    fun markQueued() = write("queued")
    fun markSyncing() = write("syncing")
    fun markRetry() = write("retry")
    fun markAttention(message: String) = write("attention", message)
    fun markSuccess() = write("success", successEpochMs = System.currentTimeMillis())

    private fun read() = CollectorStatus(
        state = preferences.getString("state", "idle") ?: "idle",
        message = preferences.getString("message", null),
        lastSuccessEpochMs = preferences.getLong("last_success_epoch_ms", 0),
    )

    private fun write(state: String, message: String? = null, successEpochMs: Long? = null) {
        val previousSuccess = preferences.getLong("last_success_epoch_ms", 0)
        val next = CollectorStatus(state, message, successEpochMs ?: previousSuccess)
        preferences.edit {
            putString("state", next.state)
            putString("message", next.message)
            putLong("last_success_epoch_ms", next.lastSuccessEpochMs)
        }
        mutableStatus.value = next
    }
}
