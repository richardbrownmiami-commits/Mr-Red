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


    /**
     * Selects only memory relevant to the current question. This runs on a background
     * dispatcher; pending asynchronous writes are drained first so completed turns are visible.
     */
    fun relevantContext(context: Context, question: String, nowMillis: Long = System.currentTimeMillis()): String {
        runCatching { writer.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        val plan = EventMemoryQueryPlanner.plan(question, nowMillis)
        val asksFacts = plan.asksFacts
        val selectedTypes = plan.eventTypes
        val rangeLabel = plan.rangeLabel
        data class Row(val type: String, val action: String, val source: String, val detail: String, val time: Long, val title: String, val body: String, val importance: String, val active: Boolean)
        val rows = mutableListOf<Row>()
        if (selectedTypes.isNotEmpty()) {
            val placeholders = selectedTypes.joinToString(",") { "?" }
            val args = (selectedTypes.toList() + plan.startMillis.toString() + plan.endExclusiveMillis.toString()).toTypedArray()
            database(context).readableDatabase.rawQuery(
                "SELECT event_type, action, source, detail, occurred_at, title, body, importance, is_active FROM events WHERE event_type IN (" + placeholders + ") AND occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at DESC LIMIT 100",
                args
            ).use { cursor ->
                while (cursor.moveToNext()) rows += Row(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4), cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getInt(8) == 1)
            }
        }

        val output = StringBuilder()
        if (asksFacts) {
            database(context).readableDatabase.rawQuery("SELECT fact FROM facts ORDER BY updated_at DESC LIMIT 8", null).use { cursor ->
                val facts = mutableListOf<String>()
                while (cursor.moveToNext()) facts += cursor.getString(0)
                if (facts.isNotEmpty()) output.append("[VERIFIED LONG-TERM FACTS]\n").append(facts.joinToString("\n") { "- " + it }).append('\n')
            }
        }
        if (selectedTypes.isNotEmpty()) {
            output.append("[RETRIEVED MEMORY — ").append(rangeLabel).append("]\n")
            if (rows.isNotEmpty()) {
                val counts = rows.groupingBy { it.type }.eachCount()
                output.append("Event totals: ").append(counts.entries.joinToString(", ") { it.key.lowercase(java.util.Locale.US) + "=" + it.value }).append(".\n")
            }
            val ordered = rows.sortedBy { it.time }
            val conversationRows = ordered.filter { it.type == "QUESTION" || it.type == "ANSWER" }
            if (plan.includeConversation && conversationRows.isNotEmpty()) {
                val exchanges = mutableListOf<Pair<String, String>>()
                var pending: String? = null
                conversationRows.forEach { row ->
                    if (row.type == "QUESTION") pending = row.detail
                    else if (row.type == "ANSWER" && pending != null) { exchanges += pending!! to row.detail; pending = null }
                }
                if (exchanges.isNotEmpty()) {
                    output.append("[RECENT CONVERSATION EXCHANGES — NEWEST FIRST]\n")
                    exchanges.asReversed().take(6).forEach { pair ->
                        output.append("User: ").append(pair.first.take(500)).append("\nMr-Red: ").append(pair.second.take(700)).append('\n')
                    }
                }
            }
            rows.filter { it.type != "QUESTION" && it.type != "ANSWER" }.sortedByDescending { it.time }.take(50).forEach { row ->
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(row.time))
                val detail = if (row.type == "NOTIFICATION" && (row.title.isNotBlank() || row.body.isNotBlank())) {
                    val content = if (row.importance == "important") row.body else row.body.take(180)
                    "app=" + row.source + "; title=" + row.title + "; content=" + content + "; importance=" + row.importance + "; status=" + if (row.active) "active" else "removed"
                } else row.detail.replace(Regex("\\s+"), " ").take(320)
                if (detail.isNotBlank()) output.append(stamp).append(" | ").append(row.type).append(" | ").append(row.action).append(" | ").append(row.source).append(" | ").append(detail).append('\n')
            }
            if (rows.isEmpty()) output.append("No matching stored events in this time range.\n")
        }
        return output.toString().trim()
    }

    /** Canonical recent notification snapshot read directly from SQLite. */
    fun recentNotificationDigest(
        context: Context,
        sinceMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        runCatching { writer.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        data class Notification(val app: String, val title: String, val body: String, val time: Long, val importance: String, val active: Boolean)
        val records = mutableListOf<Notification>()
        database(context).readableDatabase.rawQuery(
            "SELECT source, title, body, occurred_at, importance, is_active FROM events WHERE event_type = 'NOTIFICATION' AND action = 'posted' AND occurred_at BETWEEN ? AND ? ORDER BY occurred_at DESC LIMIT 100",
            arrayOf(sinceMillis.toString(), nowMillis.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) records += Notification(
                cursor.getString(0), cursor.getString(1), cursor.getString(2),
                cursor.getLong(3), cursor.getString(4), cursor.getInt(5) == 1
            )
        }
        if (records.isEmpty()) return ""
        val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return buildString {
            appendLine("ANDROID NOTIFICATION DIGEST — SQLITE RECORDS")
            appendLine("Notifications posted in selected window: " + records.size + ". Times are local device time.")
            records.sortedBy { it.time }.forEach { item ->
                val content = if (item.importance == "important") item.body.ifBlank { item.title } else
                    (item.title + if (item.body.isNotBlank()) " — " + item.body else "").replace(Regex("\\s+"), " ").take(180)
                appendLine("- " + timeFormat.format(java.util.Date(item.time)) + " | " + item.app +
                    " | " + item.importance + " | " + content + " | status=" + if (item.active) "active" else "dismissed")
            }
            append("Prioritise personal communication, missed calls, OTP/security, banking/transactions and time-sensitive alerts. Separate promotions and routine app chatter. Do not infer missing details.")
        }.trim()
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
