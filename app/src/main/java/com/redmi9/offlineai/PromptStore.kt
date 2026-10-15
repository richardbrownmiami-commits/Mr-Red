package com.redmi9.offlineai

import android.content.Context

object PromptStore {
    private const val PREFS = "assistant_prompts"
    private const val PROMPT = "prompt"

    fun get(context: Context, key: String, fallback: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, fallback) ?: fallback

    fun save(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    fun prompt(context: Context, fallback: String) = get(context, PROMPT, fallback)

    const val PROMPT_KEY = PROMPT
    const val PERSONA_NAME_KEY = "persona_name"
    const val PERSONA_KEY = "persona"
    const val STYLE_KEY = "response_style"
    const val RULES_KEY = "behavior_rules"
}
