package com.arick.androidlocalllmlab

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Small, explicit-consent long-term memory store.
 *
 * Facts are written only when the user says "remember ...". The database is local to
 * the app sandbox; it is not uploaded or sent to the model provider. Retrieval uses
 * lightweight lexical matching so it works on low-memory devices without embeddings.
 */
class PersistentMemoryStore(context: Context) :
    SQLiteOpenHelper(context, "mr_red_memory.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE memory_facts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                fact TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX memory_facts_created_idx ON memory_facts(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the initial independent memory database.
    }

    @Synchronized
    fun remember(fact: String) {
        val clean = fact.trim().replace(Regex("\\s+"), " ").take(240)
        if (clean.isBlank()) return
        val values = ContentValues().apply {
            put("fact", clean)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "memory_facts", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * Return only facts sharing meaningful words with the current request.
     * This avoids injecting the entire memory store into every prompt.
     */
    @Synchronized
    fun findRelevant(query: String, limit: Int = 5): List<String> {
        val stopWords = setOf(
            "what", "when", "where", "which", "who", "whom", "whose", "how",
            "about", "remember", "recall", "tell", "show", "know", "does",
            "with", "that", "this", "from", "have", "your", "mine", "please"
        )
        val terms = Regex("[a-z0-9]{3,}", RegexOption.IGNORE_CASE)
            .findAll(query.lowercase())
            .map { it.value }
            .filterNot { it in stopWords }
            .toSet()
        if (terms.isEmpty()) return emptyList()

        val facts = mutableListOf<String>()
        readableDatabase.query(
            "memory_facts",
            arrayOf("fact"),
            null, null, null, null,
            "created_at DESC",
            "100"
        ).use { cursor ->
            while (cursor.moveToNext()) facts += cursor.getString(0)
        }
        return facts.mapNotNull { fact ->
            val normalized = fact.lowercase()
            val score = terms.count { term -> normalized.contains(term) }
            if (score > 0) fact to score else null
        }.sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("memory_facts", null, null)
    }
}
