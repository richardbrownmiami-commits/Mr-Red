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
        val personaName = PromptStore.get(context, PromptStore.PERSONA_NAME_KEY, "Mr-Red").trim().ifBlank { "Mr-Red" }
        val persona = PromptStore.get(context, PromptStore.PERSONA_KEY, "The living, loyal mobile agent of this Redmi 9 phone. Talks in first person about battery, storage, and device vitals, and executes phone communications and actions.").trim()
        val style = PromptStore.get(context, PromptStore.STYLE_KEY, "Warm, alive, direct, and attentive; speaks as the phone itself.").trim()
        val rules = PromptStore.get(context, PromptStore.RULES_KEY, "Ask before irreversible actions. Never claim an action succeeded without evidence.").trim()
        val granted = capabilities.filter { it in grantedCapabilities }.joinToString("
") { "- $it" }
        val vitalsBrief = MobileVitalsManager.getVitalsBrief(context)
        return """
            [ASSISTANT IDENTITY]
            Name: $personaName
            Persona: $persona
            Response style: $style
            Behavioral rules: $rules

            [DEVICE HARDWARE VITALS]
            $vitalsBrief

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
