package com.arick.androidlocalllmlab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale

class MrRedAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALARM_TRIGGER = "com.arick.androidlocalllmlab.ACTION_ALARM_TRIGGER"
        const val ACTION_REMINDER_TRIGGER = "com.arick.androidlocalllmlab.ACTION_REMINDER_TRIGGER"
        const val ACTION_DISMISS_ALARM = "com.arick.androidlocalllmlab.ACTION_DISMISS_ALARM"
        const val EXTRA_ALARM_MESSAGE = "extra_alarm_message"
        const val NOTIFICATION_ID = 1001
        const val REMINDER_NOTIFICATION_ID = 1002
        const val CHANNEL_ID_ALARM = "mr_red_alarms_v2"
        const val CHANNEL_ID_REMINDER = "mr_red_reminders_v2"

        private var activeRingtone: Ringtone? = null
        private var activeTts: TextToSpeech? = null

        fun stopRingtone(context: Context) {
            try {
                activeRingtone?.stop()
                activeRingtone = null
            } catch (_: Throwable) {}
            try {
                activeTts?.stop()
                activeTts?.shutdown()
                activeTts = null
            } catch (_: Throwable) {}
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
            nm?.cancel(REMINDER_NOTIFICATION_ID)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        ActivityLogger.record(context, "ALARM", "receiver_invoked", "action=${action ?: "null"}")
        if (action == ACTION_DISMISS_ALARM) {
            stopRingtone(context)
            ActivityLogger.record(context, "ALARM", "dismissed", "Alarm/reminder sound and notifications stopped")
            return
        }

        val message = intent.getStringExtra(EXTRA_ALARM_MESSAGE) ?: "Mr-Red Alert"
        when (action) {
            ACTION_REMINDER_TRIGGER -> {
                ActivityLogger.record(context, "REMINDER", "triggered", "message=$message")
                playReminder(context, message)
            }
            ACTION_ALARM_TRIGGER, Intent.ACTION_BOOT_COMPLETED -> {
                ActivityLogger.record(context, "ALARM", "triggered", "message=$message source=$action")
                playAlarm(context, message)
            }
            else -> ActivityLogger.record(context, "ALARM", "ignored_broadcast", "unsupported_action=${action}")
        }
    }

    private fun playAlarm(context: Context, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val alarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Delete old silent channel if it exists
            try { nm.deleteNotificationChannel("mr_red_alarms_channel") } catch (_: Throwable) {}

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID_ALARM,
                "Mr-Red Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Mr-Red audible alarm notifications"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800)
                setSound(alarmUri, audioAttributes)
            }
            nm.createNotificationChannel(channel)
        }

        try {
            activeRingtone?.stop()
            val ringtone = RingtoneManager.getRingtone(context, alarmUri)
            ringtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone?.play()
            activeRingtone = ringtone
        } catch (_: Throwable) {}

        speakAnnouncement(context.applicationContext, "Alarm. $message")

        val dismissIntent = Intent(context, MrRedAlarmReceiver::class.java).apply {
            action = ACTION_DISMISS_ALARM
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ Mr-Red Alarm")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(alarmUri)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun playReminder(context: Context, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val soundUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { nm.deleteNotificationChannel("mr_red_reminders_channel") } catch (_: Throwable) {}

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID_REMINDER,
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
            activeRingtone?.stop()
            val ringtone = RingtoneManager.getRingtone(context, soundUri)
            ringtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone?.play()
            activeRingtone = ringtone
        } catch (error: Throwable) {
            ActivityLogger.record(context, "ERROR", "reminder_audio_failed", "${error.javaClass.simpleName}: ${error.message}")
        }

        speakAnnouncement(context.applicationContext, "Reminder: $message")

        val dismissIntent = Intent(context, MrRedAlarmReceiver::class.java).apply {
            action = ACTION_DISMISS_ALARM
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            REMINDER_NOTIFICATION_ID,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_REMINDER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⏰ Mr-Red Reminder")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(soundUri)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
            .build()

        nm.notify(REMINDER_NOTIFICATION_ID, notification)
        ActivityLogger.record(context, "REMINDER", "notification_posted", "notification_id=$REMINDER_NOTIFICATION_ID")
    }

    private fun speakAnnouncement(appContext: Context, text: String) {
        try {
            var tts: TextToSpeech? = null
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    activeTts?.stop()
                    activeTts?.shutdown()
                    activeTts = tts
                    tts?.language = Locale.getDefault()
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mr_red_alert_tts")
                }
            }
        } catch (_: Throwable) {}
    }
}
