package com.arick.androidlocalllmlab

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/** Notification contents are held in process memory only; they are not written to SQLite. */
data class MrRedNotification(val app: String, val title: String, val text: String)

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
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        processNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Keep a short in-memory history so a user can ask what arrived recently.
    }

    fun syncActiveNotifications() {
        runCatching {
            val active = activeNotifications ?: return
            for (sbn in active) {
                processNotification(sbn)
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
        notifications.removeAll { (it.app == shortApp || it.app == sbn.packageName) && it.title == title }
        notifications.add(0, MrRedNotification(shortApp, title.take(160), text.take(400)))
        while (notifications.size > MAX_NOTIFICATIONS) notifications.removeAt(notifications.lastIndex)
    }

    companion object {
        private const val MAX_NOTIFICATIONS = 30
        private val notifications = CopyOnWriteArrayList<MrRedNotification>()
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

        fun isConnected(): Boolean = instance != null
    }
}
