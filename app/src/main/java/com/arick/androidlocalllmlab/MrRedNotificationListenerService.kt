package com.arick.androidlocalllmlab

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/** Active notification snapshot plus a persistent timestamped history in EventMemoryStore. */
data class MrRedNotification(
    val app: String,
    val title: String,
    val text: String,
    val key: String = "",
    val packageName: String = "",
    val postedAtMillis: Long = System.currentTimeMillis()
)

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
        val title = (extras?.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras?.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString().orEmpty()
        val text = extras?.let { bundle ->
            buildList {
                val big = bundle.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
                val normal = bundle.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                val sub = bundle.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
                val lines = bundle.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString().trim() }.orEmpty()
                val selected = big.ifBlank { normal }
                if (selected.isNotBlank()) add(selected)
                if (sub.isNotBlank() && sub != selected) add(sub)
                lines.filter { it.isNotBlank() && it !in this }.forEach(::add)
            }.joinToString("\\n")
        }.orEmpty()
        ActivityLogger.record(
            applicationContext,
            "NOTIFICATION",
            "posted",
            "${sbn.packageName.substringAfterLast('.')}: $title — $text"
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        activeNotificationRecords.removeAll { it.key == sbn.key }
        EventMemoryStore.markNotificationRemoved(applicationContext, sbn.packageName, sbn.key)
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
            // MessagingStyle notifications can contain multiple messages beyond EXTRA_TEXT.
            val messageBundles = listOf(Notification.EXTRA_MESSAGES, Notification.EXTRA_HISTORIC_MESSAGES)
                .flatMap { key ->
                    extras.getParcelableArray(key)?.mapNotNull { it as? android.os.Bundle }.orEmpty()
                }
            messageBundles.forEach { message ->
                val body = message.getCharSequence("text")?.toString()?.trim().orEmpty()
                val sender = message.getCharSequence("sender")?.toString()?.trim().orEmpty()
                if (body.isNotBlank()) add(if (sender.isNotBlank()) "$sender: $body" else body)
            }
        }.joinToString("\n")
        if (title.isBlank() && text.isBlank()) return
        val shortApp = sbn.packageName.substringAfterLast('.')
        val appLabel = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(shortApp)
        // Persist each observed notification once, including notifications already active when
        // the listener reconnects. The database unique index makes repeated syncs idempotent.
        EventMemoryStore.recordNotification(
            applicationContext, appLabel, sbn.packageName, sbn.key, title, text, sbn.postTime
        )
        val record = MrRedNotification(appLabel, title, text, sbn.key, sbn.packageName, sbn.postTime)
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
            // Rehydrate recent posted notifications from the app-private activity journal
            // so the one-hour window still works after Android kills/restarts the process.
            val persisted = runCatching {
                val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                java.io.File(context?.applicationContext?.filesDir, "mr_red_activity.log")
                    .takeIf { it.isFile }?.readLines()?.mapNotNull { line ->
                        val parts = line.split(" | ", limit = 4)
                        if (parts.size < 4 || parts[1] != "NOTIFICATION" || parts[2] != "posted") return@mapNotNull null
                        val postedAt = formatter.parse(parts[0])?.time ?: return@mapNotNull null
                        if (postedAt < sinceMillis) return@mapNotNull null
                        val detail = parts[3]
                        val app = detail.substringBefore(": ").ifBlank { "Unknown app" }
                        val payload = detail.substringAfter(": ", "")
                        val title = payload.substringBefore(" — ").ifBlank { payload }
                        val body = payload.substringAfter(" — ", "")
                        MrRedNotification(app, title, body, packageName = app, postedAtMillis = postedAt)
                    }.orEmpty()
            }.getOrDefault(emptyList())
            return (notifications.filter { it.postedAtMillis >= sinceMillis } + persisted)
                .distinctBy { "${it.postedAtMillis}|${it.packageName}|${it.title}" }
                .sortedByDescending { it.postedAtMillis }
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
