package com.redmi9.offlineai

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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
 * and relational graph triples. Fully offline on Android 11 / ARMv7.
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS topic_cache")
        db.execSQL("DROP TABLE IF EXISTS confirmed_qa")
        db.execSQL("DROP TABLE IF EXISTS knowledge_graph")
        db.execSQL("DROP TABLE IF EXISTS triage_log")
        onCreate(db)
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
        return db.insert("topic_cache", null, cv)
    }

    @Synchronized
    fun findTopicEntries(query: String): List<TopicEntry> {
        val terms = extractKeywords(query)
        if (terms.isEmpty()) return emptyList()

        val db = readableDatabase
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
        return db.insert("confirmed_qa", null, cv)
    }

    @Synchronized
    fun findConfirmedQa(query: String): List<QaEntry> {
        val terms = extractKeywords(query)
        val db = readableDatabase
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
                val cleanUser = query.trim().lowercase()
                if (cleanQ == cleanUser) {
                    results.add(entry to 100)
                } else {
                    val score = terms.count { t -> cleanQ.contains(t) }
                    if (score > 0) results.add(entry to score)
                }
            }
        }
        return results.sortedByDescending { it.second }.map { it.first }
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

    private fun extractKeywords(text: String): Set<String> {
        return Regex("[\\p{L}\\p{N}]{3,}").findAll(text.lowercase())
            .map { it.value }
            .filterNot { it in STOP_WORDS }
            .toSet()
    }

    companion object {
        const val DB_NAME = "mr_red_knowledge.db"
        const val DB_VERSION = 1

        private val STOP_WORDS = setOf(
            "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her", "was",
            "one", "our", "out", "day", "get", "has", "him", "his", "how", "man", "new", "now", "old",
            "see", "two", "way", "who", "boy", "did", "its", "let", "put", "say", "she", "too", "use",
            "what", "when", "where", "which", "with", "about", "could", "would", "should"
        )
    }
}