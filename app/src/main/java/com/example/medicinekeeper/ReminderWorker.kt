package com.example.medicinekeeper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.medicinekeeper.data.AppDatabase
import java.util.concurrent.TimeUnit

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val upcoming = AppDatabase.get(applicationContext).medicineDao()
            .expiringBetween(now, now + TimeUnit.DAYS.toMillis(7))
        if (upcoming.isEmpty()) return Result.success()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "药品到期提醒", NotificationManager.IMPORTANCE_HIGH))
        upcoming.forEach { medicine ->
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("药品即将到期")
                .setContentText("${medicine.name} 将在一周内到期，请及时处理。")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            manager.notify(medicine.id.toInt(), notification)
            AppDatabase.get(applicationContext).medicineDao().markReminderSent(medicine.id)
        }
        return Result.success()
    }
    companion object { const val CHANNEL_ID = "expiry_reminders" }
}
