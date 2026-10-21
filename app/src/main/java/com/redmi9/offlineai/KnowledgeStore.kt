package com.redmi9.offlineai

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

data class TopicEntry(
    val id: Long,
    val topic: String,
    val title: String,
    val summary: String,
    val source: String,
    val createdAt: Long
)

data class QaEntry(
    val id: Long,
    val question: String,
    val answer: String,
    val topic: String,
    val confidence: Double,
    val createdAt: Long
)

data class TripleEntry(
    val id: Long,
    val subject: String,
    val predicate: String,
    val objectVal: String,
    val topic: String,
    val confidence: Double
)

data class TriageEntry(
    val id: Long,
    val rawQuery: String,
    val status: String,
    val notes: String,
    val createdAt: Long
)

/**
 * Local-first SQLite store for offline knowledge, confirmed Q&A,
 * and relational graph triples with SQLite FTS full-text indexing.
 * Fully offline on Android 11 / ARMv7.
 */
class KnowledgeStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE topic_cache (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                topic TEXT NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                source TEXT NOT NULL,
                raw_json TEXT,
                created_at INTEGER NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_topic_cache_topic ON topic_cache(topic);")

        db.execSQL(
            """
            CREATE TABLE confirmed_qa (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                question TEXT NOT NULL,
                answer TEXT NOT NULL,
                topic TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 1.0,
                created_at INTEGER NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_confirmed_qa_question ON confirmed_qa(question);")

        db.execSQL(
            """
            CREATE TABLE knowledge_graph (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                subject TEXT NOT NULL,
                predicate TEXT NOT NULL,
                object_val TEXT NOT NULL,
                topic TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 0.8,
                created_at INTEGER NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_kg_subject ON knowledge_graph(subject);")
        db.execSQL("CREATE INDEX idx_kg_object ON knowledge_graph(object_val);")

        db.execSQL(
            """
            CREATE TABLE triage_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                raw_query TEXT NOT NULL,
                status TEXT NOT NULL,
                notes TEXT,
                created_at INTEGER NOT NULL
            );
            """.trimIndent()
        )

        // SQLite FTS virtual tables for fast, typo-tolerant prefix search
        createFtsTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createFtsTables(db)
            populateFtsTables(db)
        }
    }

    private fun createFtsTables(db: SQLiteDatabase) {
        try {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS topic_fts USING fts4(topic, title, summary);")
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS qa_fts USING fts4(question, answer, topic);")
        } catch (e: Exception) {
            Log.w("KnowledgeStore", "Could not create FTS tables: ${e.message}")
        }
    }

    private fun populateFtsTables(db: SQLiteDatabase) {
        try {
            db.execSQL("INSERT INTO topic_fts(docid, topic, title, summary) SELECT id, topic, title, summary FROM topic_cache;")
            db.execSQL("INSERT INTO qa_fts(docid, question, answer, topic) SELECT id, question, answer, topic FROM confirmed_qa;")
        } catch (e: Exception) {
            Log.w("KnowledgeStore", "Could not populate FTS tables: ${e.message}")
        }
    }

    @Synchronized
    fun saveTopicCache(topic: String, title: String, summary: String, source: String, rawJson: String? = null): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("topic", topic.trim().lowercase())
            put("title", title.trim())
            put("summary", summary.trim())
            put("source", source)
            put("raw_json", rawJson)
            put("created_at", System.currentTimeMillis())
        }
        val rowId = db.insert("topic_cache", null, cv)
        if (rowId > 0) {
            try {
                val ftsCv = ContentValues().apply {
                    put("docid", rowId)
                    put("topic", topic.trim().lowercase())
                    put("title", title.trim())
                    put("summary", summary.trim())
                }
                db.insertWithOnConflict("topic_fts", null, ftsCv, SQLiteDatabase.CONFLICT_REPLACE)
            } catch (e: Exception) {
                Log.w("KnowledgeStore", "Failed to update topic_fts: ${e.message}")
            }
        }
        return rowId
    }

    @Synchronized
    fun findTopicEntries(query: String): List<TopicEntry> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val db = readableDatabase
        val ftsQuery = buildFtsPrefixQuery(cleanQuery)
        val ftsList = mutableListOf<TopicEntry>()

        if (ftsQuery.isNotEmpty()) {
            try {
                val cursor = db.rawQuery(
                    """
                    SELECT t.id, t.topic, t.title, t.summary, t.source, t.created_at
                    FROM topic_cache t
                    JOIN topic_fts f ON t.id = f.docid
                    WHERE topic_fts MATCH ?
                    ORDER BY t.created_at DESC LIMIT 20
                    """.trimIndent(),
                    arrayOf(ftsQuery)
                )
                cursor.use {
                    while (it.moveToNext()) {
                        ftsList.add(
                            TopicEntry(
                                id = it.getLong(0),
                                topic = it.getString(1),
                                title = it.getString(2),
                                summary = it.getString(3),
                                source = it.getString(4),
                                createdAt = it.getLong(5)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("KnowledgeStore", "FTS topic search failed: ${e.message}")
            }
        }

        if (ftsList.isNotEmpty()) return ftsList

        // Fallback keyword search
        val terms = extractKeywords(cleanQuery)
        if (terms.isEmpty()) return emptyList()

        val cursor = db.rawQuery("SELECT id, topic, title, summary, source, created_at FROM topic_cache ORDER BY created_at DESC LIMIT 50", null)
        val list = mutableListOf<Pair<TopicEntry, Int>>()

        cursor.use {
            while (it.moveToNext()) {
                val entry = TopicEntry(
                    id = it.getLong(0),
                    topic = it.getString(1),
                    title = it.getString(2),
                    summary = it.getString(3),
                    source = it.getString(4),
                    createdAt = it.getLong(5)
                )
                val targetText = "${entry.topic} ${entry.title} ${entry.summary}".lowercase()
                val score = terms.count { term -> targetText.contains(term) }
                if (score > 0) {
                    list.add(entry to score)
                }
            }
        }
        return list.sortedByDescending { it.second }.map { it.first }
    }

    @Synchronized
    fun saveConfirmedQa(question: String, answer: String, topic: String, confidence: Double = 1.0): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("question", question.trim())
            put("answer", answer.trim())
            put("topic", topic.trim().lowercase())
            put("confidence", confidence)
            put("created_at", System.currentTimeMillis())
        }
        val rowId = db.insert("confirmed_qa", null, cv)
        if (rowId > 0) {
            try {
                val ftsCv = ContentValues().apply {
                    put("docid", rowId)
                    put("question", question.trim())
                    put("answer", answer.trim())
                    put("topic", topic.trim().lowercase())
                }
                db.insertWithOnConflict("qa_fts", null, ftsCv, SQLiteDatabase.CONFLICT_REPLACE)
            } catch (e: Exception) {
                Log.w("KnowledgeStore", "Failed to update qa_fts: ${e.message}")
            }
        }
        return rowId
    }

    @Synchronized
    fun findConfirmedQa(query: String): List<QaEntry> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val db = readableDatabase

        // 1. Exact match check
        val exactCursor = db.rawQuery(
            "SELECT id, question, answer, topic, confidence, created_at FROM confirmed_qa WHERE LOWER(question) = LOWER(?) LIMIT 5",
            arrayOf(cleanQuery)
        )
        val exactList = mutableListOf<QaEntry>()
        exactCursor.use {
            while (it.moveToNext()) {
                exactList.add(
                    QaEntry(
                        id = it.getLong(0),
                        question = it.getString(1),
                        answer = it.getString(2),
                        topic = it.getString(3),
                        confidence = it.getDouble(4),
                        createdAt = it.getLong(5)
                    )
                )
            }
        }
        if (exactList.isNotEmpty()) return exactList

        // 2. FTS match check
        val ftsQuery = buildFtsPrefixQuery(cleanQuery)
        val ftsList = mutableListOf<QaEntry>()
        if (ftsQuery.isNotEmpty()) {
            try {
                val cursor = db.rawQuery(
                    """
                    SELECT c.id, c.question, c.answer, c.topic, c.confidence, c.created_at
                    FROM confirmed_qa c
                    JOIN qa_fts f ON c.id = f.docid
                    WHERE qa_fts MATCH ?
                    ORDER BY c.confidence DESC, c.created_at DESC LIMIT 20
                    """.trimIndent(),
                    arrayOf(ftsQuery)
                )
                cursor.use {
                    while (it.moveToNext()) {
                        ftsList.add(
                            QaEntry(
                                id = it.getLong(0),
                                question = it.getString(1),
                                answer = it.getString(2),
                                topic = it.getString(3),
                                confidence = it.getDouble(4),
                                createdAt = it.getLong(5)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("KnowledgeStore", "FTS QA search failed: ${e.message}")
            }
        }
        if (ftsList.isNotEmpty()) return ftsList

        // 3. Keyword scoring fallback
        val terms = extractKeywords(cleanQuery)
        val cursor = db.rawQuery("SELECT id, question, answer, topic, confidence, created_at FROM confirmed_qa ORDER BY confidence DESC, created_at DESC", null)
        val results = mutableListOf<Pair<QaEntry, Int>>()

        cursor.use {
            while (it.moveToNext()) {
                val entry = QaEntry(
                    id = it.getLong(0),
                    question = it.getString(1),
                    answer = it.getString(2),
                    topic = it.getString(3),
                    confidence = it.getDouble(4),
                    createdAt = it.getLong(5)
                )
                val cleanQ = entry.question.lowercase()
                val score = terms.count { t -> cleanQ.contains(t) }
                if (score > 0) results.add(entry to score)
            }
        }
        return results.sortedByDescending { it.second }.map { it.first }
    }

    data class FuzzyTopicMatch(
        val entry: TopicEntry,
        val matchedTerm: String,
        val originalTerm: String,
        val distance: Int
    )

    data class FuzzyQaMatch(
        val entry: QaEntry,
        val matchedTerm: String,
        val originalTerm: String,
        val distance: Int
    )

    fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                dp[j] = if (s1[i - 1] == s2[j - 1]) {
                    prev
                } else {
                    1 + minOf(prev, dp[j], dp[j - 1])
                }
                prev = temp
            }
        }
        return dp[s2.length]
    }

    @Synchronized
    fun findFuzzyTopicEntries(query: String): List<FuzzyTopicMatch> {
        val clean = query.trim()
        val terms = extractKeywords(clean)
        if (terms.isEmpty()) return emptyList()

        val db = readableDatabase
        val cursor = db.rawQuery("SELECT id, topic, title, summary, source, created_at FROM topic_cache ORDER BY created_at DESC LIMIT 60", null)
        val results = mutableListOf<FuzzyTopicMatch>()

        cursor.use {
            while (it.moveToNext()) {
                val entry = TopicEntry(
                    id = it.getLong(0),
                    topic = it.getString(1),
                    title = it.getString(2),
                    summary = it.getString(3),
                    source = it.getString(4),
                    createdAt = it.getLong(5)
                )
                val targetWords = Regex("[\\p{L}\\p{N}]{3,}").findAll("${entry.topic} ${entry.title}".lowercase())
                    .map { m -> m.value }
                    .filterNot { w -> w in STOP_WORDS }
                    .toList()

                for (term in terms) {
                    val maxAllowed = when {
                        term.length <= 4 -> 1
                        term.length <= 7 -> 2
                        else -> 3
                    }
                    var bestDist = 99
                    var bestWord = ""
                    for (word in targetWords) {
                        if (Math.abs(term.length - word.length) > maxAllowed) continue
                        val d = levenshteinDistance(term, word)
                        if (d < bestDist) {
                            bestDist = d
                            bestWord = word
                        }
                    }
                    if (bestDist in 1..maxAllowed) {
                        results.add(FuzzyTopicMatch(entry, bestWord, term, bestDist))
                    }
                }
            }
        }
        return results.sortedBy { it.distance }.distinctBy { it.entry.id }
    }

    @Synchronized
    fun findFuzzyQaEntries(query: String): List<FuzzyQaMatch> {
        val clean = query.trim()
        val terms = extractKeywords(clean)
        if (terms.isEmpty()) return emptyList()

        val db = readableDatabase
        val cursor = db.rawQuery("SELECT id, question, answer, topic, confidence, created_at FROM confirmed_qa ORDER BY confidence DESC, created_at DESC LIMIT 60", null)
        val results = mutableListOf<FuzzyQaMatch>()

        cursor.use {
            while (it.moveToNext()) {
                val entry = QaEntry(
                    id = it.getLong(0),
                    question = it.getString(1),
                    answer = it.getString(2),
                    topic = it.getString(3),
                    confidence = it.getDouble(4),
                    createdAt = it.getLong(5)
                )
                val targetWords = Regex("[\\p{L}\\p{N}]{3,}").findAll("${entry.question} ${entry.topic}".lowercase())
                    .map { m -> m.value }
                    .filterNot { w -> w in STOP_WORDS }
                    .toList()

                for (term in terms) {
                    val maxAllowed = when {
                        term.length <= 4 -> 1
                        term.length <= 7 -> 2
                        else -> 3
                    }
                    var bestDist = 99
                    var bestWord = ""
                    for (word in targetWords) {
                        if (Math.abs(term.length - word.length) > maxAllowed) continue
                        val d = levenshteinDistance(term, word)
                        if (d < bestDist) {
                            bestDist = d
                            bestWord = word
                        }
                    }
                    if (bestDist in 1..maxAllowed) {
                        results.add(FuzzyQaMatch(entry, bestWord, term, bestDist))
                    }
                }
            }
        }
        return results.sortedBy { it.distance }.distinctBy { it.entry.id }
    }

    @Synchronized
    fun saveTriple(subject: String, predicate: String, objectVal: String, topic: String, confidence: Double = 0.8): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("subject", subject.trim().lowercase())
            put("predicate", predicate.trim().lowercase())
            put("object_val", objectVal.trim())
            put("topic", topic.trim().lowercase())
            put("confidence", confidence)
            put("created_at", System.currentTimeMillis())
        }
        return db.insert("knowledge_graph", null, cv)
    }

    @Synchronized
    fun findTriples(term: String): List<TripleEntry> {
        val clean = term.trim().lowercase()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, subject, predicate, object_val, topic, confidence FROM knowledge_graph WHERE subject LIKE ? OR object_val LIKE ? OR topic = ? LIMIT 20",
            arrayOf("%$clean%", "%$clean%", clean)
        )
        val list = mutableListOf<TripleEntry>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    TripleEntry(
                        id = it.getLong(0),
                        subject = it.getString(1),
                        predicate = it.getString(2),
                        objectVal = it.getString(3),
                        topic = it.getString(4),
                        confidence = it.getDouble(5)
                    )
                )
            }
        }
        return list
    }

    @Synchronized
    fun logTriage(rawQuery: String, status: String, notes: String? = null): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("raw_query", rawQuery.trim())
            put("status", status)
            put("notes", notes)
            put("created_at", System.currentTimeMillis())
        }
        return db.insert("triage_log", null, cv)
    }

    @Synchronized
    fun getPendingTriageEntries(limit: Int = 10): List<TriageEntry> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, raw_query, status, notes, created_at FROM triage_log WHERE status = 'needs_online_fetch' ORDER BY created_at ASC LIMIT ?",
            arrayOf(limit.toString())
        )
        val list = mutableListOf<TriageEntry>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    TriageEntry(
                        id = it.getLong(0),
                        rawQuery = it.getString(1),
                        status = it.getString(2),
                        notes = it.getString(3) ?: "",
                        createdAt = it.getLong(4)
                    )
                )
            }
        }
        return list
    }

    @Synchronized
    fun updateTriageStatus(id: Long, newStatus: String, notes: String? = null) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("status", newStatus)
            if (notes != null) put("notes", notes)
        }
        db.update("triage_log", cv, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun countPendingTriage(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM triage_log WHERE status = 'needs_online_fetch'", null)
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    @Synchronized
    fun listStoredTopics(): List<String> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT DISTINCT topic FROM topic_cache ORDER BY topic ASC", null)
        val list = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(it.getString(0))
            }
        }
        return list
    }

    private fun buildFtsPrefixQuery(text: String): String {
        val words = Regex("[\\p{L}\\p{N}]{2,}").findAll(text.lowercase())
            .map { it.value }
            .filterNot { it in STOP_WORDS }
            .toList()
        if (words.isEmpty()) return ""
        return words.joinToString(" ") { "$it*" }
    }

    private fun extractKeywords(text: String): Set<String> {
        return Regex("[\\p{L}\\p{N}]{3,}").findAll(text.lowercase())
            .map { it.value }
            .filterNot { it in STOP_WORDS }
            .toSet()
    }

    companion object {
        const val DB_NAME = "mr_red_knowledge.db"
        const val DB_VERSION = 2

        private val STOP_WORDS = setOf(
            "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her", "was",
            "one", "our", "out", "day", "get", "has", "him", "his", "how", "man", "new", "now", "old",
            "see", "two", "way", "who", "boy", "did", "its", "let", "put", "say", "she", "too", "use",
            "what", "when", "where", "which", "with", "about", "could", "would", "should"
        )
    }
    /** Compact memory index for the assistant: reports all local stores without dumping the whole DB into the prompt. */
    @Synchronized
    fun memoryBrief(): String {
        fun count(table: String): Long = runCatching {
            readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        }.getOrDefault(0L)
        return "[Local memory index: confirmed answers=${count("confirmed_qa")}; cached topics=${count("topic_cache")}; relationship facts=${count("knowledge_graph")}; unanswered/research queue=${count("triage_log")}. Search relevant records by topic/question; the database itself is not fully pasted into model context.]"
    }

}
