package com.arick.androidlocalllmlab

import android.database.sqlite.SQLiteDatabase
import java.util.Locale

data class DatabaseMatchCandidate(
    val table: String,
    val score: Double,
    val matchedSignals: List<String>,
    val availableValues: List<String>
)

data class DatabaseMatchResult(
    val isDatabaseIntent: Boolean,
    val confidence: Double,
    val candidates: List<DatabaseMatchCandidate>,
    val clarification: String? = null,
    val diagnostic: String = ""
)

/** Schema-aware, value-assisted matcher shared by every queryable table. */
internal object DatabaseMatcher {
    private data class TableMeaning(
        val table: String,
        val aliases: Set<String>,
        val columnMeanings: Map<String, Set<String>>,
        val valueColumns: List<String>
    )

    private val meanings = listOf(
        TableMeaning("events",
            setOf("event", "events", "activity", "activities", "happened", "happen", "notification", "notifications", "alert", "alerts", "alarm", "alarms", "reminder", "reminders", "app", "application", "device", "error", "errors", "crash", "setting", "settings", "action", "actions", "history", "recent"),
            mapOf(
                "id" to setOf("id", "identifier", "record id"),
                "event_type" to setOf("type", "category", "kind", "activity", "event"),
                "action" to setOf("action", "operation", "status"),
                "source" to setOf("app", "application", "source", "sender", "person"),
                "package_name" to setOf("package", "application", "app"),
                "occurred_at" to setOf("when", "time", "date", "recent", "latest", "oldest"),
                "title" to setOf("title", "subject", "heading"),
                "body" to setOf("message", "content", "text", "body"),
                "detail" to setOf("details", "description", "event detail"),
                "event_key" to setOf("notification key", "event key", "android key"),
                "importance" to setOf("important", "priority", "urgent"),
                "is_active" to setOf("active", "current", "showing", "dismissed", "removed")
            ), listOf("event_type", "action", "source", "package_name", "importance")
        ),
        TableMeaning("facts",
            setOf("fact", "facts", "memory", "memories", "remember", "remembered", "preference", "preferences", "personal", "know", "saved", "save"),
            mapOf(
                "id" to setOf("id", "identifier", "record id"),
                "fact" to setOf("fact", "memory", "remember", "information", "preference", "know"),
                "source_text" to setOf("original text", "source text", "captured text"),
                "fingerprint" to setOf("fingerprint", "duplicate", "deduplication"),
                "source" to setOf("source", "origin"),
                "created_at" to setOf("when", "date", "created", "saved"),
                "updated_at" to setOf("updated", "changed", "latest")
            ), listOf("source")
        ),
        TableMeaning("conversation_turns",
            setOf("conversation", "conversations", "chat", "chats", "discuss", "discussed", "talk", "talked", "asked", "question", "questions", "answer", "answers", "said", "told", "previous", "history"),
            mapOf(
                "id" to setOf("id", "identifier", "record id"),
                "question" to setOf("asked", "question", "discuss", "talk", "conversation", "chat"),
                "fingerprint" to setOf("fingerprint", "duplicate", "deduplication"),
                "answer" to setOf("answer", "said", "told", "reply", "response"),
                "created_at" to setOf("when", "time", "date", "recent", "latest", "yesterday")
            ), emptyList()
        )
    )

    private val retrievalSignals = setOf("show", "find", "search", "list", "get", "retrieve", "count", "many", "total", "latest", "recent", "previous", "history", "what", "which", "when", "where", "happened", "remember", "discuss", "talk", "asked", "saved", "occurred")
    private val stopWords = setOf("a", "an", "the", "is", "are", "was", "were", "do", "did", "does", "i", "me", "my", "you", "we", "our", "to", "of", "in", "on", "for", "and", "or", "it", "this", "that", "have", "has", "had", "please", "can", "could", "would", "like", "tell", "about")

