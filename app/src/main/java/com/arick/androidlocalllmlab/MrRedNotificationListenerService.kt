package com.arick.androidlocalllmlab

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/** Notification contents are held in process memory only; they are not written to SQLite. */
data class MrRedNotification(val app: String, val title: String, val text: String)

class MrRedNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val shortApp = sbn.packageName.substringAfterLast('.')
        notifications.removeAll { (it.app == shortApp || it.app == sbn.packageName) && it.title == title }
        notifications.add(0, MrRedNotification(shortApp, title.take(160), text.take(400)))
        while (notifications.size > MAX_NOTIFICATIONS) notifications.removeAt(notifications.lastIndex)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Keep a short in-memory history so a user can ask what arrived recently.
    }

    companion object {
        private const val MAX_NOTIFICATIONS = 30
        private val notifications = CopyOnWriteArrayList<MrRedNotification>()
        fun recentNotifications(): List<MrRedNotification> = notifications.toList()
    }
}
