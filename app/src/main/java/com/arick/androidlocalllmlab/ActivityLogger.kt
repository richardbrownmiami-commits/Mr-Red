package com.arick.androidlocalllmlab

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Private, on-device diagnostic log. It deliberately records event metadata only:
 * never prompts, generated answers, notification contents, credentials, or file paths.
 */
object ActivityLogger {
    private const val FILE_NAME = "mr_red_activity.log"
    private const val MAX_LINES = 1200

    @Synchronized
    fun record(context: Context, layer: String, action: String, detail: String = "") {
        runCatching {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val safeLayer = clean(layer, 32)
            val safeAction = clean(action, 64)
            val safeDetail = clean(detail, 180)
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            file.appendText("$timestamp | $safeLayer | $safeAction | $safeDetail\n")
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                file.writeText(lines.takeLast(MAX_LINES / 2).joinToString("\n", postfix = "\n"))
            }
        }
    }

    fun summary(context: Context): String {
        val lines = runCatching {
            File(context.applicationContext.filesDir, FILE_NAME)
                .takeIf { it.isFile }?.readLines()?.takeLast(80).orEmpty()
        }.getOrDefault(emptyList())
        if (lines.isEmpty()) {
            return "No activity has been recorded yet. App navigation, model operations, device actions, and inference events will appear here as they occur."
        }

        val recent = lines.takeLast(24)
        val events = recent.map { it.substringAfter(" | ", "").substringAfter(" | ", "").substringBefore(" | ") }
        val hasUi = recent.any { it.contains("| UI |") }
        val hasModel = recent.any { it.contains("| MODEL |") }
        val hasInference = recent.any { it.contains("| INFERENCE |") }
        val hasDevice = recent.any { it.contains("| DEVICE |") }
        val hasError = recent.any { it.contains("| ERROR |") || it.contains("failed", ignoreCase = true) || it.contains("error", ignoreCase = true) }

        return buildString {
            append("Recent activity shows ")
            val areas = mutableListOf<String>()
            if (hasUi) areas += "app navigation and interface actions"
            if (hasModel) areas += "GGUF model loading or runtime management"
            if (hasInference) areas += "question processing and response generation"
            if (hasDevice) areas += "local Android device actions"
            append(if (areas.isEmpty()) "general app activity" else areas.joinToString(", "))
            append(". ")
            append(if (hasError) {
                "At least one recent event indicates a failure or error; review the detailed log for its context."
            } else {
                "No recent event is marked as a failure. This is an activity overview, not a health guarantee."
            })
            append(" The log stays on this device and does not include message text or generated answers.")
        }
    }

    fun recentText(context: Context): String = runCatching {
        File(context.applicationContext.filesDir, FILE_NAME)
            .takeIf { it.isFile }?.readText()?.takeLast(16000)
            ?: "No activity recorded yet."
    }.getOrDefault("Unable to read the activity log.")

    fun clear(context: Context) {
        runCatching {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        }
    }

    private fun clean(value: String, maxLength: Int): String =
        value.replace("\\s+".toRegex(), " ").replace("|", "/")
            .filter { it >= ' ' }.take(maxLength)
}
