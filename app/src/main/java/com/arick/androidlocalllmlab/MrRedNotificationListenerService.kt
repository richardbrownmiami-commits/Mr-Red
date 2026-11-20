package com.arick.androidlocalllmlab

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/** Notification contents are held in process memory only; they are not written to SQLite. */
data class MrRedNotification(\n    val app: String,\n    val title: String,\n    val text: String,\n    val key: String = "",\n    val packageName: String = "",\n    val postedAtMillis: Long = System.currentTimeMillis()\n)

class MrRedNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        ActivityLogger.record(applicationContext, "NOTIFICATION", "listener_connected", "Android notification access connected")
        syncActiveNotifications()
    }

    override fun onListenerDisconnected() {
        ActivityLogger.record(applicationContext, "NOTIFICATION", "listener_disconnect_callback", "Android disconnected notification listener")
        super.onListenerDisconnected()
        if (instance === this) {
            instance = null
            activeNotificationRecords.clear()
            ActivityLogger.record(applicationContext, "NOTIFICATION", "listener_disconnected", "Android notification access disconnected")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        processNotification(sbn)
        val extras = sbn.notification?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        ActivityLogger.record(
            applicationContext,
            "NOTIFICATION",
            "posted",
            "${sbn.packageName.substringAfterLast('.')}: $title — $text"
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        activeNotificationRecords.removeAll { it.key == sbn.key }
        ActivityLogger.record(
            applicationContext,
            "NOTIFICATION",
            "removed",
            sbn.packageName.substringAfterLast('.') + ": " + sbn.key.take(120)
        )
        // Recent history is retained separately for historical questions.
    }

    fun syncActiveNotifications() {
        runCatching {
            val active = activeNotifications ?: run {
                ActivityLogger.record(applicationContext, "ERROR", "notification_sync_unavailable", "Android returned null activeNotifications")
                return
            }
            this@MrRedNotificationListenerService.let { service ->
                Companion.activeNotificationRecords.clear()
                for (sbn in active) service.processNotification(sbn)
            }
            ActivityLogger.record(applicationContext, "NOTIFICATION", "active_notifications_synced", "count=${active.size}")
        }.onFailure { error ->
            ActivityLogger.record(applicationContext, "ERROR", "notification_sync_failed", "${error.javaClass.name}: ${error.message}")
        }
    }

    private fun processNotification(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification?.extras ?: return
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString().orEmpty().trim()
        val text = buildList {
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().trim()
            val normalText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().trim()
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString().trim() }.orEmpty()
            val selected = bigText.ifBlank { normalText }
            if (selected.isNotBlank()) add(selected)
            if (subText.isNotBlank() && subText != selected) add(subText)
            lines.filter { it.isNotBlank() && it !in this }.forEach(::add)
        }.joinToString("\\n")
        if (title.isBlank() && text.isBlank()) return
        val shortApp = sbn.packageName.substringAfterLast('.')
        val appLabel = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(shortApp)\n        val record = MrRedNotification(appLabel, title, text, sbn.key, sbn.packageName, sbn.postTime)
        activeNotificationRecords.removeAll { it.key == sbn.key }
        activeNotificationRecords.add(0, record)
        notifications.removeAll { (it.app == shortApp || it.app == sbn.packageName) && it.title == title }
        notifications.add(0, record)
        while (notifications.size > MAX_NOTIFICATIONS) notifications.removeAt(notifications.lastIndex)
    }

    companion object {
        private const val MAX_NOTIFICATIONS = 100
        private val notifications = CopyOnWriteArrayList<MrRedNotification>()
        private val activeNotificationRecords = CopyOnWriteArrayList<MrRedNotification>()
        @Volatile private var instance: MrRedNotificationListenerService? = null

        fun recentNotifications(context: Context? = null, sinceMillis: Long = 0L): List<MrRedNotification> {
            instance?.syncActiveNotifications()
            if (instance == null && context != null) {
                requestRebindService(context)
            }
            return notifications.filter { it.postedAtMillis >= sinceMillis }.sortedByDescending { it.postedAtMillis }
        }

        fun requestRebindService(context: Context) {
            runCatching {
                requestRebind(ComponentName(context, MrRedNotificationListenerService::class.java))
            }
        }

        fun currentNotifications(context: Context? = null): List<MrRedNotification> {
            val service = instance
            if (service != null) {
                service.syncActiveNotifications()
            } else if (context != null) {
                requestRebindService(context)
            }
            return activeNotificationRecords.toList()
        }

        fun isConnected(): Boolean = instance != null
    }
}
