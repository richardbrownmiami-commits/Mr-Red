package com.arick.androidlocalllmlab

import android.content.Context
import android.os.Build
import android.app.ActivityManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Private on-device activity journal and diagnostics. */
object ActivityLogger {
    private const val FILE_NAME = "mr_red_activity.log"
    private const val MAX_LINES = 12000
    private val journalWriter = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mr-red-activity-journal").apply { isDaemon = true }
    }

    fun record(context: Context, layer: String, action: String, detail: String = "") {
        val app = context.applicationContext
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val safeLayer = clean(layer, 32)
        val safeAction = clean(action, 64)
        runCatching {
            if (!(safeLayer == "NOTIFICATION" && (safeAction == "posted" || safeAction == "removed"))) {
                EventMemoryStore.recordEvent(app, safeLayer, safeAction, detail, source = "activity_logger")
            }
            journalWriter.execute {
                runCatching {
                    val file = File(app.filesDir, FILE_NAME)
                    file.appendText("$timestamp | $safeLayer | $safeAction | ${clean(detail, 1000)}\n")
                    val lines = file.readLines()
                    if (lines.size > MAX_LINES) {
                        file.writeText(lines.takeLast(MAX_LINES * 3 / 4).joinToString("\n", postfix = "\n"))
                    }
                }
            }
        }.onFailure {
            EventMemoryStore.recordEvent(app, "ERROR", "activity_journal_enqueue_failed", it.javaClass.simpleName, source = "activity_logger")
        }
    }

    fun summary(context: Context): String {
        val entries = recentText(context).lineSequence().filter { it.isNotBlank() }.toList()
        if (entries.isEmpty()) return "No activity has been recorded yet."
        val counts = entries.mapNotNull { it.split(" | ").getOrNull(1) }.groupingBy { it }.eachCount()
        val failures = entries.count { it.contains(" | ERROR | ") || it.contains("failed", true) }
        return buildString {
            append("Activity summary: ${entries.size} retained events")
            if (counts.isNotEmpty()) append(" across " + counts.entries.joinToString { "${it.key.lowercase(Locale.US)} (${it.value})" })
            append(". ")
            append(if (failures == 0) "No recorded errors in this window." else "$failures error or failure events were recorded.")
        }
    }

    fun diagnosticReport(context: Context): String = runCatching {
        val app = context.applicationContext
        val memory = ActivityManager.MemoryInfo().also { (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        buildString {
            appendLine("===== MR-RED DEVICE DIAGNOSTICS =====")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("CPU cores: ${Runtime.getRuntime().availableProcessors()}")
            appendLine("RAM: total=${memory.totalMem} available=${memory.availMem} low=${memory.lowMemory}")
            appendLine("Heap: max=${Runtime.getRuntime().maxMemory()} total=${Runtime.getRuntime().totalMemory()} free=${Runtime.getRuntime().freeMemory()}")
            appendLine("Memory database: " + EventMemoryStore.counts(app).entries.joinToString { "${it.key}=${it.value}" })
            appendLine("Write health: " + EventMemoryStore.writeDiagnostics().entries.joinToString { "${it.key}=${it.value}" })
            appendLine("\n===== APP ACTIVITY LOG =====")
            appendLine(recentText(app))
        }
    }.getOrElse { "Diagnostic report failed: ${it.javaClass.simpleName}: ${it.message}" }

    fun recentText(context: Context): String = runCatching {
        File(context.applicationContext.filesDir, FILE_NAME).takeIf { it.isFile }?.readText()?.takeLast(30000)
            ?: "No activity recorded yet."
    }.getOrDefault("Unable to read the activity log.")

    fun clear(context: Context) {
        EventMemoryStore.clearEventHistory(context)
        journalWriter.execute { runCatching { File(context.applicationContext.filesDir, FILE_NAME).delete() } }
    }

    private fun clean(value: String, maxLength: Int): String =
        value.replace(Regex("\\s+"), " ").replace("|", "/").filter { it >= ' ' }.take(maxLength)
}
