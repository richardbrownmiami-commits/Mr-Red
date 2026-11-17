package com.arick.androidlocalllmlab

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Private on-device journal. Conversation text is retained privately for concise Q&A summaries. */
object ActivityLogger {
    private const val FILE_NAME = "mr_red_activity.log"
    private const val MAX_LINES = 2400
    private const val MAX_DETAIL_LENGTH = 4000

    @Synchronized
    fun record(context: Context, layer: String, action: String, detail: String = "") {
        runCatching {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val safeLayer = clean(layer, 32)
            val safeAction = clean(action, 64)
            val limit = if (safeLayer == "QUESTION" || safeLayer == "ANSWER") MAX_DETAIL_LENGTH else 500
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            file.appendText("$timestamp | $safeLayer | $safeAction | ${clean(detail, limit)}\n")
            val lines = file.readLines()
            if (lines.size > MAX_LINES) file.writeText(lines.takeLast(MAX_LINES * 3 / 4).joinToString("\n", postfix = "\n"))
        }
    }

    fun summary(context: Context): String {
        val lines = runCatching {
            File(context.applicationContext.filesDir, FILE_NAME).takeIf { it.isFile }?.readLines()?.takeLast(240).orEmpty()
        }.getOrDefault(emptyList())
        if (lines.isEmpty()) return "No activity has been recorded yet. App, model, conversation, device, settings and error events will appear here."

        data class Event(val layer: String, val action: String, val detail: String)
        val events = lines.mapNotNull { line ->
            val parts = line.split(" | ", limit = 4)
            if (parts.size < 4) null else Event(parts[1], parts[2], parts[3])
        }
        val exchanges = mutableListOf<Pair<String, String>>()
        var pending: String? = null
        events.forEach { event ->
            when {
                event.layer == "QUESTION" && event.action == "asked" -> pending = event.detail
                event.layer == "ANSWER" && event.action == "generated" -> {
                    pending?.let { exchanges += it to event.detail }
                    pending = null
                }
            }
        }
        val counts = events.groupingBy { it.layer }.eachCount()
        val other = events.filter { it.layer != "QUESTION" && it.layer != "ANSWER" }.takeLast(24)
        val failures = events.count { it.layer == "ERROR" || it.action.contains("failed", true) }

        return buildString {
            append("Activity summary: ${events.size} events recorded")
            if (counts.isNotEmpty()) append(" across " + counts.entries.joinToString { "${it.key.lowercase(Locale.US)} (${it.value})" })
            append(". ")
            if (exchanges.isNotEmpty()) {
                append("Conversation summary: ")
                append(exchanges.takeLast(8).joinToString(" ") { (question, answer) ->
                    "The discussion covered ${topic(question)}; Mr-Red responded by explaining ${gist(answer)}."
                })
                append(" ")
            } else append("No completed question-and-answer exchange has been recorded yet. ")
            if (other.isNotEmpty()) {
                append("Other activity in time order: ")
                append(other.joinToString("; ") { describe(it.layer, it.action, it.detail) })
                append(". ")
            }
            append(if (failures > 0) "$failures error/failure event(s) were recorded; inspect the detailed log for context."
                else "No error or failure events appear in the retained activity window.")
        }
    }

    private fun topic(question: String): String {
        val cleaned = question.replace(Regex("\\s+"), " ").trim().trimEnd('?', '.', '!')
        val normalized = cleaned.replace(
            Regex("^(please\\s+)?(can you|could you|would you|tell me|explain|what is|what are|who is|who was|where is|when is|why is|how does|how do|how to)\\s+", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        val value = (normalized.ifBlank { cleaned }).take(100)
        return value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }.ifBlank { "a general topic" }
    }

    private fun gist(answer: String): String {
        val cleaned = answer.replace(Regex("\\s+"), " ").trim()
        return Regex("(?<=[.!?])\\s+").split(cleaned).filter { it.isNotBlank() }.take(2).joinToString(" ").take(240)
            .ifBlank { "the requested task or question" }
    }

    private fun describe(layer: String, action: String, detail: String): String {
        val subject = when (layer) {
            "APP" -> "application lifecycle"
            "UI" -> "screen/interface navigation"
            "MODEL" -> "model/runtime operation"
            "INFERENCE" -> "model inference"
            "DEVICE" -> "Android device action"
            "SETTINGS" -> "settings change"
            "ERROR" -> "error"
            else -> layer.lowercase(Locale.US) + " activity"
        }
        return "$subject: ${action.replace('_', ' ')}" + if (detail.isBlank()) "" else " (${detail.take(100)})"
    }

    /**
     * Returns actual recent Q&A text for inference. The Settings summary is intentionally
     * human-readable and lossy; the model needs the underlying exchanges, not a parsed
     * paraphrase of that display string.
     */
    fun promptMemory(context: Context, maxExchanges: Int = 3): String {
        val lines = runCatching {
            File(context.applicationContext.filesDir, FILE_NAME)
                .takeIf { it.isFile }?.readLines()?.takeLast(240).orEmpty()
        }.getOrDefault(emptyList())
        data class Exchange(val question: String, val answer: String)
        val exchanges = mutableListOf<Exchange>()
        var pendingQuestion: String? = null
        lines.forEach { line ->
            val parts = line.split(" | ", limit = 4)
            if (parts.size < 4) return@forEach
            when {
                parts[1] == "QUESTION" && parts[2] == "asked" -> pendingQuestion = parts[3]
                parts[1] == "ANSWER" && parts[2] == "generated" -> {
                    val question = pendingQuestion
                    if (!question.isNullOrBlank() && parts[3].isNotBlank()) {
                        exchanges += Exchange(question.take(140), parts[3].take(220))
                    }
                    pendingQuestion = null
                }
            }
        }
        return exchanges.takeLast(maxExchanges).joinToString("\n") { exchange ->
            "Earlier user: ${exchange.question}\nEarlier Mr-Red: ${exchange.answer}"
        }.take(1000)
    }

    fun recentText(context: Context): String = runCatching {
        File(context.applicationContext.filesDir, FILE_NAME).takeIf { it.isFile }?.readText()?.takeLast(24000)
            ?: "No activity recorded yet."
    }.getOrDefault("Unable to read the activity log.")

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE_NAME).delete() }
    }

    private fun clean(value: String, maxLength: Int): String =
        value.replace(Regex("\\s+"), " ").replace("|", "/").filter { it >= ' ' }.take(maxLength)
}
