package com.redmi9.offlineai.skill

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Executes only the small, allow-listed skill DSL. It never evaluates arbitrary code from a skill file. */
class SkillRuntime(private val service: AccessibilityService) {
    private val store = SkillStore(service)

    suspend fun tryExecute(task: String): SkillRunResult {
        val match = store.findMatch(task) ?: return SkillRunResult(false, false, "No installed skill matched this task.")
        val skill = match.first
        val input = match.second
        val trace = mutableListOf<String>()

        for ((index, step) in skill.steps.withIndex()) {
            val value = substitute(step.text, input)
            val values = step.texts.map { substitute(it, input) }
            if (step.requiresConfirmation && isRisky(step.action, value, values)) {
                return SkillRunResult(true, false,
                    "Skill “${skill.name}” is ready for step ${index + 1}, but that step can publish, send, delete, purchase, or otherwise change external data. Review and perform that step manually.", skill.id)
            }

            val result = when (step.action) {
                "launch_app" -> launch(step.packageName)
                "wait" -> { SystemClock.sleep(step.milliseconds); true }
                "read_screen" -> { trace += "Screen: ${service.readActiveWindow(1800)}"; true }
                "click_text" -> service.clickByText(value)
                "click_text_any" -> values.firstOrNull { service.clickByText(it) } != null
                "type_text" -> service.typeText(value)
                "back" -> service.goBack()
                "home" -> service.goHome()
                "respond" -> { trace += value; true }
                else -> false
            }

            if (!result) {
                return SkillRunResult(true, false,
                    "Skill “${skill.name}” stopped at step ${index + 1}. Could not complete action “${step.action}"${if (value.isNotBlank()) " on “$value”" else ""}.${traceText(trace)}",
                    skill.id)
            }
            trace += describe(step, value, values)
            if (step.action != "wait") SystemClock.sleep(350L)
        }

        return SkillRunResult(true, true, "Skill “${skill.name}” completed.${traceText(trace)}", skill.id)
    }

    private fun launch(packageName: String): Boolean {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        return true
    }

    private fun substitute(value: String, input: String): String = value
        .replace("${'$'}{input}", input)
        .replace("${'$'}{task}", input)
        .take(4000)

    private fun isRisky(action: String, text: String, values: List<String>): Boolean {
        val haystack = (listOf(action, text) + values).joinToString(" ").lowercase()
        return action in setOf("click_text", "click_text_any", "type_text") &&
            listOf("post", "publish", "send", "delete", "remove", "buy", "purchase", "pay", "transfer", "comment", "reply", "share", "submit", "confirm", "follow", "message").any(haystack::contains)
    }

    private fun describe(step: SkillStep, value: String, values: List<String>): String = when (step.action) {
        "click_text_any" -> "Clicked one of: ${values.joinToString()}."
        "click_text" -> "Clicked “$value”."
        "type_text" -> "Entered text into the focused field."
        "launch_app" -> "Opened ${step.packageName}."
        "read_screen" -> "Read the visible screen."
        "back" -> "Pressed Back."
        "home" -> "Pressed Home."
        "wait" -> "Waited ${step.milliseconds} ms."
        "respond" -> value
        else -> step.action
    }

    private fun traceText(trace: List<String>): String = if (trace.isEmpty()) "" else "\n\nSteps:\n" + trace.joinToString("\n") { "• $it" }
}
