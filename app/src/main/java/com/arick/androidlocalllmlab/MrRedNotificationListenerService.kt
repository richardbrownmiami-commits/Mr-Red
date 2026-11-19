package com.arick.androidlocalllmlab

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/** Notification contents are held in process memory only; they are not written to SQLite. */
data class MrRedNotification(val app: String, val title: String, val text: String, val key: String = "")

class MrRedNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        syncActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) {
            instance = null
            activeNotificationRecords.clear()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        processNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        activeNotificationRecords.removeAll { it.key == sbn.key }
        // Recent history is retained separately for historical questions.
    }

    fun syncActiveNotifications() {
        runCatching {
            val active = activeNotificationRecords ?: return
            this@MrRedNotificationListenerService.let { service ->
                Companion.activeNotificationRecords.clear()
                for (sbn in active) service.processNotification(sbn)
            }
        }
    }

    private fun processNotification(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification?.extras ?: return
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString().orEmpty().trim()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString().orEmpty().trim()
        if (title.isBlank() && text.isBlank()) return
        val shortApp = sbn.packageName.substringAfterLast('.')
        val record = MrRedNotification(shortApp, title.take(160), text.take(400), sbn.key)
        activeNotificationRecords.removeAll { it.key == sbn.key }
        activeNotificationRecords.add(0, record)
        notifications.removeAll { (it.app == shortApp || it.app == sbn.packageName) && it.title == title }
        notifications.add(0, record)
        while (notifications.size > MAX_NOTIFICATIONS) notifications.removeAt(notifications.lastIndex)
    }

    companion object {
        private const val MAX_NOTIFICATIONS = 30
        private val notifications = CopyOnWriteArrayList<MrRedNotification>()
        private val activeNotificationRecords = CopyOnWriteArrayList<MrRedNotification>()
        @Volatile private var instance: MrRedNotificationListenerService? = null

        fun recentNotifications(context: Context? = null): List<MrRedNotification> {
            instance?.syncActiveNotifications()
            if (instance == null && context != null) {
                requestRebindService(context)
            }
            return notifications.toList()
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
