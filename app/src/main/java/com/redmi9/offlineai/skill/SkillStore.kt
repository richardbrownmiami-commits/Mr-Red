package com.redmi9.offlineai.skill

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Offline skill registry. Skills are declarative JSON, never executable Kotlin/JVM code. */
class SkillStore(private val context: Context) {
    private val root = File(context.filesDir, "skills")

    init {
        root.mkdirs()
        seedBundledSkills()
    }

    fun list(): List<SkillDefinition> = files().mapNotNull { parse(it) }.sortedBy { it.name.lowercase() }

    fun importJson(json: String): SkillDefinition {
        val skill = parseJson(json)
        validate(skill)
        val safeId = skill.id.lowercase().replace(Regex("[^a-z0-9._-]"), "_").take(64).ifBlank { "skill" }
        File(root, "$safeId.json").writeText(json.trim(), Charsets.UTF_8)
        return skill
    }

    fun delete(id: String): Boolean = File(root, "$id.json").delete()

    fun findMatch(task: String): Pair<SkillDefinition, String>? {
        val normalized = task.trim().lowercase()
        if (normalized.isEmpty()) return null
        val candidates = list().flatMap { skill ->
            skill.triggers.mapNotNull { trigger ->
                val t = trigger.trim().lowercase()
                if (t.isNotEmpty() && normalized.contains(t)) skill to t else null
            }
        }.sortedByDescending { it.second.length }
        val match = candidates.firstOrNull() ?: return null
        val remainder = normalized.removeRange(normalized.indexOf(match.second), normalized.indexOf(match.second) + match.second.length).trim(' ', ':', '-', '"', '\'')
        return match.first to remainder
    }

    private fun seedBundledSkills() {
        val assetDir = runCatching { context.assets.list("skills") }.getOrNull() ?: return
        assetDir.filter { it.endsWith(".json", true) }.forEach { name ->
            val target = File(root, name)
            if (!target.exists()) runCatching { context.assets.open("skills/$name").use { input -> target.outputStream().use { input.copyTo(it) } } }
        }
    }

    private fun files(): List<File> = root.listFiles { file -> file.isFile && file.extension.equals("json", true) }?.toList() ?: emptyList()
    private fun parse(file: File): SkillDefinition? = runCatching { parseJson(file.readText(Charsets.UTF_8)) }.getOrNull()

    private fun parseJson(json: String): SkillDefinition {
        val obj = JSONObject(json)
        val id = obj.optString("id").trim()
        val name = obj.optString("name", id).trim()
        val description = obj.optString("description", "").trim()
        val triggers = obj.optJSONArray("triggers").toStringList()
        val stepsJson = obj.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until stepsJson.length()).map { i ->
            val step = stepsJson.getJSONObject(i)
            SkillStep(
                action = step.optString("action").trim().lowercase(),
                text = step.optString("text", ""),
                texts = step.optJSONArray("texts").toStringList(),
                packageName = step.optString("package", "").trim(),
                milliseconds = step.optLong("ms", 500L).coerceIn(0L, 10000L),
                requiresConfirmation = step.optBoolean("requires_confirmation", false)
            )
        }
        return SkillDefinition(id, name, description, triggers, steps)
    }

    private fun validate(skill: SkillDefinition) {
        require(skill.id.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Skill id must contain only letters, numbers, '.', '_' or '-'" }
        require(skill.name.isNotBlank()) { "Skill name is required" }
        require(skill.triggers.isNotEmpty()) { "At least one trigger is required" }
        require(skill.steps.isNotEmpty() && skill.steps.size <= 50) { "Skill must contain 1-50 steps" }
        skill.steps.forEach { step ->
            require(step.action in ALLOWED_ACTIONS) { "Unsupported skill action: ${step.action}" }
            if (step.action == "launch_app") require(step.packageName.isNotBlank()) { "launch_app requires package" }
            if (step.action == "click_text" || step.action == "type_text" || step.action == "respond") require(step.text.isNotBlank()) { "${step.action} requires text" }
            if (step.action == "click_text_any") require(step.texts.isNotEmpty()) { "click_text_any requires texts" }
        }
    }

    companion object {
        val ALLOWED_ACTIONS = setOf("launch_app", "wait", "read_screen", "click_text", "click_text_any", "type_text", "back", "home", "respond")
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it, "").takeIf(String::isNotBlank) }
}
