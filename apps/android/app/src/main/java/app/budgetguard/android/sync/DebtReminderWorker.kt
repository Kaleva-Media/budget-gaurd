package app.budgetguard.android.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.budgetguard.android.MainActivity
import app.budgetguard.android.R
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object DebtReminderScheduler {
    private const val PREFERENCES = "debt_reminders"

    fun sync(context: Context, entityId: String, enabled: Boolean, day: Int, latestCheckInMonth: String?) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            if (latestCheckInMonth == null) remove("checked_$entityId") else putString("checked_$entityId", latestCheckInMonth)
        }
        if (enabled) schedule(context, entityId, day, afterCurrentOccurrence = false)
        else WorkManager.getInstance(context).cancelUniqueWork(workName(entityId))
    }

    fun schedule(context: Context, entityId: String, day: Int, afterCurrentOccurrence: Boolean) {
        val now = ZonedDateTime.now()
        var targetMonth = YearMonth.from(now)
        var target = targetMonth.atDay(day.coerceIn(1, 28)).atTime(LocalTime.of(9, 0)).atZone(now.zone)
        if (afterCurrentOccurrence || !target.isAfter(now)) {
            targetMonth = targetMonth.plusMonths(1)
            target = targetMonth.atDay(day.coerceIn(1, 28)).atTime(LocalTime.of(9, 0)).atZone(now.zone)
        }
        val request = OneTimeWorkRequestBuilder<DebtReminderWorker>()
            .setInitialDelay(Duration.between(now, target).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("entity_id" to entityId, "day" to day.coerceIn(1, 28)))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(entityId), ExistingWorkPolicy.REPLACE, request)
    }

    fun latestCheckedMonth(context: Context, entityId: String): String? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString("checked_$entityId", null)

    private fun workName(entityId: String) = "debt-check-in-$entityId"
}

class DebtReminderWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result {
        val entityId = inputData.getString("entity_id") ?: return Result.success()
        val day = inputData.getInt("day", 28).coerceIn(1, 28)
        val currentMonth = YearMonth.now().atDay(1).toString()
        if (DebtReminderScheduler.latestCheckedMonth(applicationContext, entityId) != currentMonth) {
            showNotification(entityId)
        }
        DebtReminderScheduler.schedule(applicationContext, entityId, day, afterCurrentOccurrence = true)
        return Result.success()
    }

    private fun showNotification(entityId: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID,
                "Debt check-ins",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Monthly reminders to update debt balances" })
        }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_debt_freedom", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            entityId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("BudgetGuard check-in")
            .setContentText("Your monthly balance check-in is ready.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(entityId.hashCode(), notification)
    }

    private companion object {
        const val CHANNEL_ID = "debt_check_ins"
    }
}
