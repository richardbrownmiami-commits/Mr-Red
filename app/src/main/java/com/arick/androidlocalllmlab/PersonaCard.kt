package com.arick.androidlocalllmlab

import android.content.Context
import org.json.JSONObject

/** Small editable Character Card stored locally; only its compact speaking identity is sent to GGUF. */
data class PersonaCard(
    val name: String = "Mr-Red",
    val description: String = "An offline assistant living on this Android phone.",
    val personality: String = "Practical, curious, calm, and honest about uncertainty.",
    val speakingStyle: String = "Warm and natural. Be concise, avoid robotic disclaimers, and never claim an action that did not happen.",
    val greeting: String = "Hey, what can I help with?"
) {
    fun toJson(): String = JSONObject().apply {
        put("spec", "chara_card_v2")
        put("spec_version", "2.0")
        put("data", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("personality", personality)
            put("scenario", "An offline assistant on the user's Android phone.")
            put("first_mes", greeting)
            put("mes_example", "")
            put("creator_notes", "Editable Mr-Red persona card stored locally on the device.")
            put("system_prompt", "")
            put("post_history_instructions", "")
            put("alternate_greetings", org.json.JSONArray())
            put("tags", org.json.JSONArray().put("offline-assistant"))
            put("creator", "Mr-Red")
            put("character_version", "1")
            put("extensions", JSONObject().put("speaking_style", speakingStyle))
        })
    }.toString()

    /** Keep the model-facing identity intentionally compact to limit repeated prompt cost. */
    fun compactInstruction(): String = "You are $name. ${personality.trim()} Voice: ${speakingStyle.trim()}"

    companion object {
        fun fromJson(json: String?): PersonaCard {
            if (json.isNullOrBlank()) return PersonaCard()
            return runCatching {
                val root = JSONObject(json)
                val obj = root.optJSONObject("data") ?: root
                val ext = obj.optJSONObject("extensions")
                PersonaCard(
                    name = obj.optString("name").takeIf { it.isNotBlank() } ?: "Mr-Red",
                    description = obj.optString("description").takeIf { it.isNotBlank() } ?: PersonaCard().description,
                    personality = obj.optString("personality").takeIf { it.isNotBlank() } ?: PersonaCard().personality,
                    speakingStyle = (ext?.optString("speaking_style") ?: obj.optString("speaking_style"))
                        .takeIf { it.isNotBlank() } ?: PersonaCard().speakingStyle,
                    greeting = obj.optString("first_mes").takeIf { it.isNotBlank() }
                        ?: obj.optString("greeting").takeIf { it.isNotBlank() }
                        ?: PersonaCard().greeting
                )
            }.getOrDefault(PersonaCard())
        }
    }
}

class PersonaCardStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("mr_red_persona", Context.MODE_PRIVATE)
    fun load(): PersonaCard {
        val stored = prefs.getString(KEY_CARD, null)
        if (!stored.isNullOrBlank()) return PersonaCard.fromJson(stored)
        val bundled = runCatching {
            context.assets.open("mr_red_character_card.json").bufferedReader().use { it.readText() }
        }.getOrNull()
        return PersonaCard.fromJson(bundled)
    }
    fun save(card: PersonaCard) { prefs.edit().putString(KEY_CARD, card.toJson()).apply() }
    fun reset() { prefs.edit().remove(KEY_CARD).apply() }
    private companion object { const val KEY_CARD = "character_card_v2" }
}

/** Answers only app-identity/capability questions. General knowledge is intentionally left to GGUF. */
object AssistantKnowledgeRouter {
    fun answer(raw: String, card: PersonaCard): String? {
        val text = raw.trim().lowercase().replace(Regex("[?!.,]+$"), "").trim()
        if (text in setOf("hi", "hello", "hey", "good morning", "good afternoon", "good evening")) {
            return card.greeting
        }
        if (text in setOf("thanks", "thank you", "thx")) return "You're welcome."
        if (text.matches(Regex("(?:who are you|what are you|what is your name|what's your name|your name|who am i talking to)"))) {
            return "I'm ${card.name}, ${card.description.lowercase().removeSuffix(".")}."
        }
        if (text.matches(Regex("(?:help|what can you do|what can you help me with|what are your skills|what features do you have|your capabilities|what do you know how to do)"))) {
            return "I can check phone status, open apps and settings, set alarms and reminders, read notifications when you grant access, and look up current news, weather, or web results. For open-ended explanations, writing, or questions outside those built-in actions, I use the local model when it’s loaded."
        }
        return null
    }
}
