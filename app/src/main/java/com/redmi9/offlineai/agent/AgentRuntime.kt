package com.redmi9.offlineai.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Bounded multi-step Android task loop.
 *
 * The local model plans one small action at a time from the current accessible
 * screen. The screen is refreshed after every device action so the next
 * decision is based on the resulting UI, not stale screen text. Actions remain
 * on an explicit allow-list rather than granting arbitrary Android control.
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

        var screen = readScreen(service)
        val trace = mutableListOf<String>()
        val seenActions = mutableSetOf<String>()

        repeat(MAX_DECISIONS) { stepIndex ->
            val systemPrompt = buildSystemPrompt(normalizedTask, screen, trace, stepIndex + 1)
            val response = try {
                provider.complete(
                    AgentLLMRequest(
                        systemPrompt = systemPrompt,
                        messages = listOf(AgentChatMessage("user", normalizedTask)),
                        maxTokens = MAX_GENERATION_TOKENS
                    )
                ).content
            } catch (e: Exception) {
                return "Agent stopped at step ${stepIndex + 1}: ${e.message ?: "local model request failed"}"
            }

            val decision = parseDecision(response)
                ?: return response.ifBlank { "The model returned no action." }
            val action = decision.optString("action").trim().lowercase()

            when (action) {
                "respond" -> {
                    val message = decision.optString("message").ifBlank { "Task finished." }
                    return withTrace(message, trace)
                }

                "read_screen" -> {
                    if (!seenActions.add("read_screen")) {
                        return stopWithTrace("Agent stopped because the model repeated the same screen-read action.", trace)
                    }
                    screen = readScreen(service)
                    trace += "Read the current screen."
                }

                "back" -> {
                    if (!seenActions.add("back")) {
                        return stopWithTrace("Agent stopped to avoid repeating Back navigation.", trace)
                    }
                    val ok = withContext(Dispatchers.Main) { service.goBack() }
                    trace += if (ok) "Pressed Back." else "Android rejected Back."
                    if (!ok) return stopWithTrace("Agent could not navigate Back.", trace)
                    screen = readScreen(service)
                }

                "home" -> {
                    if (!seenActions.add("home")) {
                        return stopWithTrace("Agent stopped to avoid repeatedly leaving the current app.", trace)
                    }
                    val ok = withContext(Dispatchers.Main) { service.goHome() }
                    trace += if (ok) "Pressed Home." else "Android rejected Home."
                    if (!ok) return stopWithTrace("Android rejected Home.", trace)
                    screen = readScreen(service)
                }

                "click_text" -> {
                    val label = decision.optString("text").trim()
                    if (label.isEmpty()) {
                        return stopWithTrace("The model returned click_text without a target label.", trace)
                    }
                    if (isSensitiveLabel(label)) {
                        return stopWithTrace(
                            "Safety stop: “$label” may submit, authorize, delete, purchase, contact someone, or expose information. Complete that step manually.",
                            trace
                        )
                    }
                    val signature = "click:${label.lowercase()}"
                    if (!seenActions.add(signature)) {
                        return stopWithTrace("Agent stopped to avoid clicking “$label” repeatedly.", trace)
                    }
                    val clicked = withContext(Dispatchers.Main) { service.clickByText(label) }
                    trace += if (clicked) "Clicked visible control “$label”." else "No clickable visible control matched “$label”."
                    screen = readScreen(service)
                }

                else -> return stopWithTrace(
                    "The model selected unsupported action “$action”. No further device action was performed.",
                    trace
                )
            }
        }

        return stopWithTrace(
            "Agent reached its safe limit of $MAX_DECISIONS decisions. Review the steps and give it a more specific task if needed.",
            trace
        )
    }

    private suspend fun readScreen(service: AgentAccessibilityService): String =
        withContext(Dispatchers.Main) { service.readActiveWindow(MAX_SCREEN_CHARS) }

    private fun buildSystemPrompt(task: String, screen: String, trace: List<String>, step: Int): String =
        """
        You are Mr-Red, a cautious Android task agent. Work toward the user's explicit task over multiple
        small steps. This is decision $step of $MAX_DECISIONS. Choose exactly one action and output ONLY
        one JSON object. Never claim an action succeeded unless the execution history confirms it.

        Allowed actions:
        {"action":"respond","message":"explain the result or ask the user for a needed step"}
        {"action":"read_screen"}
        {"action":"click_text","text":"exact visible clickable label"}
        {"action":"back"}
        {"action":"home"}

        Safety rules:
        - Use only controls shown in the current visible screen text. Never invent labels.
        - Never click controls involving pay, purchase, send, delete, remove, transfer, submit, confirm,
          install, allow, grant, call, buy, order, share, or other irreversible/private actions.
        - Do not repeat an action already present in the execution history.
        - If the task requires a risky action, missing permission, or unavailable screen information,
          respond with a clear explanation and let the user take over.
        - When the task is done or cannot safely proceed, use respond.
        - Keep the JSON valid and do not wrap it in markdown.

        User task:
        $task

        Execution history:
        ${if (trace.isEmpty()) "(no actions yet)" else trace.joinToString("\n")}

        Current accessible screen text:
        ${screen.take(MAX_SCREEN_CHARS)}
        """.trimIndent()

    private fun parseDecision(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    private fun withTrace(message: String, trace: List<String>): String = buildString {
        append(message)
        if (trace.isNotEmpty()) {
            append("\n\nAgent steps:\n")
            trace.forEachIndexed { i, entry -> append(i + 1).append(". ").append(entry).append('\n') }
        }
    }.trimEnd()

    private fun stopWithTrace(message: String, trace: List<String>): String = withTrace(message, trace)

    private fun isSensitiveLabel(label: String): Boolean {
        val normalized = label.lowercase()
        return listOf(
            "pay", "purchase", "send", "delete", "remove", "transfer", "submit",
            "confirm", "install", "allow", "grant", "call", "buy", "order", "share"
        ).any { normalized.contains(it) }
    }

    private companion object {
        const val MAX_DECISIONS = 5
        const val MAX_SCREEN_CHARS = 1200
        const val MAX_GENERATION_TOKENS = 96
    }
}