    fun match(db: SQLiteDatabase, question: String, preferredTable: String? = null, searchAll: Boolean = false): DatabaseMatchResult {
        val normalized = question.lowercase(Locale.ROOT)
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(normalized).map { it.value }
            .filter { it.length > 1 && it !in stopWords }.toSet()
        if (tokens.isEmpty()) return DatabaseMatchResult(false, 0.0, emptyList(), diagnostic = "No meaningful query terms.")

        val schemaTables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val scored = meanings.filter { it.table in schemaTables }.map { meaning ->
            val signals = linkedSetOf<String>()
            var score = 0.0
            meaning.aliases.forEach { alias ->
                val matched = if (alias.contains(' ')) normalized.contains(alias) else tokens.any { termMatches(it, alias) }
                if (matched) { score += 1.8; signals += "table:$alias" }
            }
            meaning.columnMeanings.forEach { (column, aliases) ->
                aliases.forEach { alias ->
                    val matched = if (alias.contains(' ')) normalized.contains(alias) else tokens.any { termMatches(it, alias) }
                    if (matched) { score += 0.65; signals += "column:$column" }
                }
            }
            if (meaning.table == preferredTable) { score += 5.0; signals += "user-selected-table" }
            val matchedValues = discoverValues(db, meaning, tokens)
            if (matchedValues.isNotEmpty()) { score += minOf(1.8, matchedValues.size * 0.45); signals += "stored-value" }
            val values = matchedValues.ifEmpty { discoverAvailableValues(db, meaning) }
            DatabaseMatchCandidate(meaning.table, score, signals.toList(), values)
        }.sortedByDescending { it.score }

        val top = scored.firstOrNull() ?: return DatabaseMatchResult(false, 0.0, emptyList())
        val runnerUp = scored.getOrNull(1)
        val hasRetrievalIntent = retrievalSignals.any { signal -> tokens.any { termMatches(it, signal) } } ||
            meanings.any { m -> m.aliases.any { it.contains(' ') && normalized.contains(it) } }
        val genericDataIntent = tokens.any { it in setOf("data", "database", "records", "everything", "entire") } ||
            normalized.contains("all my history") || normalized.contains("all my information")
        if (hasRetrievalIntent && genericDataIntent && top.score < 1.8 && !searchAll) {
            val allCandidates = scored.map { candidate ->
                candidate.copy(score = 0.0, matchedSignals = candidate.matchedSignals + "generic-database-request")
            }
            val choices = allCandidates.joinToString(" / ") { candidate ->
                "${candidate.table.replace('_', ' ')} (${countRows(db, candidate.table)} records)"
            }
            return DatabaseMatchResult(
                isDatabaseIntent = true,
                confidence = 0.50,
                candidates = allCandidates,
                clarification = "I can search the whole database. Which information should I start with: $choices, or everything?",
                diagnostic = "generic database request; clarification required"
            )
        }
        if (!hasRetrievalIntent || (top.score < 1.8 && !searchAll && preferredTable == null)) {
            return DatabaseMatchResult(false, 0.0, emptyList(), diagnostic = "No reliable database intent match.")
        }

        val margin = top.score - (runnerUp?.score ?: 0.0)
        val signalSupport = (top.matchedSignals.distinct().size * 0.06).coerceAtMost(0.24)
        val separationSupport = if (runnerUp == null) 0.05 else (margin / 8.0).coerceIn(0.0, 0.15)
        val confidence = (0.20 + (top.score / 10.0).coerceAtMost(0.45) + signalSupport + separationSupport).coerceIn(0.0, 1.0)
        val candidates = if (searchAll) scored else scored.filter { it.score >= maxOf(1.2, top.score * 0.58) }.take(3)
        val topRowCount = countRows(db, top.table)
        val temporalTerms = setOf("today", "yesterday", "tomorrow", "recent", "recently", "latest", "oldest", "last", "week", "month", "year", "hour", "hours", "day", "days")
        val querySpecificTokens = tokens.filterNot { token ->
            token in temporalTerms ||
                retrievalSignals.any { termMatches(token, it) } ||
                meanings.any { meaning ->
                    meaning.aliases.any { alias -> !alias.contains(' ') && termMatches(token, alias) } ||
                        meaning.columnMeanings.values.flatten().any { alias -> !alias.contains(' ') && termMatches(token, alias) }
                }
        }
        val hasSpecificText = querySpecificTokens.isNotEmpty()
        val hasTimeConstraint = tokens.any { it in temporalTerms }
        val broadHighVolume = !searchAll && preferredTable == null && topRowCount > 40 &&
            when (top.table) {
                "events" -> !top.matchedSignals.contains("stored-value") && !hasSpecificText
                "conversation_turns" -> !hasSpecificText && !hasTimeConstraint
                "facts" -> !hasSpecificText
                else -> !hasSpecificText && !hasTimeConstraint
            }
        val ambiguous = !searchAll && preferredTable == null && candidates.size > 1 && margin < 1.5
        val lowConfidence = confidence < 0.58 && preferredTable == null && !searchAll && !hasSpecificText
        val clarification = when {
            ambiguous -> {
                val options = candidates.map { c ->
                    "${c.table.replace('_', ' ')} (${countRows(db, c.table)} records)" +
                        c.availableValues.takeIf { it.isNotEmpty() }?.joinToString(prefix = " — ", separator = ", ").orEmpty()
                }
                "I found several possible places to look. Which information do you mean? " +
                    options.joinToString(" / ") + " / Search across all of them"
            }
            broadHighVolume -> {
                val narrowing = if (top.availableValues.isNotEmpty()) {
                    "Available database choices: " + top.availableValues.joinToString(" / ")
                } else {
                    "Narrow it by a topic, person, or date range."
                }
                "I found $topRowCount records in ${top.table.replace('_', ' ')}. $narrowing Or say 'all' to search broadly."
            }
            lowConfidence -> "I'm not confident which stored information you mean. Should I search ${top.table.replace('_', ' ')} ($topRowCount records), or something else?"
            else -> null
        }
        return DatabaseMatchResult(true, confidence, candidates, clarification,
            "top=${top.table}; score=${"%.2f".format(Locale.ROOT, top.score)}; margin=${"%.2f".format(Locale.ROOT, margin)}")
    }

