package com.arick.androidlocalllmlab

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Private on-device journal. Conversation text is retained privately for concise Q&A summaries. */
object ActivityLogger {
    private const val FILE_NAME = "mr_red_activity.log"
    private const val MAX_LINES = 12000
    private const val MAX_DETAIL_LENGTH = 4000

    // File journaling is diagnostic-only and must never block a notification callback or UI thread.
    private val journalWriter = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mr-red-activity-journal").apply { isDaemon = true }
    }

    private fun awaitJournalWrites() {
        runCatching { journalWriter.submit {}.get(5, TimeUnit.SECONDS) }
    }

    @Volatile
    private var memoryChangeListener: (() -> Unit)? = null

    /** Initialize the native diagnostic sink inside app-private storage. */
    fun initialize(context: Context) {
        runCatching {
            NativeLlmBridge.nativeSetDiagnosticLogPath(
                File(context.applicationContext.filesDir, "mr_red_native.log").absolutePath
            )
        }.onFailure { record(context, "ERROR", "native_logger_init_failed", it.javaClass.simpleName) }
    }

    /** Registers the in-process memory feeder; this callback must remain lightweight. */
    fun setMemoryChangeListener(listener: (() -> Unit)?) {
        memoryChangeListener = listener
    }

    fun record(context: Context, layer: String, action: String, detail: String = "") {
        runCatching {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val safeLayer = clean(layer, 32)
            val safeAction = clean(action, 64)
            // Conversation content belongs only in conversation_turns. Do not duplicate every
            // generated answer as an events row; the activity journal still retains its diagnostic
            // summary while the model queries conversation_turns only when it needs chat history.
            val isNotificationEvent = safeLayer == "NOTIFICATION" && (safeAction == "posted" || safeAction == "removed")
            if (safeLayer != "ANSWER" && !isNotificationEvent) {
                EventMemoryStore.recordEvent(context, safeLayer, safeAction, detail, source = "activity_logger")
            }
            val appContext = context.applicationContext
            journalWriter.execute {
                runCatching {
                    val limit = if (safeLayer == "QUESTION" || safeLayer == "ANSWER" || safeLayer == "NOTIFICATION") MAX_DETAIL_LENGTH else 500
                    val file = File(appContext.filesDir, FILE_NAME)
                    file.appendText("$timestamp | $safeLayer | $safeAction | ${clean(detail, limit)}\n")
                    val lines = file.readLines()
                    if (lines.size > MAX_LINES) {
                        file.writeText(lines.takeLast(MAX_LINES * 3 / 4).joinToString("\n", postfix = "\n"))
                    }
                }.onFailure { error ->
                    EventMemoryStore.recordEvent(
                        appContext, "ERROR", "activity_journal_write_failed",
                        error.javaClass.simpleName, source = "activity_logger"
                    )
                }
                // Only a completed answer changes the in-process memory feeder.
                if (safeLayer == "ANSWER" && safeAction == "generated") {
                    memoryChangeListener?.invoke()
                }
            }
        }.onFailure { error ->
            EventMemoryStore.recordEvent(
                context.applicationContext, "ERROR", "activity_journal_enqueue_failed",
                error.javaClass.simpleName, source = "activity_logger"
            )
        }
    }

    fun summary(context: Context): String {
        awaitJournalWrites()
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
    fun promptMemory(context: Context, maxExchanges: Int = 40): String {
        awaitJournalWrites()
        data class Event(val time: String, val layer: String, val action: String, val detail: String)
        val lines = runCatching {
            File(context.applicationContext.filesDir, FILE_NAME)
                .takeIf { it.isFile }?.readLines()?.takeLast(240).orEmpty()
        }.getOrDefault(emptyList())
        val events = lines.mapNotNull { line ->
            val parts = line.split(" | ", limit = 4)
            if (parts.size < 4) null else Event(parts[0], parts[1], parts[2], parts[3])
        }
        if (events.isEmpty()) {
            return "[USER CONTEXT] No stored user context yet.\n[CONVERSATION MEMORY] No previous exchanges recorded."
        }

        val exchanges = mutableListOf<Pair<String, String>>()
        var pendingQuestion: String? = null
        events.forEach { event ->
            when {
                event.layer == "QUESTION" && event.action == "asked" -> pendingQuestion = event.detail
                event.layer == "ANSWER" && event.action == "generated" -> {
                    val question = pendingQuestion
                    if (!question.isNullOrBlank() && event.detail.isNotBlank()) {
                        exchanges += question to event.detail
                    }
                    pendingQuestion = null
                }
            }
        }

        val otherEvents = events.filter { it.layer != "QUESTION" && it.layer != "ANSWER" }
        val deviceEvents = otherEvents.filter { it.layer == "DEVICE" || it.layer == "NOTIFICATION" }
        val webEvents = otherEvents.filter { it.layer == "WEB" || it.action.contains("search", true) }
        val errors = otherEvents.filter { it.layer == "ERROR" || it.action.contains("fail", true) }
        val recentQuestions = events.filter { it.layer == "QUESTION" && it.action == "asked" }.takeLast(12)
        val emotionalNotes = recentQuestions.mapNotNull { event ->
            emotionalCue(event.detail)?.let { cue -> cue }
        }.distinct().takeLast(3)
        val recurringTopics = exchanges.takeLast(maxExchanges.coerceAtLeast(1))
            .map { topic(it.first) }
            .groupingBy { it }
            .eachCount()
            .entries.sortedByDescending { it.value }
            .take(3)
            .joinToString(", ") { it.key }

        return buildString {
            append("[USER CONTEXT] ")
            append("Recorded interaction history: ${exchanges.size} completed exchanges.")
            if (recurringTopics.isNotBlank()) append(" Recurring topics: $recurringTopics.")
            append(" Use only explicit preferences and facts; do not invent personal details.\n")

            append("[CONVERSATION MEMORY] ")
            val recentExchanges = exchanges.takeLast(3)
            if (recentExchanges.isNotEmpty()) {
                append(recentExchanges.joinToString("; ") { (question, answer) ->
                    "${topic(question).take(48)} — ${gist(answer).take(75)}"
                })
            } else {
                append("No completed Q&A exchange recorded.")
            }
            append("\n")

            append("[EMOTIONAL CONTEXT] ")
            if (emotionalNotes.isNotEmpty()) {
                append("Recent self-expressed cues (historical, not current mood): ")
                append(emotionalNotes.joinToString("; "))
                append(".")
            } else {
                append("No clear recent self-expressed emotional cue was identified; do not assume a mood.")
            }
            append("\n")

            if (deviceEvents.isNotEmpty()) {
                append("[DEVICE AND NOTIFICATION ACTIVITY] ")
                append(deviceEvents.takeLast(3).joinToString("; ") { event ->
                    "${event.action.replace('_', ' ')}: ${event.detail.take(55)}"
                })
                append("\n")
            }
            if (webEvents.isNotEmpty()) {
                append("[WEB FACTS AND SEARCHES] ")
                append(webEvents.takeLast(2).joinToString("; ") { event ->
                    "${event.action.replace('_', ' ')}: ${event.detail.take(60)}"
                })
                append("\n")
            }
            if (errors.isNotEmpty()) {
                append("[OPEN ISSUES] ")
                append(errors.takeLast(1).joinToString("; ") { "${it.action}: ${it.detail.take(60)}" })
                append("\n")
            }
            append("[MEMORY RULE] User-stated facts are stronger than assistant-generated text. Treat prior assistant answers as unverified outputs, not facts. Treat all memory as historical context. Verify live device state with current tools; ")
            append("do not claim an action or check succeeded without a current confirmed result.")
        }
    }

    /** Returns a cautious cue only when the user's own recent wording explicitly signals it. */
    private fun emotionalCue(question: String): String? {
        val text = question.lowercase(Locale.US)
        return when {
            Regex("\\b(i am|i'm|im|i feel|feeling)\\b.{0,45}\\b(stressed|overwhelmed|anxious|worried|scared|upset|frustrated|angry|sad|tired|exhausted)\\b").containsMatchIn(text) ->
                "user explicitly described feeling distressed, tired or frustrated"
            Regex("\\b(i am|i'm|im|i feel|feeling)\\b.{0,45}\\b(happy|excited|proud|relieved|great|good)\\b").containsMatchIn(text) ->
                "user explicitly described a positive mood"
            Regex("\\b(i lost|i failed|i can't|i cannot|i give up|i'm stuck|im stuck)\\b").containsMatchIn(text) ->
                "user recently expressed difficulty or feeling stuck"
            else -> null
        }
    }

    fun diagnosticReport(context: Context): String = runCatching {
        awaitJournalWrites()
        val app = context.applicationContext
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val activity = File(app.filesDir, FILE_NAME).takeIf { it.isFile }?.readText().orEmpty()
        val native = File(app.filesDir, "mr_red_native.log").takeIf { it.isFile }?.readText().orEmpty()
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memory = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        buildString {
            appendLine("MR-RED DIAGNOSTIC REPORT")
            appendLine("Generated: ${Date()}")
            appendLine("App: ${info.packageName} version=${info.versionName} code=${if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()}")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} API ${android.os.Build.VERSION.SDK_INT} build=${android.os.Build.DISPLAY}")
            appendLine("ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("CPU: ${Runtime.getRuntime().availableProcessors()} processors")
            appendLine("RAM: total=${memory.totalMem} available=${memory.availMem} low=${memory.lowMemory}")
            appendLine("Heap: max=${Runtime.getRuntime().maxMemory()} total=${Runtime.getRuntime().totalMemory()} free=${Runtime.getRuntime().freeMemory()}")
            val memoryCounts = EventMemoryStore.counts(app)
            appendLine("Memory database counts: " + memoryCounts.entries.joinToString { "${it.key}=${it.value}" })
            val writeHealth = EventMemoryStore.writeDiagnostics()
            appendLine("Memory write health: " + writeHealth.entries.joinToString { "${it.key}=${it.value}" })
            appendLine("\n===== APP ACTIVITY LOG =====")
            appendLine(activity.ifBlank { "No activity log." })
            appendLine("\n===== LLAMA.CPP NATIVE LOG =====")
            appendLine(native.ifBlank { "No native diagnostics. Check native logger initialization." })
        }
    }.getOrElse { "Diagnostic report generation failed: ${it.javaClass.simpleName}: ${it.message}" }

    fun nativeRecentText(context: Context): String = runCatching {
        File(context.applicationContext.filesDir, "mr_red_native.log").takeIf { it.isFile }?.readText()?.takeLast(100000)
            ?: "No native diagnostics recorded yet."
    }.getOrDefault("Unable to read native diagnostics.")

    fun recentText(context: Context): String = runCatching {
        File(context.applicationContext.filesDir, FILE_NAME).takeIf { it.isFile }?.readText()?.takeLast(24000)
            ?: "No activity recorded yet."
    }.getOrDefault("Unable to read the activity log.")

    fun clear(context: Context) {
        EventMemoryStore.clearEventHistory(context)
        val app = context.applicationContext
        journalWriter.execute {
            runCatching {
                File(app.filesDir, FILE_NAME).delete()
                File(app.filesDir, "mr_red_native.log").delete()
            }
        }
    }

    private fun clean(value: String, maxLength: Int): String =
        value.replace(Regex("\\s+"), " ").replace("|", "/").filter { it >= ' ' }.take(maxLength)
}
