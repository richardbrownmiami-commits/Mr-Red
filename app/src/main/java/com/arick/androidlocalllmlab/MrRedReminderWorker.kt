package com.arick.androidlocalllmlab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

class MrRedReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val CHANNEL_ID = "mr_red_reminders_v2"
        const val KEY_MESSAGE = "reminder_message"
        const val KEY_TITLE = "reminder_title"
    }

    override suspend fun doWork(): Result {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val title = inputData.getString(KEY_TITLE) ?: "⏰ Mr-Red Reminder"
        val message = inputData.getString(KEY_MESSAGE) ?: "Time for your reminder!"
        ActivityLogger.record(context, "REMINDER", "worker_started", "run_attempt=$runAttemptCount title=$title message=$message")
        return try {
            showNotificationAndAlert(title, message)
            // Wait briefly so Text-to-Speech can begin its announcement.
            delay(3500)
            ActivityLogger.record(context, "REMINDER", "worker_completed", "duration_ms=${android.os.SystemClock.elapsedRealtime() - startedAt} message=$message")
            Result.success()
        } catch (error: Throwable) {
            ActivityLogger.record(context, "ERROR", "reminder_worker_failed", "duration_ms=${android.os.SystemClock.elapsedRealtime() - startedAt} type=${error.javaClass.name} message=${error.message}")
            Result.failure()
        }
    }

    private fun showNotificationAndAlert(title: String, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: throw IllegalStateException("NotificationManager service unavailable")

        val soundUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { nm.deleteNotificationChannel("mr_red_reminders_channel") } catch (_: Throwable) {}

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mr-Red Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Audible spoken reminders"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
                setSound(soundUri, audioAttributes)
            }
            nm.createNotificationChannel(channel)
        }

        try {
            val ringtone = RingtoneManager.getRingtone(context, soundUri)
            ringtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone?.play()
        } catch (_: Throwable) {}

        // Speak reminder aloud
        try {
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    tts?.speak("Reminder: $message", TextToSpeech.QUEUE_FLUSH, null, "mr_red_worker_tts")
                }
            }
        } catch (_: Throwable) {}

        val dismissIntent = Intent(context, MrRedAlarmReceiver::class.java).apply {
            action = MrRedAlarmReceiver.ACTION_DISMISS_ALARM
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            MrRedAlarmReceiver.REMINDER_NOTIFICATION_ID,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(soundUri)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
            .build()

        val notificationId = (System.currentTimeMillis() % 100000).toInt() + 2000
        nm.notify(notificationId, notification)
        ActivityLogger.record(context, "REMINDER", "worker_notification_posted", "notification_id=$notificationId")
    }
}
