package com.arick.androidlocalllmlab

import org.json.JSONObject

/** Conservative local router: device actions, settings, and web research bypass the language model. */
object DeviceIntentRouter {
    fun route(raw: String): ToolCall? {
        val text = raw.trim().lowercase()
        if (text.isEmpty()) return null

        // Exclusions for general knowledge/creative chat (unless explicit web search intent)
        if (Regex("\\b(explain|how (?:do|does|can|to)|tell me a story|write|why (?:do|does|is|are)|teach me)\\b").containsMatchIn(text)) {
            if (!Regex("\\b(search|research|look up|lookup|find online|web search)\\b").containsMatchIn(text)) {
                return null
            }
        }

        // 1. Device Vitals (battery, RAM, storage, uptime)
        if ((Regex("\\b(battery|ram|memory|storage|disk space|uptime|device status|phone status|vitals)\\b").containsMatchIn(text) &&
            Regex("\\b(check|show|status|level|free|available|remaining|used|what is|how much|my|current|percent|percentage)\\b").containsMatchIn(text)) ||
            text in setOf("battery", "vitals", "device status", "phone status", "ram", "storage")) {
            if (!text.contains("setting") && !text.contains("search")) {
                return ToolCall("device_vitals", JSONObject())
            }
        }

        // 2. Notifications
        if (Regex("\\b(read|show|check|what are|any)\\b.*\\bnotifications?\\b|\\bnotifications?\\b.*\\b(read|show|check)\\b").containsMatchIn(text)) {
            return ToolCall("read_notifications", JSONObject())
        }
        if (Regex("\\b(enable|grant|allow|open)\\b.*\\bnotification access\\b").containsMatchIn(text)) {
            return ToolCall("open_notification_access", JSONObject())
        }

        // 3. System & Hardware Settings
        if (text.contains("setting")) {
            val page = when {
                text.contains("wifi") -> "wifi"
                text.contains("bluetooth") -> "bluetooth"
                text.contains("battery") -> "battery"
                text.contains("display") -> "display"
                else -> "system"
            }
            return ToolCall("open_settings", JSONObject().put("page", page))
        }

        // 4. Phone Dialer & Calls
        Regex("\\b(?:call|dial)\\s+([+0-9 -]{3,20})\\b").find(text)?.let {
            val num = it.groupValues[1].replace(" ", "").replace("-", "")
            if (num.isNotBlank()) return ToolCall("make_call", JSONObject().put("phone_number", num))
        }
        if (Regex("\\b(open|launch|start|show)\\b.*\\b(dialer|phone|dial pad|keypad)\\b|\\b(open dialer|phone dialer)\\b").containsMatchIn(text) ||
            text in setOf("dialer", "phone dialer", "phone", "keypad")) {
            return ToolCall("make_call", JSONObject().put("phone_number", ""))
        }

        // 5. Open Apps
        Regex("\\b(?:open|launch|start)\\s+([a-z0-9][a-z0-9 ._-]{0,50})$").find(text)?.let {
            val app = it.groupValues[1].trim().removePrefix("the ")
            if (app in setOf("dialer", "phone dialer", "phone", "keypad")) {
                return ToolCall("make_call", JSONObject().put("phone_number", ""))
            }
            if (app in setOf("battery", "battery settings", "device battery settings")) {
                return ToolCall("open_settings", JSONObject().put("page", "battery"))
            }
            if (app in setOf("settings", "system settings", "device settings")) {
                return ToolCall("open_settings", JSONObject().put("page", "system"))
            }
            if (app.isNotBlank()) {
                return ToolCall("open_app", JSONObject().put("app_name", app))
            }
        }

        // 6. Alarms & Timers
        Regex("\\b(?:set|create)\\s+(?:an?\\s+)?alarm\\s+(?:for|at)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b").find(text)?.let { match ->
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
            val meridiem = match.groupValues[3]
            if (minute !in 0..59) return null
            if (meridiem == "pm" && hour in 1..11) hour += 12
            if (meridiem == "am" && hour == 12) hour = 0
            if (hour !in 0..23 || (meridiem.isNotBlank() && match.groupValues[1].toInt() !in 1..12)) return null
            return ToolCall("set_alarm", JSONObject().put("hour", hour).put("minute", minute).put("message", "Mr-Red alarm"))
        }
        Regex("\\b(?:set|start)\\s+(?:a\\s+)?timer\\s+(?:for\\s+)?(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b").find(text)?.let { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            val unit = match.groupValues[2]
            val multiplier = when {
                unit.startsWith("h") -> 3600L
                unit.startsWith("m") -> 60L
                else -> 1L
            }
            val seconds = amount * multiplier
            if (seconds in 1..86400) return ToolCall("set_timer", JSONObject().put("seconds", seconds.toInt()))
        }

        // 7. News
        if (Regex("\\b(news|headlines|breaking news)\\b").containsMatchIn(text)) {
            val query = text.replace(Regex("\\b(what is|what are|show|find|search|for|latest|current|today'?s?|news|headlines|breaking|about|tell me)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim()
            return ToolCall("get_news", JSONObject().put("query", query))
        }

        // 8. Weather
        if (Regex("\\b(weather|forecast|temperature)\\b").containsMatchIn(text)) {
            val city = Regex("\\b(?:in|for|at)\\s+([a-z][a-z .'-]{1,38})$").find(text)?.groupValues?.get(1)?.trim().orEmpty()
            if (city.isNotBlank()) {
                return ToolCall("get_weather", JSONObject().put("city", city))
            }
        }

        // 9. Web Search (stocks, crypto, trending, live/current events, explicit searches)
        val isLiveQuery = Regex("\\b(stock|stocks|stock price|share price|crypto|bitcoin|btc|eth|trending|trends|trend|market|nasdaq|dow jones|gold price|exchange rate|who won|match|score|election|winner|happening)\\b").containsMatchIn(text)
        val isExplicitSearch = Regex("\\b(search|research|look up|lookup|find online|web search|google|check online|browse)\\b").containsMatchIn(text)
        val isTemporalQuery = Regex("\\b(today|latest|current|right now|yesterday|this week|live)\\b").containsMatchIn(text) &&
            Regex("\\b(price|rate|score|news|status|update|trend|trending|value|market|release|version|leader|president|ceo|winner|about)\\b").containsMatchIn(text)

        if (isExplicitSearch || isLiveQuery || isTemporalQuery) {
            val cleanQuery = text.replace(Regex("\\b(please|search|research|look up|lookup|find online|web search|the web|online|for|can you|tell me|what is|what are|give me)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim()
            val finalQuery = if (cleanQuery.isNotBlank()) cleanQuery else text
            return ToolCall("web_search", JSONObject().put("query", finalQuery.take(240)))
        }

        return null
    }
}
