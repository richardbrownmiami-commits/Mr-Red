package com.arick.androidlocalllmlab

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

object AlarmScheduler {

    fun setInAppAlarm(context: Context, hour: Int, minute: Int, message: String = "Mr-Red Alarm"): ToolResult {
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolResult.Failure("Invalid time: hour must be 0-23, minute 0-59.")
        }
        return runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return ToolResult.Failure("AlarmManager service not available.")

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val triggerMillis = calendar.timeInMillis
            val diffMillis = triggerMillis - System.currentTimeMillis()
            val diffHours = diffMillis / (1000 * 60 * 60)
            val diffMins = (diffMillis / (1000 * 60)) % 60

            val intent = Intent(context, MrRedAlarmReceiver::class.java).apply {
                action = MrRedAlarmReceiver.ACTION_ALARM_TRIGGER
                putExtra(MrRedAlarmReceiver.EXTRA_ALARM_MESSAGE, message)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                MrRedAlarmReceiver.NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val showIntent = Intent(context, MainActivity::class.java)
            val showPending = PendingIntent.getActivity(
                context,
                0,
                showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerMillis, showPending),
                pendingIntent
            )

            ScheduledTasksStore.recordAlarm(context, hour, minute, message, triggerMillis)

            val timeFormatted = String.format("%02d:%02d", hour, minute)
            val inTimeStr = if (diffHours > 0) "${diffHours}h ${diffMins}m" else "${diffMins}m"
            ToolResult.Success("Alarm set in Mr-Red for $timeFormatted (rings in $inTimeStr) with ringtone and notification.")
        }.getOrElse {
            ToolResult.Failure("Failed to schedule alarm: ${it.message ?: "error"}")
        }
    }

    fun dismissAlarm(context: Context): ToolResult {
        MrRedAlarmReceiver.stopRingtone(context)
        ScheduledTasksStore.clearAlarms(context)
        return ToolResult.Success("Alarm dismissed and sound stopped.")
    }

    fun scheduleReminder(
        context: Context,
        message: String,
        intervalMinutes: Long,
        recurring: Boolean
    ): ToolResult {
        if (message.isBlank()) return ToolResult.Failure("Reminder message cannot be empty.")
        return runCatching {
            val wm = WorkManager.getInstance(context)
            val data = Data.Builder()
                .putString(MrRedReminderWorker.KEY_TITLE, "⏰ Mr-Red Reminder")
                .putString(MrRedReminderWorker.KEY_MESSAGE, message)
                .build()

            val cleanTag = "reminder_" + message.lowercase().filter { it.isLetterOrDigit() }.take(20)

            if (recurring) {
                val effectiveMinutes = intervalMinutes.coerceAtLeast(15)
                val request = PeriodicWorkRequestBuilder<MrRedReminderWorker>(
                    effectiveMinutes,
                    TimeUnit.MINUTES
                )
                    .setInputData(data)
                    .addTag("mr_red_reminder")
                    .addTag(cleanTag)
                    .build()

                wm.enqueueUniquePeriodicWork(
                    cleanTag,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )

                val note = if (intervalMinutes < 15) " (adjusted to Android WorkManager 15-min minimum)" else ""
                val intervalDisplay = if (effectiveMinutes >= 60 && effectiveMinutes % 60 == 0L) {
                    "${effectiveMinutes / 60} hour(s)"
                } else {
                    "$effectiveMinutes minutes"
                }

                ScheduledTasksStore.recordReminder(
                    context = context,
                    id = cleanTag,
                    message = message,
                    scheduleDisplay = "Every $intervalDisplay$note",
                    recurring = true,
                    targetMillis = System.currentTimeMillis() + (effectiveMinutes * 60 * 1000)
                )

                ToolResult.Success("Recurring reminder set: '$message' every $intervalDisplay$note.")
            } else {
                val delayMinutes = intervalMinutes.coerceAtLeast(1)
                val request = OneTimeWorkRequestBuilder<MrRedReminderWorker>()
                    .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                    .setInputData(data)
                    .addTag("mr_red_reminder")
                    .addTag(cleanTag)
                    .build()

                wm.enqueueUniqueWork(
                    cleanTag,
                    ExistingWorkPolicy.REPLACE,
                    request
                )

                val delayDisplay = if (delayMinutes >= 60 && delayMinutes % 60 == 0L) {
                    "${delayMinutes / 60} hour(s)"
                } else {
                    "$delayMinutes minute(s)"
                }

                ScheduledTasksStore.recordReminder(
                    context = context,
                    id = cleanTag,
                    message = message,
                    scheduleDisplay = "In $delayDisplay",
                    recurring = false,
                    targetMillis = System.currentTimeMillis() + (delayMinutes * 60 * 1000)
                )

                ToolResult.Success("Reminder scheduled: '$message' in $delayDisplay.")
            }
        }.getOrElse {
            ToolResult.Failure("Failed to schedule reminder: ${it.message ?: "error"}")
        }
    }

    fun cancelSingleReminder(context: Context, id: String): ToolResult {
        return runCatching {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(id)
            ScheduledTasksStore.removeReminder(context, id)
            ToolResult.Success("Reminder cancelled.")
        }.getOrElse {
            ToolResult.Failure("Failed to cancel reminder: ${it.message ?: "error"}")
        }
    }

    fun cancelReminders(context: Context): ToolResult {
        return runCatching {
            val wm = WorkManager.getInstance(context)
            wm.cancelAllWorkByTag("mr_red_reminder")
            ScheduledTasksStore.clearReminders(context)
            ToolResult.Success("All Mr-Red reminders have been cancelled.")
        }.getOrElse {
            ToolResult.Failure("Failed to cancel reminders: ${it.message ?: "error"}")
        }
    }
}
