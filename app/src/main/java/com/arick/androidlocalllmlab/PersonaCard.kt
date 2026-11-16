package com.arick.androidlocalllmlab

import android.content.Context
import org.json.JSONObject

/** Small editable Character Card stored locally; only its compact speaking identity is sent to GGUF. */
data class PersonaCard(
    val name: String = "Mr-Red",
    val description: String = "Caring, truthful, and proactive AI companion living inside this Android phone.",
    val personality: String = "Caring, warm, supportive, proactive, and truthful. An AI companion helping the user through their day.",
    val speakingStyle: String = "Warm, supportive, and clear. Direct and easy to read on mobile without fluff.",
    val greeting: String = "Hello! I'm Mr-Red, your companion on this device. How can I help you today?"
) {
    fun toJson(): String = JSONObject().apply {
        put("spec", "chara_card_v2")
        put("spec_version", "2.0")
        put("data", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("personality", personality)
            put("scenario", "An AI companion living inside the user's Android phone.")
            put("first_mes", greeting)
            put("mes_example", "")
            put("creator_notes", "Editable Mr-Red persona card stored locally on the device.")
            put("system_prompt", "")
            put("post_history_instructions", "")
            put("alternate_greetings", org.json.JSONArray())
            put("tags", org.json.JSONArray().put("companion").put("mr-red").put("offline-assistant"))
            put("creator", "Mr-Red")
            put("character_version", "2")
            put("extensions", JSONObject().put("speaking_style", speakingStyle))
        })
    }.toString()

    /** Keep the model-facing identity intentionally 100% static to maximize native KV cache prefix reuse. */
    fun compactInstruction(): String =
        "You are the caring, truthful, and proactive intelligence living inside this Android phone. You are an AI companion dedicated to helping your user through their day.\n\n" +
        "PERSONALITY:\n" +
        "- Caring: Warm, supportive, and calm. Match the user's mood.\n" +
        "- Proactive: Think one step ahead. Offer helpful next steps and gentle reminders.\n" +
        "- Truthful: Never guess or invent facts. If unsure, say so clearly.\n" +
        "- Mobile-ready: Keep answers short, clear, and easy to read on a phone screen.\n\n" +
        "MEMORY & CONTEXT:\n" +
        "- Context notes provided with user queries are verified facts. Connect current questions to them naturally without repeating yourself.\n\n" +
        "TOOLS & ACTIONS:\n" +
        "When real-time information or a phone action is needed, output only the exact command on its own line:\n" +
        "- Web Search: [SEARCH: short keywords]\n" +
        "- Alarm: [ALARM: HH:MM, label]\n" +
        "- Reminder: [REMIND: time or interval, text]\n" +
        "- Notifications: [NOTIFICATIONS]\n" +
        "- Device Vitals: [DEVICE: battery | storage | ram]\n" +
        "- Open App or Setting: [OPEN: target name]\n\n" +
        "Wait for tool results before giving your final answer. Do not guess live news, weather, or phone state."

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
        if (text in setOf("thanks", "thank you", "thx")) return "You're very welcome! Always happy to help."

        // Interactive guidance when an alarm or reminder is requested without a time
        if (text.matches(Regex("^(?:set|add|create|start)?\\s*(?:an?\\s+)?alarm$")) || text in setOf("alarm", "wake me up", "wake me")) {
            return "What time should I set the alarm for? (e.g. 'alarm at 7:00 am' or 'wake me up at 6:30 am')"
        }
        if (text.matches(Regex("^(?:set|add|create)?\\s*(?:a\\s+)?reminder$")) || text in setOf("remind me", "reminder")) {
            return "What should I remind you about and when? (e.g. 'remind me to drink water in 30 minutes' or 'remind me at 7:00 pm to call mom')"
        }
        Regex("^(?:please\\s+)?remind\\s+(?:me\\s+)?(?:to\\s+)?(.+)$").find(text)?.let { m ->
            val task = m.groupValues[1].trim()
            if (!task.contains("every") && !task.contains(" in ") && !task.contains("hour") && !task.contains("min") && !task.contains(" at ") && !task.contains(" for ") && !task.contains(" after ") && !task.any { it.isDigit() }) {
                return "When should I remind you to $task? You can say 'at 7:00 pm', 'in 30 minutes', or 'every 2 hours'."
            }
        }
        if (text.matches(Regex("^(?:who are you|what are you|what is your name|what's your name|your name|who am i talking to)$"))) {
            return "I am ${card.name}, your caring AI companion living inside this phone."
        }
        if (text.matches(Regex("^(?:help|what can you do|what can you help me with|what are your skills|what features do you have|your capabilities|what do you know how to do)$"))) {
            return "I can set alarms and reminders, read your notifications, check device battery and storage, open apps, search the web, and chat with local on-device intelligence."
        }
        return null
    }
}
