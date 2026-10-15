package com.redmi9.offlineai

import android.content.Context

class AiConfiguration(private val context: Context) {
    val prompt: String = load(context, "prompt.md")
    val capabilities: List<String> = load(context, "capabilities.md")
        .lineSequence().map { it.trim() }.filter { it.startsWith("- ") }
        .map { it.removePrefix("- ").substringBefore(":").trim() }
        .filter { it.isNotEmpty() }.toList()

    fun buildSystemContext(modelName: String, grantedCapabilities: Set<String> = capabilities.toSet()): String {
        val activePrompt = PromptStore.prompt(context, prompt).trim()
        val granted = capabilities.filter { it in grantedCapabilities }.joinToString("\n") { "- $it" }
        return """
            [SYSTEM PROMPT]
            $activePrompt

            [CURRENT MODEL]
            $modelName

            [AVAILABLE CAPABILITIES]
            $granted
        """.trimIndent()
    }

    private fun load(context: Context, name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }
}
