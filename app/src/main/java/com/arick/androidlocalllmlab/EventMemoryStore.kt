package com.arick.androidlocalllmlab

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Persistent, app-private event memory. Collection is asynchronous so notification callbacks
 * and Compose/UI logging never perform SQLite I/O on the main thread.
 *
 * Original notification bodies remain in app-private storage and are visible only through the local database browser.
 */
/** Read-only row models exposed to the on-device database browser. */
data class StoredEventRecord(
    val id: Long,
    val eventType: String,
    val action: String,
    val source: String,
    val detail: String,
    val occurredAtMillis: Long,
    val packageName: String,
    val title: String,
    val body: String,
    val importance: String,
    val isActive: Boolean
)

object EventMemoryStore {
    private const val DATABASE_NAME = "mr_red_memory.db"
    private const val DATABASE_VERSION = 4
    private const val EVENT_RETENTION_DAYS = 90
    private const val RETENTION_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mr-red-memory-db").apply { isDaemon = true }
    }
    private val failedWriteCount = AtomicInteger(0)
    private val lastWriteFailure = AtomicReference("")
    @Volatile private var lastPruneAtMillis = 0L

    private fun noteWriteFailure(error: Throwable) {
        failedWriteCount.incrementAndGet()
        lastWriteFailure.set(error.javaClass.simpleName)
    }

    private fun pruneOldEventsIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastPruneAtMillis < RETENTION_INTERVAL_MILLIS) return
        val db = helper?.writableDatabase ?: return
        val cutoff = now - EVENT_RETENTION_DAYS * 24L * 60 * 60 * 1000
        db.delete("events", "occurred_at < ?", arrayOf(cutoff.toString()))
        lastPruneAtMillis = now
    }

    private fun runWrite(block: () -> Unit) {
        runCatching {
            block()
            pruneOldEventsIfDue()
        }.onFailure(::noteWriteFailure)
    }

    private fun awaitPendingWrites() {
        try {
            writer.submit {}.get(5, TimeUnit.SECONDS)
        } catch (error: Exception) {
            noteWriteFailure(error)
            throw IllegalStateException(
                "Event-memory writes did not drain; refusing to answer from a potentially stale snapshot.",
                error
            )
        }
    }

    /** Non-sensitive health counters for diagnostics and the sampler. */
    fun writeDiagnostics(): Map<String, String> = mapOf(
        "failed_writes" to failedWriteCount.get().toString(),
        "last_failure_type" to lastWriteFailure.get(),
        "event_retention_days" to EVENT_RETENTION_DAYS.toString()
    )

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
            runWrite {
                val values = ContentValues().apply {
                    put("event_type", category.take(48))
                    put("action", action.take(80))
                    put("source", source.take(120))
                    put("detail", detail)
                    put("occurred_at", occurredAtMillis)
                }
                check(database(appContext).writableDatabase.insert("events", null, values) != -1L) { "Event insert returned -1" }
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
        importance: String = "unclassified"
    ) {
        val appContext = context.applicationContext
        writer.execute {
            runWrite {
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
            runWrite {
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
                check(db.insert("events", null, values) != -1L) { "Notification removal insert returned -1" }
             }
        }
    }

    fun clearEventHistory(context: Context) {
        val appContext = context.applicationContext
        writer.execute {
            runWrite { database(appContext).writableDatabase.delete("events", null, null) }
        }
    }

    /** Read-only event records for the database browser. */
    fun browseEvents(
        context: Context,
        section: String,
        query: String = "",
        limit: Int = 500
    ): List<StoredEventRecord> {
        awaitPendingWrites()
        val selection = when (section) {
            "notifications" -> "event_type = 'NOTIFICATION' AND action = 'posted'"
            "actions" -> "(event_type = 'DEVICE' OR (event_type = 'ERROR' AND action = 'device_action_failed'))"
            "activities" -> "event_type <> 'NOTIFICATION' AND event_type <> 'DEVICE' AND NOT (event_type = 'ERROR' AND action = 'device_action_failed')"
            else -> "1 = 0"
        }
        val needle = query.trim()
        val searchClause = if (needle.isBlank()) "" else
            " AND (source LIKE ? OR event_type LIKE ? OR action LIKE ? OR detail LIKE ? OR title LIKE ? OR body LIKE ? OR package_name LIKE ?)"
        val args = if (needle.isBlank()) emptyArray() else Array(7) { "%$needle%" }
        val sql = """
            SELECT id, event_type, action, source, detail, occurred_at, package_name,
                   title, body, importance, is_active
            FROM events
            WHERE $selection$searchClause
            ORDER BY occurred_at DESC, id DESC
            LIMIT ?
        """.trimIndent()
        val bindArgs = args + arrayOf(limit.coerceIn(1, 1000).toString())
        val rows = mutableListOf<StoredEventRecord>()
        database(context.applicationContext).readableDatabase.rawQuery(sql, bindArgs).use { cursor ->
            while (cursor.moveToNext()) {
                rows += StoredEventRecord(
                    id = cursor.getLong(0),
                    eventType = cursor.getString(1),
                    action = cursor.getString(2),
                    source = cursor.getString(3),
                    detail = cursor.getString(4),
                    occurredAtMillis = cursor.getLong(5),
                    packageName = cursor.getString(6),
                    title = cursor.getString(7),
                    body = cursor.getString(8),
                    importance = cursor.getString(9),
                    isActive = cursor.getInt(10) == 1
                )
            }
        }
        return rows
    }

    /** Counts for the event database browser. */
    fun databaseBrowserCounts(context: Context): Map<String, Int> {
        awaitPendingWrites()
        val db = database(context.applicationContext).readableDatabase
        fun count(sql: String): Int = db.rawQuery(sql, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        return mapOf(
            "notifications" to count("SELECT COUNT(*) FROM events WHERE event_type = 'NOTIFICATION' AND action = 'posted'"),
            "actions" to count("SELECT COUNT(*) FROM events WHERE event_type = 'DEVICE' OR (event_type = 'ERROR' AND action = 'device_action_failed')"),
            "activities" to count("SELECT COUNT(*) FROM events WHERE event_type <> 'NOTIFICATION' AND event_type <> 'DEVICE' AND NOT (event_type = 'ERROR' AND action = 'device_action_failed')"),
        )
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
            "active_notifications" to count("events", "event_type = 'NOTIFICATION' AND action = 'posted' AND is_active = 1")
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

        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("DROP INDEX IF EXISTS idx_notification_unique")
                db.execSQL("CREATE UNIQUE INDEX idx_notification_unique ON events(package_name, event_key, occurred_at) WHERE event_type = 'NOTIFICATION' AND action = 'posted'")
            }
        }
    }
}
