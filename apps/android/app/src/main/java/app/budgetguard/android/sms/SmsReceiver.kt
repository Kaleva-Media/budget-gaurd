package app.budgetguard.android.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.budgetguard.android.BudgetGuardApplication
import app.budgetguard.android.sync.SyncScheduler
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

class SmsReceiver : BroadcastReceiver() {
    private val parser = BankSmsParser()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        val parsed = parser.parse(body) ?: return
        val pendingResult = goAsync()
        val executor = Executors.newSingleThreadExecutor()

        executor.execute {
            try {
                val application = context.applicationContext as BudgetGuardApplication
                val inserted = runBlocking { application.repository.ingest(parsed) }
                if (inserted) SyncScheduler.enqueueNow(context.applicationContext)
            } finally {
                pendingResult.finish()
                executor.shutdown()
            }
        }
    }
}
