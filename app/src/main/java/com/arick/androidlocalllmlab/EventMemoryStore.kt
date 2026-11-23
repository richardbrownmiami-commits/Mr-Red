package com.arick.androidlocalllmlab

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Persistent, app-private event memory. Collection is asynchronous so notification callbacks
 * and Compose/UI logging never perform SQLite I/O on the main thread.
 *
 * Original notification bodies and conversation text stay on-device. Prompt selection is a
 * separate concern; this store does not automatically inject its contents into the model.
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

data class StoredConversationRecord(
    val id: Long,
    val question: String,
    val answer: String,
    val createdAtMillis: Long
)

object EventMemoryStore {
    private const val DATABASE_NAME = "mr_red_memory.db"
    private const val DATABASE_VERSION = 3
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
        importance: String = classifyNotification(packageName, title, body)
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
            runWrite {
                val now = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put("fact", fact)
                    put("source_text", text.take(1200))
                    put("source", "explicit_user_statement")
                    put("created_at", now)
                    put("updated_at", now)
                    put("fingerprint", sha256(fact.lowercase()))
                }
                check(database(appContext).writableDatabase.insertWithOnConflict(
                    "facts", null, values, SQLiteDatabase.CONFLICT_REPLACE
                ) != -1L) { "Fact insert returned -1" }
                val eventValues = ContentValues().apply {
                    put("event_type", "FACT")
                    put("action", "captured")
                    put("source", "user")
                    put("detail", fact)
                    put("occurred_at", now)
                }
                check(database(appContext).writableDatabase.insert("events", null, eventValues) != -1L) { "Fact event insert returned -1" }
            }
        }
    }


    /**
     * Stores a completed chat exchange in the dedicated SQLite conversation table.
     * Exact duplicate pairs are ignored; distinct answers to the same question are retained.
     */
    fun recordConversationExchange(
        context: Context,
        question: String,
        answer: String,
        occurredAtMillis: Long = System.currentTimeMillis()
    ) {
        val q = question.trim().take(4000)
        val a = answer.trim().take(4000)
        if (q.isBlank() || a.isBlank()) return
        val appContext = context.applicationContext
        writer.execute {
            runWrite {
                val db = database(appContext).writableDatabase
                val fingerprint = sha256(
                    q.replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT) +
                        "\\n" +
                        a.replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
                )
                val values = ContentValues().apply {
                    put("question", q)
                    put("answer", a)
                    put("created_at", occurredAtMillis)
                    put("fingerprint", fingerprint)
                }
                db.insertWithOnConflict("conversation_turns", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                db.execSQL(
                    "DELETE FROM conversation_turns WHERE id NOT IN (SELECT id FROM conversation_turns ORDER BY created_at DESC, id DESC LIMIT 200)"
                )
            }
        }
    }

    /** Imports the legacy SharedPreferences transcript once per unique exchange. */
    fun importLegacyConversationHistory(context: Context, exchanges: List<Pair<String, String>>) {
        if (exchanges.isEmpty()) return
        val appContext = context.applicationContext
        writer.execute {
            runWrite {
                val db = database(appContext).writableDatabase
                db.beginTransaction()
                try {
                    exchanges.forEach { (question, answer) ->
                        val q = question.trim().take(4000)
                        val a = answer.trim().take(4000)
                        if (q.isNotBlank() && a.isNotBlank()) {
                            val fingerprint = sha256(
                                q.replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT) +
                                    "\\n" +
                                    a.replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
                            )
                            val values = ContentValues().apply {
                                put("question", q)
                                put("answer", a)
                                put("created_at", System.currentTimeMillis())
                                put("fingerprint", fingerprint)
                            }
                            db.insertWithOnConflict("conversation_turns", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                        }
                    }
                    db.execSQL(
                        "DELETE FROM conversation_turns WHERE id NOT IN (SELECT id FROM conversation_turns ORDER BY created_at DESC, id DESC LIMIT 200)"
                    )
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    /** Reads completed Q&A pairs from SQLite, newest first in storage and chronological in result. */
    fun recentConversationExchanges(context: Context, limit: Int = 12): List<Pair<String, String>> {
        awaitPendingWrites()
        val result = mutableListOf<Pair<String, String>>()
        database(context.applicationContext).readableDatabase.rawQuery(
            "SELECT question, answer FROM conversation_turns ORDER BY created_at DESC, id DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 200).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.getString(0) to cursor.getString(1)
        }
        return result.asReversed()
    }

    /** Clears only the dedicated conversation transcript. */
    fun clearConversationHistory(context: Context) {
        val appContext = context.applicationContext
        writer.execute {
            runWrite { database(appContext).writableDatabase.delete("conversation_turns", null, null) }
        }
    }

    /**
     * Selects only memory relevant to the current question. This runs on a background
     * dispatcher; pending asynchronous writes are drained first so completed turns are visible.
     */
    fun relevantContext(context: Context, question: String, nowMillis: Long = System.currentTimeMillis()): String {
        awaitPendingWrites()
        val plan = EventMemoryQueryPlanner.plan(question, nowMillis)
        val asksFacts = plan.asksFacts
        val selectedTypes = plan.eventTypes
        val rangeLabel = plan.rangeLabel
        data class Row(val type: String, val action: String, val source: String, val detail: String, val time: Long, val title: String, val body: String, val importance: String, val active: Boolean)
        val rows = mutableListOf<Row>()
        // Query each selected category independently so a high-volume source cannot fill
        // one global LIMIT and starve quieter categories such as WEB or PERMISSION.
        selectedTypes.forEach { eventType ->
            val limit = if (selectedTypes.size <= 1) 100 else if (eventType == "NOTIFICATION") 30 else 20
            val args = arrayOf(eventType, plan.startMillis.toString(), plan.endExclusiveMillis.toString())
            database(context).readableDatabase.rawQuery(
                "SELECT event_type, action, source, detail, occurred_at, title, body, importance, is_active FROM events WHERE event_type = ? AND occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at DESC LIMIT " + limit,
                args
            ).use { cursor ->
                while (cursor.moveToNext()) rows += Row(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getLong(4), cursor.getString(5),
                    cursor.getString(6), cursor.getString(7), cursor.getInt(8) == 1
                )
            }
        }
        val balancedRows = rows.sortedByDescending { it.time }

        val output = StringBuilder()
        if (failedWriteCount.get() > 0) {
            output.append("[COLLECTION WARNING: ").append(failedWriteCount.get())
                .append(" database write(s) failed; the stored history may be incomplete. Last failure type: ")
                .append(lastWriteFailure.get()).append("]\n")
        }
        if (asksFacts) {
            database(context).readableDatabase.rawQuery("SELECT fact FROM facts ORDER BY updated_at DESC LIMIT 8", null).use { cursor ->
                val facts = mutableListOf<String>()
                while (cursor.moveToNext()) facts += cursor.getString(0)
                if (facts.isNotEmpty()) output.append("[VERIFIED LONG-TERM FACTS]\n").append(facts.joinToString("\n") { "- " + it }).append('\n')
            }
        }
        if (selectedTypes.isNotEmpty()) {
            output.append("[RETRIEVED MEMORY — ").append(rangeLabel).append("]\n")
            if (balancedRows.isNotEmpty()) {
                val counts = balancedRows.groupingBy { it.type }.eachCount()
                output.append("Event totals: ").append(counts.entries.joinToString(", ") { it.key.lowercase(java.util.Locale.US) + "=" + it.value }).append(".\n")
            }
            val ordered = balancedRows.sortedBy { it.time }
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
            val notificationRows = balancedRows.filter { it.type == "NOTIFICATION" && it.action == "posted" }
            if (notificationRows.isNotEmpty()) {
                val query = question.lowercase(java.util.Locale.ROOT)
                val appNames = listOf(
                    "whatsapp", "telegram", "signal", "gmail", "outlook", "messenger",
                    "instagram", "facebook", "messages", "sms", "phone", "dialer",
                    "bank", "wallet", "paypal", "calendar"
                ).filter(query::contains)
                val categorySlots = linkedMapOf(
                    "security/verification" to listOf("otp", "verification", "security", "login code", "passcode", "fraud"),
                    "banking/transactions" to listOf("bank", "payment", "transaction", "transfer", "credited", "debited", "card"),
                    "missed calls" to listOf("missed call", "missed calls"),
                    "personal messages" to listOf("message", "messages", "sms", "chat", "replied", "messaged", "whatsapp", "telegram", "signal", "messenger"),
                    "delivery/orders" to listOf("delivery", "delivered", "shipment", "parcel", "order"),
                    "calendar/events" to listOf("calendar", "meeting", "appointment", "event")
                )
                val requestedCategories = categorySlots.filter { (_, terms) -> terms.any(query::contains) }
                val senderSlot = Regex("\\bfrom\\s+([\\p{L}][\\p{L}0-9._-]*(?:\\s+[\\p{L}][\\p{L}0-9._-]*){0,2})")
                    .find(query)?.groupValues?.get(1)?.takeIf { it !in setOf("the app", "my phone", "today") }
                val activeOnly = when {
                    listOf("active notification", "currently showing", "still showing", "in notification shade").any(query::contains) -> true
                    listOf("dismissed", "cleared", "removed notification").any(query::contains) -> false
                    else -> null
                }
                val hasSpecificSlots = appNames.isNotEmpty() || requestedCategories.isNotEmpty() ||
                    senderSlot != null || activeOnly != null

                fun compactNotificationText(value: String, maxChars: Int): String {
                    var text = value.replace(Regex("\\s+"), " ").trim()
                    text = text.replace(
                        Regex("^(new message|new notification|you have a notification|notification):?\\s*", RegexOption.IGNORE_CASE),
                        ""
                    )
                    return text.take(maxChars).trim().trimEnd(',', ';', ':', '-', '—')
                }
                fun relativeBucket(time: Long): String {
                    val age = (nowMillis - time).coerceAtLeast(0L)
                    return when {
                        age < 5L * 60 * 1000 -> "just now"
                        age < 60L * 60 * 1000 -> "recently"
                        age < 6L * 60 * 60 * 1000 -> "earlier today"
                        age < 24L * 60 * 60 * 1000 -> "today"
                        else -> "earlier in this period"
                    }
                }
                val matchedNotifications = notificationRows.filter { row ->
                    val combined = (row.source + " " + row.title + " " + row.body).lowercase(java.util.Locale.ROOT)
                    val appMatch = appNames.isEmpty() || appNames.any { app ->
                        row.source.contains(app, ignoreCase = true) || row.title.contains(app, ignoreCase = true) ||
                            (app == "messages" && combined.contains("message"))
                    }
                    val categoryMatch = requestedCategories.isEmpty() || requestedCategories.values.any { terms ->
                        terms.any(combined::contains)
                    }
                    val senderMatch = senderSlot == null || combined.contains(senderSlot)
                    val statusMatch = activeOnly == null || row.active == activeOnly
                    appMatch && categoryMatch && senderMatch && statusMatch
                }.sortedByDescending { it.time }

                output.append("[COMPACT NOTIFICATION CONTEXT — ").append(rangeLabel).append("]\n")
                output.append("Requested slots: ")
                val slotLabels = buildList {
                    if (appNames.isNotEmpty()) add("app=" + appNames.joinToString("/"))
                    if (requestedCategories.isNotEmpty()) add("type=" + requestedCategories.keys.joinToString("/"))
                    if (senderSlot != null) add("sender contains=" + senderSlot)
                    if (activeOnly != null) add("status=" + if (activeOnly) "active" else "dismissed")
                }
                output.append(if (slotLabels.isEmpty()) "general notification overview" else slotLabels.joinToString("; "))
                    .append(".\n")
                output.append("Matching notifications: ").append(matchedNotifications.size)
                    .append(" of ").append(notificationRows.size).append(" stored notification(s).\n")
                matchedNotifications.take(12).forEachIndexed { index, row ->
                    val title = compactNotificationText(row.title, 48)
                    val body = compactNotificationText(row.body, 100)
                    val cue = listOf(title, body).filter { it.isNotBlank() }.distinct().joinToString(": ")
                    output.append("- ")
                        .append(row.source.ifBlank { "Unknown app" }.take(28))
                        .append(if (cue.isNotBlank()) " | " + cue else "")
                        .append(" | ").append(if (row.active) "active" else "dismissed")
                        .append(", ").append(relativeBucket(row.time))
                        .append('\n')
                }
                if (matchedNotifications.isEmpty()) {
                    output.append("No stored notification matched the requested slots. Do not infer missing items.\n")
                }
                if (hasSpecificSlots && matchedNotifications.size < notificationRows.size) {
                    output.append("Other stored notifications were excluded because they did not match the requested slots.\n")
                }
            }
            balancedRows.filter { it.type != "QUESTION" && it.type != "ANSWER" && it.type != "NOTIFICATION" }
                .sortedByDescending { it.time }.forEach { row ->
                    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(row.time))
                    val detail = row.detail.replace(Regex("\\s+"), " ").take(320)
                    if (detail.isNotBlank()) {
                        output.append(stamp).append(" | ").append(row.type).append(" | ")
                            .append(row.action).append(" | ").append(row.source).append(" | ")
                            .append(detail).append('\n')
                    }
                }
            if (balancedRows.isEmpty()) output.append("No matching stored events in this time range.\n")
        }
        return output.toString().trim()
    }

    /**
     * Question-driven notification retrieval for the Kotlin action path.
     * Reads existing rows without changing collection or database schema, compacts matching
     * evidence, and persistently tracks rows already supplied to GGUF.
     */
    fun notificationContextForQuestion(
        context: Context,
        question: String,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        awaitPendingWrites()
        val appContext = context.applicationContext
        val q = question.lowercase(java.util.Locale.ROOT)
        val asksUnread = Regex("\\b(unread|not yet shared|not shared|not shown to you|not included yet|new since you checked)\\b").containsMatchIn(q)
        val asksCount = Regex("\\b(how many|count|counts|total|number of)\\b").containsMatchIn(q)
        val asksExactTime = Regex("\\b(when|what time|timestamp|exact time|date and time|what date|which date)\\b").containsMatchIn(q)
        val asksExactText = Regex("\\b(exact|exactly|verbatim|full|complete|entire|word for word|quote)\\b").containsMatchIn(q)
        val asksImportant = Regex("\\b(important|urgent|attention|missed|anything i need to act on)\\b").containsMatchIn(q)

        val dayMillis = 24L * 60 * 60 * 1000
        var startMillis = nowMillis - dayMillis
        var endExclusive = nowMillis + 1L
        var rangeLabel = "active + last 24 hours"
        var explicitTimeFilter = false
        val duration = Regex("\\b(?:last|past)\\s+(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\s+(minutes?|mins?|hours?|days?|weeks?)\\b").find(q)
        val explicitDate = Regex("\\b(20\\d{2}-\\d{2}-\\d{2})\\b").find(q)?.groupValues?.get(1)
        when {
            explicitDate != null -> {
                val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply {
                    isLenient = false
                    timeZone = java.util.TimeZone.getDefault()
                }
                val parsed = runCatching { formatter.parse(explicitDate)?.time }.getOrNull()
                if (parsed != null) {
                    explicitTimeFilter = true
                    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = parsed }
                    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
                    calendar.set(java.util.Calendar.MINUTE, 0)
                    calendar.set(java.util.Calendar.SECOND, 0)
                    calendar.set(java.util.Calendar.MILLISECOND, 0)
                    startMillis = calendar.timeInMillis
                    calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
                    endExclusive = minOf(nowMillis + 1L, calendar.timeInMillis)
                    rangeLabel = explicitDate
                }
            }
            duration != null -> {
                explicitTimeFilter = true
                val amount = (duration.groupValues[1].toLongOrNull() ?: mapOf(
                    "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L,
                    "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L, "ten" to 10L,
                    "eleven" to 11L, "twelve" to 12L
                )[duration.groupValues[1]] ?: 1L).coerceIn(1, 24L * 90)
                val unit = duration.groupValues[2]
                val span = when {
                    unit.startsWith("min") -> amount * 60_000L
                    unit.startsWith("hour") || unit.startsWith("hr") -> amount * 60L * 60 * 1000
                    unit.startsWith("week") -> amount * 7L * dayMillis
                    else -> amount * dayMillis
                }
                startMillis = nowMillis - span
                rangeLabel = "last " + amount + " " + unit
            }
            Regex("\\b(last|past)\\s+hour\\b").containsMatchIn(q) -> {
                explicitTimeFilter = true
                startMillis = nowMillis - 60L * 60 * 1000
                rangeLabel = "last hour"
            }
            q.contains("yesterday") -> {
                explicitTimeFilter = true
                val calendar = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
                calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
                calendar.set(java.util.Calendar.MINUTE, 0)
                calendar.set(java.util.Calendar.SECOND, 0)
                calendar.set(java.util.Calendar.MILLISECOND, 0)
                calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
                startMillis = calendar.timeInMillis
                calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
                endExclusive = minOf(nowMillis + 1L, calendar.timeInMillis)
                rangeLabel = "yesterday"
            }
            q.contains("today") -> {
                explicitTimeFilter = true
                val calendar = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
                calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
                calendar.set(java.util.Calendar.MINUTE, 0)
                calendar.set(java.util.Calendar.SECOND, 0)
                calendar.set(java.util.Calendar.MILLISECOND, 0)
                startMillis = calendar.timeInMillis
                rangeLabel = "today"
            }
            asksUnread || asksCount || q.contains("all notifications") || q.contains("notification history") -> {
                startMillis = nowMillis - 90L * dayMillis
                rangeLabel = "active + retained history (up to 90 days)"
            }
        }
        data class NotificationRow(
            val id: Long, val app: String, val packageName: String, val title: String,
            val body: String, val time: Long, val active: Boolean
        )
        val records = mutableListOf<NotificationRow>()
        val activeRowsClause = if (explicitTimeFilter) "0" else "is_active = 1"
        database(appContext).readableDatabase.rawQuery(
            """SELECT id, source, package_name, title, body, occurred_at, is_active
               FROM events
               WHERE event_type = 'NOTIFICATION' AND action = 'posted'
                 AND ($activeRowsClause OR (occurred_at >= ? AND occurred_at < ?))
               ORDER BY occurred_at DESC""".trimIndent(),
            arrayOf(startMillis.toString(), endExclusive.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) records += NotificationRow(
                cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                cursor.getString(3), cursor.getString(4), cursor.getLong(5),
                cursor.getInt(6) == 1
            )
        }

        val sharePrefs = appContext.getSharedPreferences("notification_model_context", Context.MODE_PRIVATE)
        val previouslyShared = sharePrefs.getStringSet("shared_notification_ids", emptySet()).orEmpty()
        val unreadRecords = if (asksUnread) records.filterNot { it.id.toString() in previouslyShared } else records

        val appAliases = linkedMapOf(
            "whatsapp" to listOf("whatsapp", "what's app", "what app"),
            "telegram" to listOf("telegram"), "signal" to listOf("signal"),
            "sms" to listOf("sms", "text", "text message", "text messages", "messages app"),
            "gmail" to listOf("gmail"), "outlook" to listOf("outlook"),
            "instagram" to listOf("instagram"), "facebook" to listOf("facebook", "messenger"),
            "youtube" to listOf("youtube", "youtube music"), "spotify" to listOf("spotify"),
            "amazon" to listOf("amazon"), "flipkart" to listOf("flipkart")
        )
        val requestedApps = appAliases.filterValues { aliases -> aliases.any(q::contains) }.keys
        val includeOtherApps = q.contains("other") || q.contains("all apps") || q.contains("all applications")
        val requestedCategories = buildSet {
            if (Regex("\\b(message|messages|sms|text|whatsapp|telegram|chat)\\b").containsMatchIn(q)) add("messages")
            if (Regex("\\b(shopping|shop|order|product|price|discount|deal|offer|purchase)\\b").containsMatchIn(q)) add("shopping")
            if (Regex("\\b(game|games|gaming|play|player|invite)\\b").containsMatchIn(q)) add("games")
            if (Regex("\\b(music|song|track|artist|playlist)\\b").containsMatchIn(q)) add("music")
        }
        val sender = Regex("\\bfrom\\s+([\\p{L}][\\p{L}0-9._-]*)")
            .find(q)?.groupValues?.get(1)?.takeIf {
                it !in setOf(
                    "the last hour", "last hour", "last", "past", "hour", "hours", "day", "days",
                    "week", "weeks", "month", "months", "the app", "my", "mobile", "operator", "sim",
                    "today", "yesterday", "whatsapp", "telegram", "signal", "sms", "text", "messages",
                    "gmail", "outlook"
                )
            }
        val ignoredTerms = setOf(
            "notification", "notifications", "message", "messages", "unread", "important", "urgent",
            "attention", "last", "past", "hour", "hours", "minute", "minutes", "mins", "days",
            "today", "yesterday", "from", "with", "what", "which", "when", "time", "show", "check",
            "read", "list", "see", "view", "get", "have", "has", "any", "all", "the", "and", "for",
            "you", "your", "me", "my", "are", "was", "were", "did", "does", "do", "arrive", "arrived",
            "count", "counts", "total", "many", "number", "how", "other", "others", "exact", "exactly", "verbatim", "full", "complete", "entire", "word", "quote", "please", "about", "there", "that", "this",
            "app", "apps", "text", "sms", "whatsapp", "telegram", "signal", "gmail", "outlook",
            "instagram", "facebook", "messenger", "spotify", "youtube", "amazon", "flipkart",
            "shopping", "shop", "game", "games", "gaming", "music", "song", "track", "artist",
            "product", "products", "price", "prices", "discount", "discounts", "deal", "deals", "offer", "offers", "purchase", "purchases", "order", "orders", "games", "songs", "tracks", "artists", "playlists", "play", "playing", "player", "invite", "level", "match", "clan", "guild", "mobile", "operator", "sim", "my"
        )
        val contentTerms = Regex("[\\p{L}\\p{N}]{3,}").findAll(q).map { it.value }
            .filterNot { it in ignoredTerms }.distinct().toList()

        fun compactText(value: String, maxChars: Int): String = value
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^(new message|new notification|you have a notification|notification):?\\s*", RegexOption.IGNORE_CASE), "")
            .trim().take(maxChars).trimEnd(',', ';', ':', '-', '—')

        fun rowCategory(row: NotificationRow): Set<String> {
            val combined = (row.app + " " + row.packageName + " " + row.title + " " + row.body).lowercase(java.util.Locale.ROOT)
            return buildSet {
                if (Regex("\\b(whatsapp|telegram|signal|sms|mms|message|messenger|chat)\\b").containsMatchIn(combined)) add("messages")
                if (Regex("\\b(shop|shopping|order|product|price|discount|deal|offer|cart|purchase|delivery)\\b").containsMatchIn(combined)) add("shopping")
                if (Regex("\\b(game|games|gaming|player|play|level|match|clan|guild|invite)\\b").containsMatchIn(combined)) add("games")
                if (Regex("\\b(music|song|track|artist|album|playlist|spotify)\\b").containsMatchIn(combined)) add("music")
            }
        }
        fun isAttentionWorthy(row: NotificationRow): Boolean {
            val combined = (row.title + " " + row.body + " " + row.app).lowercase(java.util.Locale.ROOT)
            // Do not treat the app-level heuristic stored in the legacy "importance" column
            // as a real priority signal. Filter only on explicit, answer-relevant content cues.
            return Regex("\\b(otp|verification code|security alert|fraud|missed call|payment due|payment failed|transaction failed|card blocked|meeting|appointment|delivery today|account locked|action required|due today|expires today|renewal required)\\b")
                .containsMatchIn(combined)
        }
        val filtered = unreadRecords.filter { row ->
            val combined = (row.app + " " + row.packageName + " " + row.title + " " + row.body).lowercase(java.util.Locale.ROOT)
            val appMatch = includeOtherApps || requestedApps.isEmpty() || requestedApps.any { app ->
                when (app) {
                    "sms" -> combined.contains("sms") || combined.contains("message") || combined.contains("mms")
                    "facebook" -> combined.contains("facebook") || combined.contains("messenger")
                    "youtube" -> combined.contains("youtube")
                    else -> combined.contains(app)
                }
            } || (requestedApps.isNotEmpty() && records.any { candidate ->
                val appName = candidate.app.lowercase(java.util.Locale.ROOT).trim()
                appName.length >= 3 && q.contains(appName) && row.app.equals(candidate.app, true)
            })
            val categoryMatch = includeOtherApps || requestedCategories.isEmpty() || requestedCategories.any { it in rowCategory(row) }
            val senderMatch = sender == null || combined.contains(sender)
            val termsMatch = contentTerms.isEmpty() || sender != null || contentTerms.any(combined::contains)
            val importantMatch = !asksImportant || isAttentionWorthy(row)
            appMatch && categoryMatch && senderMatch && termsMatch && importantMatch
        }.sortedByDescending { it.time }

        if (filtered.isEmpty()) {
            return when {
                asksUnread && unreadRecords.isEmpty() ->
                    "NOTIFICATION EVIDENCE | " + rangeLabel + "\nNo notifications remain unshared with the model. All matching stored notifications were already supplied in earlier model context." +
                        if (failedWriteCount.get() > 0) "\nCollection warning: stored history may be incomplete." else ""
                asksUnread -> "NOTIFICATION EVIDENCE | " + rangeLabel + "\nNo unshared notifications matched the requested filters." +
                    if (failedWriteCount.get() > 0) "\nCollection warning: stored history may be incomplete." else ""
                else -> "NOTIFICATION EVIDENCE | " + rangeLabel + "\nNo stored notifications matched this request." +
                    if (failedWriteCount.get() > 0) "\nCollection warning: stored history may be incomplete." else ""
            }
        }

        val countOnly = asksCount && contentTerms.isEmpty() && sender == null && !asksImportant
        // Only records whose compact detail lines are actually included are marked shared.
        // Aggregate-only count requests do not mark underlying notification details as seen by GGUF.
        val sharedIds = previouslyShared.toMutableSet()
        if (!countOnly) filtered.take(20).forEach { sharedIds += it.id.toString() }
        sharePrefs.edit().putStringSet(
            "shared_notification_ids",
            sharedIds.sortedByDescending { it.toLongOrNull() ?: 0L }.take(10000).toSet()
        ).apply()

        val appCounts = filtered.groupingBy { it.app.ifBlank { "Unknown app" } }.eachCount()
            .entries.sortedByDescending { it.value }
        val exactFormatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return buildString {
            append("NOTIFICATION EVIDENCE | ").append(rangeLabel)
                .append(" | total=").append(filtered.size).appendLine()
            if (failedWriteCount.get() > 0) {
                appendLine("Collection warning: " + failedWriteCount.get() + " event write(s) failed; stored history may be incomplete.")
            }
            if (asksUnread) appendLine("Unread means not previously supplied to the model; Android read/unread state is not available.")
            if (asksCount || requestedApps.isEmpty()) {
                append("By app: ").append(appCounts.take(12).joinToString(", ") { it.key + "=" + it.value }).appendLine()
            }
            if (!countOnly) {
                filtered.take(20).forEach { row ->
                    val app = row.app.ifBlank { "Unknown app" }.take(32)
                    val title = if (asksExactText) row.title.trim().take(128) else compactText(row.title, 64)
                    val bodyLimit = if (asksExactText) 1200 else if ("shopping" in rowCategory(row)) 180 else 150
                    val body = if (asksExactText) row.body.trim().take(bodyLimit) else compactText(row.body, bodyLimit)
                    val isMessage = "messages" in rowCategory(row)
                    val description = when {
                        isMessage && title.isNotBlank() && body.isNotBlank() -> title + ": " + body
                        title.isNotBlank() && body.isNotBlank() && !title.equals(body, true) -> title + ": " + body
                        title.isNotBlank() -> title
                        else -> body
                    }
                    append("- ").append(app)
                    if (description.isNotBlank()) append(" | ").append(description)
                    if (asksExactTime) {
                        append(" | ").append(exactFormatter.format(java.util.Date(row.time)))
                    } else {
                        val age = (nowMillis - row.time).coerceAtLeast(0L)
                        val ageLabel = when {
                            age < 60_000L -> "just now"
                            age < 60L * 60 * 1000 -> (age / 60_000L).toString() + "m ago"
                            age < dayMillis -> (age / (60L * 60 * 1000)).toString() + "h ago"
                            else -> (age / dayMillis).toString() + "d ago"
                        }
                        append(" | ").append(ageLabel)
                    }
                    append(if (row.active) " | active" else " | dismissed")
                    appendLine()
                }
                if (filtered.size > 20) appendLine("Additional matching notifications omitted from the compact prompt.")
            }
            if (asksImportant) appendLine("Only records with explicit attention cues in their stored content are included.")
            append("Use only these records. Do not infer missing prices, discounts, message text, sender identity, or priority.")
        }
    }

    /** Clears event history while preserving explicitly captured long-term facts. */
    fun clearEventHistory(context: Context) {
        val appContext = context.applicationContext
        writer.execute {
            runWrite { database(appContext).writableDatabase.delete("events", null, null) }
        }
    }

    /** Returns recently learned explicit facts for on-device display in the Memory Hub. */
    fun recentFacts(context: Context, limit: Int = 20): List<String> = runCatching {
        database(context.applicationContext).readableDatabase.rawQuery(
            "SELECT fact FROM facts ORDER BY updated_at DESC LIMIT " + limit, null
        ).use { cursor ->
            val list = mutableListOf<String>()
            while (cursor.moveToNext()) list += cursor.getString(0)
            list
        }
    }.getOrDefault(emptyList())

    /**
     * Executes model-generated SQL against a separate read-only SQLite connection.
     * Only one SELECT statement over the existing allowlisted tables is accepted.
     * Results are returned as compact plain text for the GGUF answer-generation pass.
     */
    fun executeModelReadOnlyQuery(
        context: Context,
        sql: String,
        maxRows: Int = 100,
        maxChars: Int = 18_000
    ): String {
        awaitPendingWrites()
        val statement = sql.trim()
        require(statement.isNotBlank()) { "The model returned an empty SQL query." }
        require(statement.length <= 8_000) { "SQL query is too long." }
        require(Regex("^SELECT\\s+\\*", RegexOption.IGNORE_CASE).containsMatchIn(statement).not()) {
            "Select explicit columns instead of SELECT *."
        }
        require(Regex("^SELECT\\b", RegexOption.IGNORE_CASE).containsMatchIn(statement)) {
            "Only a single SELECT statement is permitted."
        }
        require(!statement.contains(';') && !statement.contains("--") &&
            !statement.contains("/*") && !statement.contains("*/")) {
            "Multiple statements and SQL comments are not permitted."
        }
        val unsafeFunctions = Regex(
            "\\b(load_extension|readfile|writefile|fts3_tokenizer)\\s*\\(",
            RegexOption.IGNORE_CASE
        )
        require(!unsafeFunctions.containsMatchIn(statement)) {
            "The query contains a prohibited SQLite function."
        }

        val allowedTables = setOf("events", "facts", "conversation_turns")
        val tableReferences = Regex(
            "\\b(?:FROM|JOIN)\\s+([A-Za-z_][A-Za-z0-9_]*)",
            RegexOption.IGNORE_CASE
        ).findAll(statement).map { it.groupValues[1].lowercase(java.util.Locale.ROOT) }.toList()
        require(tableReferences.isNotEmpty() && tableReferences.all { it in allowedTables }) {
            "The query must read only from events, facts, or conversation_turns."
        }
        require(!Regex("\\bsqlite_[A-Za-z0-9_]*\\b", RegexOption.IGNORE_CASE).containsMatchIn(statement)) {
            "SQLite internal tables and functions are not available to model queries."
        }
        val fromClause = Regex(
            "\\bFROM\\b(.*?)(?=\\bWHERE\\b|\\bGROUP\\s+BY\\b|\\bORDER\\s+BY\\b|\\bLIMIT\\b|\\bUNION\\b|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(statement)?.groupValues?.getOrNull(1).orEmpty()
        require(!fromClause.contains(',')) {
            "Comma-style table lists are not permitted; use explicit JOIN clauses."
        }

        // Initialize the existing helper/schema on first use before opening the read-only handle.
        database(context).readableDatabase
        val dbFile = context.applicationContext.getDatabasePath(DATABASE_NAME)
        require(dbFile.isFile) { "The Mr Red database could not be initialized." }
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            val boundedSql = "SELECT * FROM ($statement) AS model_result LIMIT ${maxRows.coerceIn(1, 100)}"
            db.rawQuery(boundedSql, null).use { cursor ->
                if (!cursor.moveToFirst()) return "Query completed. No matching records were found."
                val output = StringBuilder()
                output.append("Database query returned rows:\n")
                var rowNumber = 0
                do {
                    rowNumber++
                    output.append("Record ").append(rowNumber).append(":\n")
                    for (column in cursor.columnNames.indices) {
                        if (output.length >= maxChars) break
                        val value = if (cursor.isNull(column)) "NULL" else
                            runCatching { cursor.getString(column) }.getOrDefault("[unreadable value]")
                        output.append(cursor.getColumnName(column)).append(": ")
                            .append(value.take(2_000)).append('\n')
                    }
                    output.append('\n')
                } while (output.length < maxChars && rowNumber < maxRows.coerceIn(1, 100) && cursor.moveToNext())
                if (output.length >= maxChars) output.append("[Results truncated to fit the response budget.]")
                return output.toString().take(maxChars)
            }
        } finally {
            db.close()
        }
    }

    /**
     * Read-only browser query over the existing event table. No records are inserted,
     * updated, deleted, or migrated by these methods.
     */
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

    /** Read-only search over the existing dedicated conversation history table. */
    fun browseConversations(
        context: Context,
        query: String = "",
        limit: Int = 500
    ): List<StoredConversationRecord> {
        awaitPendingWrites()
        val needle = query.trim()
        val where = if (needle.isBlank()) "" else "WHERE question LIKE ? OR answer LIKE ?"
        val args = if (needle.isBlank()) {
            arrayOf(limit.coerceIn(1, 1000).toString())
        } else {
            arrayOf("%$needle%", "%$needle%", limit.coerceIn(1, 1000).toString())
        }
        val rows = mutableListOf<StoredConversationRecord>()
        database(context.applicationContext).readableDatabase.rawQuery(
            "SELECT id, question, answer, created_at FROM conversation_turns $where ORDER BY created_at DESC, id DESC LIMIT ?",
            args
        ).use { cursor ->
            while (cursor.moveToNext()) rows += StoredConversationRecord(
                id = cursor.getLong(0),
                question = cursor.getString(1),
                answer = cursor.getString(2),
                createdAtMillis = cursor.getLong(3)
            )
        }
        return rows
    }

    /** Counts reflect the actual existing tables and event categories. */
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
            "chat" to count("SELECT COUNT(*) FROM conversation_turns")
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
            db.execSQL(
                """CREATE TABLE conversation_turns (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    question TEXT NOT NULL,
                    answer TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    fingerprint TEXT NOT NULL UNIQUE
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX idx_conversation_turns_created ON conversation_turns(created_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("DROP INDEX IF EXISTS idx_notification_unique")
                db.execSQL("CREATE UNIQUE INDEX idx_notification_unique ON events(package_name, event_key, occurred_at) WHERE event_type = 'NOTIFICATION' AND action = 'posted'")
            }
            if (oldVersion < 3) {
                db.execSQL(
                    """CREATE TABLE conversation_turns (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        question TEXT NOT NULL,
                        answer TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        fingerprint TEXT NOT NULL UNIQUE
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX idx_conversation_turns_created ON conversation_turns(created_at DESC)")
            }
        }
    }
}
