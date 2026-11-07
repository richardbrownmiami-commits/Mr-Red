package com.arick.androidlocalllmlab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class MrRedReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val CHANNEL_ID = "mr_red_reminders_channel"
        const val KEY_MESSAGE = "reminder_message"
        const val KEY_TITLE = "reminder_title"
    }

    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE) ?: "Mr-Red Reminder"
        val message = inputData.getString(KEY_MESSAGE) ?: "Time for your reminder!"
        showNotification(title, message)
        return Result.success()
    }

    private fun showNotification(title: String, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mr-Red Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Periodic reminders scheduled with WorkManager"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationId = (System.currentTimeMillis() % 100000).toInt() + 2000
        nm.notify(notificationId, notification)
    }
}
