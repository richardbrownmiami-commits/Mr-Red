package com.arick.androidlocalllmlab

import org.json.JSONObject

/** Conservative local router: only unambiguous requests bypass the language model. */
object DeviceIntentRouter {
    fun route(raw: String): ToolCall? {
        val text = raw.trim().lowercase()
        if (text.isEmpty()) return null
        if (Regex("\\b(battery|ram|memory|storage|disk space|uptime|device status|phone status|vitals)\\b").containsMatchIn(text) &&
            Regex("\\b(check|show|what|how|status|tell|read|my|current|free|available|remaining|used)\\b").containsMatchIn(text)) {
            return ToolCall("device_vitals", JSONObject())
        }
        if (Regex("\\b(read|show|check|what are)\\b.*\\bnotifications?\\b|\\bnotifications?\\b.*\\b(read|show|check)\\b").containsMatchIn(text)) {
            return ToolCall("read_notifications", JSONObject())
        }
        if (Regex("\\b(enable|grant|allow|open)\\b.*\\bnotification access\\b").containsMatchIn(text)) {
            return ToolCall("open_notification_access", JSONObject())
        }
        Regex("\\b(?:open|launch|start)\\s+([a-z0-9][a-z0-9 ._-]{0,50})$").find(text)?.let {
            val app = it.groupValues[1].trim().removePrefix("the ")
            if (app.isNotBlank() && app !in setOf("settings", "wifi settings", "bluetooth settings", "battery settings", "display settings")) {
                return ToolCall("open_app", JSONObject().put("app_name", app))
            }
            val page = when {
                app.contains("wifi") -> "wifi"
                app.contains("bluetooth") -> "bluetooth"
                app.contains("battery") -> "battery"
                app.contains("display") -> "display"
                else -> "system"
            }
            return ToolCall("open_settings", JSONObject().put("page", page))
        }
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
        if (Regex("\\b(news|headlines|latest news)\\b").containsMatchIn(text) &&
            Regex("\\b(latest|current|today|news|headlines|search|find)\\b").containsMatchIn(text)) {
            val query = text.replace(Regex("\\b(what is|what are|show|find|search|for|latest|current|today'?s?|news|headlines|about)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim()
            return ToolCall("get_news", JSONObject().put("query", query))
        }
        if (Regex("\\b(weather|forecast|temperature)\\b").containsMatchIn(text) &&
            Regex("\\b(weather|forecast|temperature|rain)\\b").containsMatchIn(text)) {
            val city = Regex("\\b(?:in|for|at)\\s+([a-z][a-z .'-]{1,38})$").find(text)?.groupValues?.get(1)?.trim()
                ?: return null
            return ToolCall("get_weather", JSONObject().put("city", city))
        }
        Regex("\\b(?:latest|today'?s?)\\s+(?:updates?(?:\\s+(?:on|about))?|information(?:\\s+(?:on|about))?|info(?:\\s+(?:on|about))?|news(?:\\s+(?:on|about))?|about|on)\\s+([a-z0-9][a-z0-9 ._-]{1,60})$").find(text)?.let {
            val topic = it.groupValues[1].trim()
            if (topic.isNotBlank()) return ToolCall("web_search", JSONObject().put("query", topic.take(240)))
        }
        if (Regex("\\b(search|research|look up|find online|web search)\\b").containsMatchIn(text)) {
            val query = text.replace(Regex("\\b(please|search|research|look up|find online|web search|the web|online|for)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim()
            if (query.isNotBlank()) return ToolCall("web_search", JSONObject().put("query", query.take(240)))
        }
        return null
    }
}
