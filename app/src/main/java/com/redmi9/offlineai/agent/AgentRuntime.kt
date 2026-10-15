package com.redmi9.offlineai.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Small, bounded tool router for the initial integration milestone.
 * One model decision and at most one device action are allowed per user request.
 */
class AgentRuntime(
    private val provider: LlamaAgentProvider = LlamaAgentProvider()
) {
    suspend fun executeTask(task: String): String {
        val normalizedTask = task.trim()
        if (normalizedTask.isEmpty()) return "Enter a task first."
        if (!provider.isAvailable()) {
            return "Local model unavailable. Load a GGUF model in the app before starting the agent."
        }
        val service = AgentAccessibilityService.current()
            ?: return "Screen automation is disabled. Enable “AI Agent Screen Access” in Android Accessibility settings, then retry."

        val screen = withContext(Dispatchers.Main) {
            service.readActiveWindow(1200)
        }
        val systemPrompt = """
            You are a cautious Android task agent. The user requested a task and supplied the current
            visible screen. Choose exactly one action and output ONLY a JSON object.
            Allowed schemas:
            {"action":"respond","message":"short natural-language answer"}
            {"action":"read_screen"}
            {"action":"click_text","text":"exact visible label"}
            {"action":"back"}
            {"action":"home"}
            Do not invent screen elements. Only click a visible label that directly advances the user's
            explicit request. Never click payment, purchase, send, delete, remove, transfer, submit,
            confirm, install, allow, grant, or call controls; ask the user to do those manually.
            Current visible screen text:
            ${screen.take(1200)}
        """.trimIndent()
        val response = provider.complete(
            AgentLLMRequest(
                systemPrompt = systemPrompt,
                messages = listOf(AgentChatMessage("user", normalizedTask)),
                maxTokens = 96
            )
        ).content
        val decision = parseDecision(response)
            ?: return response.ifBlank { "The model returned no action." }

        return when (decision.optString("action")) {
            "respond" -> decision.optString("message").ifBlank { "No response supplied." }
            "read_screen" -> screen
            "back" -> {
                val ok = withContext(Dispatchers.Main) { service.goBack() }
                if (ok) "Back navigation requested." else "Android rejected the Back action."
            }
            "home" -> {
                val ok = withContext(Dispatchers.Main) { service.goHome() }
                if (ok) "Home navigation requested." else "Android rejected the Home action."
            }
            "click_text" -> {
                val label = decision.optString("text").trim()
                if (label.isEmpty()) return "The model did not provide a target label."
                if (isSensitiveLabel(label)) {
                    return "Safety stop: “$label” may submit, authorize, delete, purchase, or expose information. Complete this step manually."
                }
                val clicked = withContext(Dispatchers.Main) { service.clickByText(label) }
                if (clicked) "Clicked visible control “$label”." else "No clickable visible control matched “$label”."
            }
            else -> "The model selected an unsupported action. No device action was performed."
        }
    }

    private fun parseDecision(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    private fun isSensitiveLabel(label: String): Boolean {
        val normalized = label.lowercase()
        return listOf(
            "pay", "purchase", "send", "delete", "remove", "transfer", "submit",
            "confirm", "install", "allow", "grant", "call", "buy", "order", "share"
        ).any { normalized.contains(it) }
    }
}
