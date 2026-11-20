package com.arick.androidlocalllmlab

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Persistent, app-private event memory. Collection is asynchronous so notification callbacks
 * and Compose/UI logging never perform SQLite I/O on the main thread.
 *
 * Original notification bodies and conversation text stay on-device. Prompt selection is a
 * separate concern; this store does not automatically inject its contents into the model.
 */
object EventMemoryStore {
    private const val DATABASE_NAME = "mr_red_memory.db"
    private const val DATABASE_VERSION = 2

    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mr-red-memory-db").apply { isDaemon = true }
    }

    @Volatile
    private var helper: MemoryDatabase? = null

    private fun database(context: Context): MemoryDatabase =
        helper ?: synchronized(this) {
            helper ?: MemoryDatabase(context.applicationContext).also { helper = it }
        }

    fun recordEvent(
        context: Context,
        category: String,
        action: String,
        detail: String,
        source: String = "app",
        occurredAtMillis: Long = System.currentTimeMillis()
    ) {
        val appContext = context.applicationContext
        writer.execute {
            runCatching {
                val values = ContentValues().apply {
                    put("event_type", category.take(48))
                    put("action", action.take(80))
                    put("source", source.take(120))
                    put("detail", detail)
                    put("occurred_at", occurredAtMillis)
                }
                database(appContext).writableDatabase.insert("events", null, values)
             }
        }
    }

    fun recordNotification(
        context: Context,
        appLabel: String,
        packageName: String,
        notificationKey: String,
        title: String,
        body: String,
        postedAtMillis: Long,
        importance: String = classifyNotification(packageName, title, body)
    ) {
        val appContext = context.applicationContext
        writer.execute {
            runCatching {
                val db = database(appContext).writableDatabase
                val values = ContentValues().apply {
                    put("event_type", "NOTIFICATION")
                    put("action", "posted")
                    put("source", appLabel.take(160))
                    put("detail", "$title\n$body")
                    put("occurred_at", postedAtMillis)
                    put("package_name", packageName)
                    put("event_key", notificationKey)
                    put("title", title)
                    put("body", body)
                    put("importance", importance)
                    put("is_active", 1)
                }
                db.insertWithOnConflict("events", null, values, SQLiteDatabase.CONFLICT_IGNORE)
             }
        }
    }

    fun markNotificationRemoved(
        context: Context,
        packageName: String,
        notificationKey: String,
        removedAtMillis: Long = System.currentTimeMillis()
    ) {
        val appContext = context.applicationContext
        writer.execute {
            runCatching {
                val db = database(appContext).writableDatabase
                db.execSQL(
                    "UPDATE events SET is_active = 0 WHERE event_type = 'NOTIFICATION' AND action = 'posted' AND package_name = ? AND event_key = ?",
                    arrayOf(packageName, notificationKey)
                )
                val values = ContentValues().apply {
                    put("event_type", "NOTIFICATION")
                    put("action", "removed")
                    put("source", packageName)
                    put("detail", "Notification removed from active shade")
                    put("occurred_at", removedAtMillis)
                    put("package_name", packageName)
                    put("event_key", notificationKey)
                    put("is_active", 0)
                }
                db.insert("events", null, values)
             }
        }
    }

    /**
     * Store only explicit, non-sensitive user-stated facts. Do not infer facts from assistant
     * replies or ordinary questions. Sensitive categories are deliberately excluded.
     */
    fun captureExplicitFact(context: Context, userText: String) {
        val text = userText.trim()
        if (text.isBlank() || containsSensitiveTopic(text)) return

        val patterns = listOf(
            Regex("^(?:please\\s+)?remember\\s+that\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^don't\\s+forget\\s+that\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^my\\s+preference\\s+is\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^i\\s+prefer\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^i\\s+like\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^i\\s+use\\s+(.+)$", RegexOption.IGNORE_CASE)
        )
        val fact = patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
            ?.trim()?.trimEnd('.', '!', '?')
            ?.takeIf { it.length in 3..500 }
            ?: return

        val appContext = context.applicationContext
        writer.execute {
            runCatching {
                val now = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put("fact", fact)
                    put("source_text", text.take(1200))
                    put("source", "explicit_user_statement")
                    put("created_at", now)
                    put("updated_at", now)
                    put("fingerprint", sha256(fact.lowercase()))
                }
                database(appContext).writableDatabase.insertWithOnConflict(
                    "facts", null, values, SQLiteDatabase.CONFLICT_REPLACE
                )
                val eventValues = ContentValues().apply {
                    put("event_type", "FACT")
                    put("action", "captured")
                    put("source", "user")
                    put("detail", fact)
                    put("occurred_at", now)
                }
                database(appContext).writableDatabase.insert("events", null, eventValues)
            }
        }
    }

    /** Clears event history while preserving explicitly captured long-term facts. */
    fun clearEventHistory(context: Context) {
        val appContext = context.applicationContext
        writer.execute {
            runCatching { database(appContext).writableDatabase.delete("events", null, null) }
        }
    }

    /** Read-only diagnostic counts; useful for validating collection without exposing message text. */
    fun counts(context: Context): Map<String, Int> {
        val db = database(context).readableDatabase
        fun count(table: String, where: String? = null): Int {
            val sql = "SELECT COUNT(*) FROM $table" + (where?.let { " WHERE $it" } ?: "")
            db.rawQuery(sql, null).use { cursor -> return if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        }
        return mapOf(
            "events" to count("events"),
            "notifications" to count("events", "event_type = 'NOTIFICATION' AND action = 'posted'"),
            "facts" to count("facts"),
            "active_notifications" to count("events", "event_type = 'NOTIFICATION' AND action = 'posted' AND is_active = 1")
        )
    }

    private fun classifyNotification(packageName: String, title: String, body: String): String {
        val id = packageName.lowercase()
        val combined = "$title $body".lowercase()
        val importantApps = listOf(
            "messaging", "mms", "sms", "whatsapp", "telegram", "signal",
            "gmail", "outlook", "mail", "dialer", "phone", "incall",
            "bank", "banking", "pay", "wallet", "authenticator", "calendar"
        )
        return when {
            importantApps.any(id::contains) -> "important"
            Regex("\\b(otp|verification code|security alert|fraud|transaction|payment received|missed call)\\b")
                .containsMatchIn(combined) -> "important"
            else -> "routine"
        }
    }

    private fun containsSensitiveTopic(value: String): Boolean {
        val lower = value.lowercase()
        val blocked = listOf(
            "medical", "diagnosis", "disease", "medication", "pregnan", "sexual",
            "religion", "religious", "political party", "vote for", "ethnicity",
            "race", "sexual orientation", "union membership"
        )
        return blocked.any(lower::contains)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun prune(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY occurred_at DESC, id DESC LIMIT ?)",
            arrayOf(MAX_EVENT_ROWS)
        )
        db.execSQL(
            "DELETE FROM events WHERE event_type = 'NOTIFICATION' AND action = 'posted' AND id NOT IN (SELECT id FROM events WHERE event_type = 'NOTIFICATION' AND action = 'posted' ORDER BY occurred_at DESC, id DESC LIMIT ?)",
            arrayOf<Any>(MAX_NOTIFICATION_ROWS)
        )
    }

    private class MemoryDatabase(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_type TEXT NOT NULL,
                    action TEXT NOT NULL,
                    source TEXT NOT NULL DEFAULT '',
                    detail TEXT NOT NULL DEFAULT '',
                    occurred_at INTEGER NOT NULL,
                    package_name TEXT NOT NULL DEFAULT '',
                    event_key TEXT NOT NULL DEFAULT '',
                    title TEXT NOT NULL DEFAULT '',
                    body TEXT NOT NULL DEFAULT '',
                    importance TEXT NOT NULL DEFAULT '',
                    is_active INTEGER NOT NULL DEFAULT 0
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX idx_events_type_time ON events(event_type, occurred_at DESC)")
            db.execSQL("CREATE INDEX idx_events_key ON events(package_name, event_key)")
            db.execSQL("CREATE UNIQUE INDEX idx_notification_unique ON events(package_name, event_key, occurred_at) WHERE event_type = 'NOTIFICATION' AND action = 'posted'")
            db.execSQL("CREATE INDEX idx_events_active ON events(event_type, is_active, occurred_at DESC)")
            db.execSQL(
                """CREATE TABLE facts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    fact TEXT NOT NULL,
                    source_text TEXT NOT NULL,
                    source TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    fingerprint TEXT NOT NULL UNIQUE
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX idx_facts_updated ON facts(updated_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("DROP INDEX IF EXISTS idx_notification_unique")
                db.execSQL("CREATE UNIQUE INDEX idx_notification_unique ON events(package_name, event_key, occurred_at) WHERE event_type = 'NOTIFICATION' AND action = 'posted'")
            }
        }
    }
}
