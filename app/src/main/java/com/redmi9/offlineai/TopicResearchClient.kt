package com.redmi9.offlineai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ResearchResult(
    val success: Boolean,
    val topic: String,
    val title: String,
    val summary: String,
    val factsLearned: Int,
    val message: String
)

/**
 * Online topic research client that pulls clean definitions and facts
 * from Wikipedia REST API and DuckDuckGo Instant Answers without requiring
 * external SDKs or API keys. Populates KnowledgeStore for offline use.
 */
class TopicResearchClient(private val store: KnowledgeStore) {

    fun extractResearchTopic(rawQuery: String): String {
        var clean = rawQuery.trim().replace(Regex("[?!.,;:]+$"), "").trim()
        val patterns = listOf(
            Regex("^(what is|what are|who is|who was|who were|where is|where are)\\s+(the\\s+|a\\s+|an\\s+)?", RegexOption.IGNORE_CASE),
            Regex("^(tell me about|explain|define|describe|search for)\\s+(the\\s+|a\\s+|an\\s+)?", RegexOption.IGNORE_CASE),
            Regex("^(how does|how do|how is)\\s+", RegexOption.IGNORE_CASE),
            Regex("\\s+work$", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            clean = clean.replace(pattern, "")
        }
        return clean.trim().ifBlank { rawQuery.trim() }
    }

    suspend fun researchAndStore(topic: String, queueOnFailure: Boolean = true): ResearchResult = withContext(Dispatchers.IO) {
        val cleanTopic = extractResearchTopic(topic)
        if (cleanTopic.isBlank()) {
            return@withContext ResearchResult(false, "", "", "", 0, "Topic cannot be empty.")
        }

        var factsCount = 0
        var mainSummary = ""
        var mainTitle = cleanTopic

        // 1. DuckDuckGo Instant Answer
        val ddgData = fetchDuckDuckGo(cleanTopic)
        if (ddgData != null) {
            val abstractText = ddgData.optString("AbstractText", "").trim()
            val heading = ddgData.optString("Heading", "").trim()
            if (abstractText.isNotEmpty()) {
                mainSummary = abstractText
                if (heading.isNotEmpty()) mainTitle = heading
                store.saveTopicCache(cleanTopic, mainTitle, abstractText, "duckduckgo", ddgData.toString())
                store.saveTriple(cleanTopic, "is", abstractText.take(120), cleanTopic, 0.9)
                factsCount++
            }

            // Extract related topics
            val related = ddgData.optJSONArray("RelatedTopics") ?: JSONArray()
            for (i in 0 until related.length().coerceAtMost(6)) {
                val item = related.optJSONObject(i) ?: continue
                val text = item.optString("Text", "").trim()
                if (text.isNotEmpty()) {
                    store.saveTriple(cleanTopic, "related_to", text.take(150), cleanTopic, 0.75)
                    factsCount++
                }
            }
        }

        // 2. Wikipedia Summary REST API
        val wikiData = fetchWikipediaSummary(cleanTopic)
        if (wikiData != null) {
            val extract = wikiData.optString("extract", "").trim()
            val title = wikiData.optString("title", "").trim()
            val description = wikiData.optString("description", "").trim()

            if (extract.isNotEmpty()) {
                if (mainSummary.isEmpty() || extract.length > mainSummary.length) {
                    mainSummary = extract
                }
                if (title.isNotEmpty()) mainTitle = title

                store.saveTopicCache(cleanTopic, mainTitle, extract, "wikipedia", wikiData.toString())
                if (description.isNotEmpty()) {
                    store.saveTriple(cleanTopic, "description", description, cleanTopic, 0.9)
                    factsCount++
                }
                store.saveTriple(cleanTopic, "summary", extract.take(150), cleanTopic, 0.85)
                factsCount++
            }
        }

        if (mainSummary.isNotEmpty()) {
            store.logTriage(cleanTopic, "resolved", "Fetched $factsCount facts from online sources.")
            ResearchResult(
                success = true,
                topic = cleanTopic,
                title = mainTitle,
                summary = mainSummary,
                factsLearned = factsCount,
                message = "Learned and cached '$cleanTopic' with $factsCount facts for offline answering."
            )
        } else {
            if (queueOnFailure) {
                store.logTriage(cleanTopic, "needs_online_fetch", "No instant summary found via Wikipedia or DuckDuckGo.")
            }
            ResearchResult(
                success = false,
                topic = cleanTopic,
                title = mainTitle,
                summary = "",
                factsLearned = 0,
                message = "Could not find instant definitions for '$cleanTopic'. Saved to triage queue."
            )
        }
    }

    suspend fun processPendingTriage(limit: Int = 5): Int = withContext(Dispatchers.IO) {
        val pending = store.getPendingTriageEntries(limit)
        var successCount = 0
        for (entry in pending) {
            val topic = extractResearchTopic(entry.rawQuery)
            val result = researchAndStore(topic, queueOnFailure = false)
            if (result.success) {
                store.updateTriageStatus(entry.id, "resolved", "Auto-researched: ${result.title} (${result.factsLearned} facts)")
                successCount++
            } else {
                store.updateTriageStatus(entry.id, "attempted_not_found", "No instant summary online.")
            }
        }
        successCount
    }

    private fun fetchDuckDuckGo(query: String): JSONObject? {
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val urlString = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val text = httpGet(urlString) ?: return null
            JSONObject(text)
        }.getOrNull()
    }

    private fun fetchWikipediaSummary(query: String): JSONObject? {
        return runCatching {
            val encoded = URLEncoder.encode(query.replace(' ', '_'), "UTF-8")
            val urlString = "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
            val text = httpGet(urlString) ?: return null
            JSONObject(text)
        }.getOrNull()
    }

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "MrRed-OfflineAssistant/0.2.0 (Android; ARMv7)")
            conn.setRequestProperty("Accept", "application/json")

            if (conn.responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line).append('\n')
                }
                reader.close()
                sb.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w("TopicResearchClient", "HTTP fetch failed for $urlStr: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}