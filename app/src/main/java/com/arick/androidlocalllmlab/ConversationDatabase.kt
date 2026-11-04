package com.arick.androidlocalllmlab

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * SQLite database for durable conversation persistence and rolling memory summaries.
 * Retains user and assistant turns across app restarts.
 */
class ConversationDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "mr_red_conversation.db"
        const val DATABASE_VERSION = 1

        const val TABLE_MESSAGES = "messages"
        const val COL_ID = "id"
        const val COL_ROLE = "role"
        const val COL_CONTENT = "content"
        const val COL_REASONING = "reasoning"
        const val COL_AGENT_TRACE = "agent_trace"
        const val COL_SOURCE_LINKS = "source_links"
        const val COL_CREATED_AT = "created_at"

        const val TABLE_SUMMARY = "summary"
        const val COL_SUMMARY_ID = "id"
        const val COL_SUMMARY_TEXT = "summary_text"
        const val COL_SUMMARY_UPDATED_AT = "updated_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_MESSAGES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ROLE TEXT NOT NULL,
                $COL_CONTENT TEXT NOT NULL,
                $COL_REASONING TEXT NOT NULL DEFAULT '',
                $COL_AGENT_TRACE TEXT NOT NULL DEFAULT '',
                $COL_SOURCE_LINKS TEXT NOT NULL DEFAULT '',
                $COL_CREATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SUMMARY (
                $COL_SUMMARY_ID INTEGER PRIMARY KEY,
                $COL_SUMMARY_TEXT TEXT NOT NULL DEFAULT '',
                $COL_SUMMARY_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Safe migration: preserve existing tables
        onCreate(db)
    }

    @Synchronized
    fun saveMessage(message: ChatMessage) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ROLE, message.role.name)
            put(COL_CONTENT, message.content)
            put(COL_REASONING, message.reasoningContent)

            val traceArray = JSONArray()
            message.agentTrace.forEach { traceArray.put(it.text) }
            put(COL_AGENT_TRACE, traceArray.toString())

            val sourcesArray = JSONArray()
            message.sourceLinks.forEach { source ->
                sourcesArray.put(JSONObject().apply {
                    put("title", source.title)
                    put("url", source.url)
                })
            }
            put(COL_SOURCE_LINKS, sourcesArray.toString())
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        db.insert(TABLE_MESSAGES, null, values)
        pruneOldMessages(keepCount = 300)
    }

    /**
     * Loads the most recent messages (up to [limit]) in chronological order to protect
     * 32-bit Android devices from memory pressure on large chat histories.
     */
    @Synchronized
    fun loadRecentMessages(limit: Int = 100): List<ChatMessage> {
        val db = readableDatabase
        val messages = mutableListOf<ChatMessage>()
        val cursor = db.query(
            TABLE_MESSAGES,
            arrayOf(COL_ROLE, COL_CONTENT, COL_REASONING, COL_AGENT_TRACE, COL_SOURCE_LINKS),
            null,
            null,
            null,
            null,
            "$COL_ID DESC",
            limit.toString()
        )
        cursor.use {
            val roleIdx = it.getColumnIndexOrThrow(COL_ROLE)
            val contentIdx = it.getColumnIndexOrThrow(COL_CONTENT)
            val reasoningIdx = it.getColumnIndexOrThrow(COL_REASONING)
            val traceIdx = it.getColumnIndexOrThrow(COL_AGENT_TRACE)
            val sourcesIdx = it.getColumnIndexOrThrow(COL_SOURCE_LINKS)

            while (it.moveToNext()) {
                val roleStr = it.getString(roleIdx)
                val role = try {
                    MessageRole.valueOf(roleStr)
                } catch (_: Exception) {
                    MessageRole.User
                }
                val content = it.getString(contentIdx) ?: ""
                val reasoning = it.getString(reasoningIdx) ?: ""

                val traceList = mutableListOf<AgentTraceItem>()
                val traceStr = it.getString(traceIdx) ?: ""
                if (traceStr.isNotBlank()) {
                    try {
                        val arr = JSONArray(traceStr)
                        for (i in 0 until arr.length()) {
                            traceList.add(AgentTraceItem(arr.optString(i)))
                        }
                    } catch (_: Exception) {}
                }

                val sourcesList = mutableListOf<SourceLink>()
                val sourcesStr = it.getString(sourcesIdx) ?: ""
                if (sourcesStr.isNotBlank()) {
                    try {
                        val arr = JSONArray(sourcesStr)
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i)
                            if (obj != null) {
                                sourcesList.add(SourceLink(obj.optString("title", ""), obj.optString("url", "")))
                            }
                        }
                    } catch (_: Exception) {}
                }

                messages.add(
                    ChatMessage(
                        role = role,
                        content = content,
                        reasoningContent = reasoning,
                        agentTrace = traceList,
                        sourceLinks = sourcesList
                    )
                )
            }
        }
        messages.reverse()
        return messages
    }

    @Synchronized
    fun loadAllMessages(): List<ChatMessage> = loadRecentMessages(limit = 100)

    @Synchronized
    fun pruneOldMessages(keepCount: Int = 300) {
        val db = writableDatabase
        db.execSQL(
            """
            DELETE FROM $TABLE_MESSAGES WHERE $COL_ID NOT IN (
                SELECT $COL_ID FROM $TABLE_MESSAGES ORDER BY $COL_ID DESC LIMIT $keepCount
            )
            """.trimIndent()
        )
    }

    @Synchronized
    fun getSummary(): String {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SUMMARY,
            arrayOf(COL_SUMMARY_TEXT),
            "$COL_SUMMARY_ID = 1",
            null,
            null,
            null,
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndexOrThrow(COL_SUMMARY_TEXT)
                return it.getString(idx) ?: ""
            }
        }
        return ""
    }

    @Synchronized
    fun saveSummary(summaryText: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SUMMARY_ID, 1)
            put(COL_SUMMARY_TEXT, summaryText)
            put(COL_SUMMARY_UPDATED_AT, System.currentTimeMillis())
        }
        db.insertWithOnConflict(
            TABLE_SUMMARY,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun clearAll() {
        val db = writableDatabase
        db.delete(TABLE_MESSAGES, null, null)
        db.delete(TABLE_SUMMARY, null, null)
    }
}
