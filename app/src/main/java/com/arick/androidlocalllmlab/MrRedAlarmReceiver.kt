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
import androidx.core.app.NotificationCompat

class MrRedAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALARM_TRIGGER = "com.arick.androidlocalllmlab.ACTION_ALARM_TRIGGER"
        const val ACTION_DISMISS_ALARM = "com.arick.androidlocalllmlab.ACTION_DISMISS_ALARM"
        const val EXTRA_ALARM_MESSAGE = "extra_alarm_message"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "mr_red_alarms_channel"

        private var activeRingtone: Ringtone? = null

        fun stopRingtone(context: Context) {
            try {
                activeRingtone?.stop()
                activeRingtone = null
            } catch (_: Throwable) {}
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == ACTION_DISMISS_ALARM) {
            stopRingtone(context)
            return
        }

        if (action == ACTION_ALARM_TRIGGER || action == Intent.ACTION_BOOT_COMPLETED) {
            val message = intent.getStringExtra(EXTRA_ALARM_MESSAGE) ?: "Mr-Red Alarm"
            playAlarm(context, message)
        }
    }

    private fun playAlarm(context: Context, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mr-Red Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Mr-Red in-app alarm notifications"
                enableVibration(true)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }

        try {
            val alarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            activeRingtone?.stop()
            val ringtone = RingtoneManager.getRingtone(context, alarmUri)
            ringtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone?.play()
            activeRingtone = ringtone
        } catch (_: Throwable) {}

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

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ Mr-Red Alarm")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}