    /** Exact/prefix matches are preferred; edit distance <= 2 tolerates short typing errors. */
    private fun termMatches(query: String, candidate: String): Boolean {
        if (query == candidate) return true
        if (query.length >= 3 && candidate.length >= 3 &&
            (query.startsWith(candidate) || candidate.startsWith(query))) return true
        if (query.length < 5 || candidate.length < 5 || kotlin.math.abs(query.length - candidate.length) > 2) return false

        var previous = IntArray(candidate.length + 1) { it }
        for (i in query.indices) {
            val current = IntArray(candidate.length + 1)
            current[0] = i + 1
            var rowMinimum = current[0]
            for (j in candidate.indices) {
                val substitution = previous[j] + if (query[i] == candidate[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, substitution)
                rowMinimum = minOf(rowMinimum, current[j + 1])
            }
            if (rowMinimum > 2) return false
            previous = current
        }
        return previous[candidate.length] <= 2
    }

    private fun discoverValues(db: SQLiteDatabase, meaning: TableMeaning, tokens: Set<String>): List<String> {
        val output = linkedSetOf<String>()
        for (column in meaning.valueColumns) {
            val sql = "SELECT DISTINCT $column FROM ${meaning.table} WHERE $column IS NOT NULL AND TRIM(CAST($column AS TEXT)) <> '' ORDER BY $column LIMIT 40"
            runCatching {
                db.rawQuery(sql, null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val value = cursor.getString(0)?.trim().orEmpty()
                        val valueTokens = value.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
                        if (value.isNotBlank() && tokens.any { token -> valueTokens.any { termMatches(token, it) } }) {
                            output += "$column: ${value.take(48)}"
                        }
                    }
                }
            }
        }
        return output.take(4).toList()
    }

    /** Small categorical samples for clarification choices; never reads notification/chat bodies. */
    private fun discoverAvailableValues(db: SQLiteDatabase, meaning: TableMeaning): List<String> {
        val output = mutableListOf<String>()
        for (column in meaning.valueColumns.take(4)) {
            runCatching {
                db.rawQuery(
                    "SELECT $column, COUNT(*) AS row_count FROM ${meaning.table} " +
                        "WHERE $column IS NOT NULL AND TRIM(CAST($column AS TEXT)) <> '' " +
                        "GROUP BY $column ORDER BY row_count DESC, $column LIMIT 4",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val value = cursor.getString(0)?.trim().orEmpty()
                        val count = cursor.getInt(1)
                        if (value.isNotBlank()) output += "$column: ${value.take(32)} ($count)"
                    }
                }
            }
            if (output.size >= 6) break
        }
        return output.take(6)
    }

    private fun countRows(db: SQLiteDatabase, table: String): Int = runCatching {
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }.getOrDefault(0)
}

/** A user-selected database value, always supplied to SQLite as a bound parameter. */
data class DatabaseQueryFilter(val table: String, val column: String, val value: String)
