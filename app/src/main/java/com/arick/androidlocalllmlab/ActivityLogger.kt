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
     * Builds a compact, paraphrased memory from recent conversation and app activity.
     * This deliberately avoids copying raw questions/answers into the model prompt.
     */
    fun promptMemory(context: Context, maxExchanges: Int = 12): String {
        val lines = runCatching {
            File(context.applicationContext.filesDir, FILE_NAME)
                .takeIf { it.isFile }?.readLines()?.takeLast(240).orEmpty()
        }.getOrDefault(emptyList())
        data class Event(val time: String, val layer: String, val action: String, val detail: String)
        val events = lines.mapNotNull { line ->
            val parts = line.split(" | ", limit = 4)
            if (parts.size < 4) null else Event(parts[0], parts[1], parts[2], parts[3])
        }
        if (events.isEmpty()) return "No previous conversation or app activity is recorded."

        val exchanges = mutableListOf<Pair<String, String>>()
        var pendingQuestion: String? = null
        events.forEach { event ->
            when {
                event.layer == "QUESTION" && event.action == "asked" -> pendingQuestion = event.detail
                event.layer == "ANSWER" && event.action == "generated" -> {
                    val q = pendingQuestion
                    if (!q.isNullOrBlank() && event.detail.isNotBlank()) exchanges += q to event.detail
                    pendingQuestion = null
                }
            }
        }
        val otherEvents = events.filter { it.layer != "QUESTION" && it.layer != "ANSWER" }
        val device = otherEvents.filter { it.layer == "DEVICE" || it.layer == "NOTIFICATION" }
        val web = otherEvents.filter { it.action.contains("search", true) || it.layer == "WEB" }
        val errors = otherEvents.filter { it.layer == "ERROR" || it.action.contains("fail", true) }
        val recent = exchanges.takeLast(maxExchanges)

        return buildString {
            append("Memory summary of recent Mr-Red activity (paraphrased, not a transcript). ")
            if (recent.isNotEmpty()) {
                append("Conversation topics and outcomes: ")
                append(recent.joinToString("; ") { (q, a) ->
                    "${topic(q)}; response outcome: ${gist(a)}"
                })
                append(". ")
            }
            if (device.isNotEmpty()) {
                append("Android/device activity: ")
                append(device.takeLast(12).joinToString("; ") { event ->
                    "${event.action.replace('_', ' ')}" +
                        if (event.detail.isBlank()) "" else " — ${event.detail.take(90)}"
                })
                append(". ")
            }
            if (web.isNotEmpty()) {
                append("Web research: ")
                append(web.takeLast(6).joinToString("; ") { event ->
                    "${event.action.replace('_', ' ')}" +
                        if (event.detail.isBlank()) "" else " — ${event.detail.take(100)}"
                })
                append(". ")
            }
            val appEvents = otherEvents.filter { it.layer !in setOf("DEVICE", "NOTIFICATION", "WEB") &&
                !it.action.contains("search", true) && it.layer != "ERROR" }
            if (appEvents.isNotEmpty()) {
                append("Other app/model/settings activity: ")
                append(appEvents.takeLast(8).joinToString("; ") { event ->
                    "${event.layer.lowercase(Locale.US)}: ${event.action.replace('_', ' ')}" +
                        if (event.detail.isBlank()) "" else " (${event.detail.take(65)})"
                })
                append(". ")
            }
            if (errors.isNotEmpty()) {
                append("Recent errors: ")
                append(errors.takeLast(3).joinToString("; ") { "${it.action}: ${it.detail.take(80)}" })
                append(". ")
            }
            append("Use this as background context, distinguish historical records from live device state, and never claim a device check succeeded unless a current tool result confirms it.")
        }
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
