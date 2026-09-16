package app.budgetguard.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.budgetguard.android.BudgetGuardApplication
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TransactionSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = syncMutex.withLock {
        val application = applicationContext as BudgetGuardApplication
        val client = application.collectorClient ?: return Result.success()
        val rows = application.repository.pendingBatch()
        if (rows.isEmpty()) {
            application.statusStore.markSuccess()
            return Result.success()
        }

        application.statusStore.markSyncing()
        return try {
            val report = client.upload(rows) { application.repository.markSynced(it) }
            if (report.detectedAccounts.isEmpty()) {
                application.statusStore.markSuccess()
            } else {
                application.statusStore.markAttention(
                    "Added ${report.detectedAccounts.joinToString()} from SMS. Review its purpose in Accounts.",
                )
            }
            Result.success()
        } catch (_: NotAuthenticatedException) {
            application.statusStore.markAttention("Sign in to BudgetGuard to resume transaction sync.")
            Result.success()
        } catch (_: Exception) {
            application.statusStore.markRetry()
            Result.retry()
        }
    }

    private companion object {
        val syncMutex = Mutex()
    }
}
