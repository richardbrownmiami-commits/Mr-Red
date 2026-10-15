package com.redmi9.offlineai.skill

data class SkillDefinition(
    val id: String,
    val name: String,
    val description: String,
    val triggers: List<String>,
    val steps: List<SkillStep>
)

data class SkillStep(
    val action: String,
    val text: String = "",
    val texts: List<String> = emptyList(),
    val packageName: String = "",
    val milliseconds: Long = 500L,
    val requiresConfirmation: Boolean = false
)

data class SkillRunResult(
    val matched: Boolean,
    val success: Boolean,
    val message: String,
    val skillId: String? = null
)
