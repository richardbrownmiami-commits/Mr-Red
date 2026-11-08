package com.arick.androidlocalllmlab

import org.json.JSONObject
import java.util.Calendar

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

        // 6. Alarms & Reminders Controls
        if (Regex("\\b(stop|dismiss|turn off|cancel|silence|kill)\\s+(?:the\\s+)?alarm\\b").containsMatchIn(text) ||
            text in setOf("stop alarm", "dismiss alarm", "cancel alarm", "turn off alarm")) {
            return ToolCall("dismiss_alarm", JSONObject())
        }

        if (Regex("\\b(cancel|clear|stop|remove|delete)\\s+(?:all\\s+)?reminders?\\b").containsMatchIn(text) ||
            text in setOf("cancel reminders", "cancel reminder", "stop reminders", "clear reminders")) {
            return ToolCall("cancel_reminders", JSONObject())
        }

        if (Regex("\\b(?:list|show|check|get|view|what(?:\\s+are)?)\\s+(?:my\\s+)?(?:upcoming\\s+)?(?:reminders?|alarms?|schedules?|scheduled\\s+tasks?|tasks?)\\b").containsMatchIn(text) ||
            text in setOf("check reminders", "upcoming reminders", "my reminders", "scheduled tasks", "check schedule", "list tasks", "my tasks", "check alarms", "schedule task", "scheduled task")) {
            return ToolCall("list_scheduled_tasks", JSONObject())
        }

        // 6a. Recurring reminders: any user-chosen action and any interval number (hours or minutes)
        // e.g. "remind me every 3 hours to take medicine" or "remind me to stretch every 2 hours"
        val recurringMatchPrefix = Regex("\\b(?:remind(?:\\s+me)?|(?:set|add|create)?\\s*(?:a\\s+)?reminder)\\s+every\\s+(\\d+)?\\s*(hours?|hrs?|minutes?|mins?)(?:(?:\\s+to)?\\s+(.+))?\\b").find(text)
        if (recurringMatchPrefix != null) {
            val amt = recurringMatchPrefix.groupValues[1].ifBlank { "1" }.toLongOrNull() ?: 1L
            val unit = recurringMatchPrefix.groupValues[2]
            val mins = if (unit.startsWith("h")) amt * 60L else amt
            val rawAction = recurringMatchPrefix.groupValues[3].ifBlank { "Reminder" }
            val action = rawAction.removePrefix("to ").trim().ifBlank { "Reminder" }
            if (mins > 0) {
                return ToolCall(
                    "schedule_reminder",
                    JSONObject().put("message", action).put("interval_minutes", mins).put("recurring", true)
                )
            }
        }

        val recurringMatchSuffix = Regex("\\b(?:remind(?:\\s+me)?(?:\\s+to)?|(?:set|add|create)?\\s*(?:a\\s+)?reminder(?:\\s+to)?)\\s+(.+?)\\s+every\\s+(\\d+)?\\s*(hours?|hrs?|minutes?|mins?)\\b").find(text)
        if (recurringMatchSuffix != null) {
            val rawAction = recurringMatchSuffix.groupValues[1]
            val action = rawAction.removePrefix("to ").trim().ifBlank { "Reminder" }
            val amt = recurringMatchSuffix.groupValues[2].ifBlank { "1" }.toLongOrNull() ?: 1L
            val unit = recurringMatchSuffix.groupValues[3]
            val mins = if (unit.startsWith("h")) amt * 60L else amt
            if (mins > 0) {
                return ToolCall(
                    "schedule_reminder",
                    JSONObject().put("message", action).put("interval_minutes", mins).put("recurring", true)
                )
            }
        }

        // 6b. Relative delay reminders (in / after):
        // e.g. "remind me in 15 mins to call mom", "reminder in 10 minutes", "remind me after 1 hour"
        val oneTimeMatchPrefix = Regex("\\b(?:remind(?:\\s+me)?|(?:set|add|create)?\\s*(?:a\\s+)?reminder)\\s+(?:in|after)\\s+(\\d+)?\\s*(hours?|hrs?|minutes?|mins?)(?:(?:\\s+to)?\\s+(.+))?\\b").find(text)
        if (oneTimeMatchPrefix != null) {
            val amt = oneTimeMatchPrefix.groupValues[1].ifBlank { "1" }.toLongOrNull() ?: 1L
            val unit = oneTimeMatchPrefix.groupValues[2]
            val mins = if (unit.startsWith("h")) amt * 60L else amt
            val rawAction = oneTimeMatchPrefix.groupValues[3].ifBlank { "Reminder" }
            val action = rawAction.removePrefix("to ").trim().ifBlank { "Reminder" }
            if (mins > 0) {
                return ToolCall(
                    "schedule_reminder",
                    JSONObject().put("message", action).put("interval_minutes", mins).put("recurring", false)
                )
            }
        }

        val oneTimeMatchSuffix = Regex("\\b(?:remind(?:\\s+me)?(?:\\s+to)?|(?:set|add|create)?\\s*(?:a\\s+)?reminder(?:\\s+to)?)\\s+(.+?)\\s+(?:in|after)\\s+(\\d+)?\\s*(hours?|hrs?|minutes?|mins?)\\b").find(text)
        if (oneTimeMatchSuffix != null) {
            val rawAction = oneTimeMatchSuffix.groupValues[1]
            val action = rawAction.removePrefix("to ").trim().ifBlank { "Reminder" }
            val amt = oneTimeMatchSuffix.groupValues[2].ifBlank { "1" }.toLongOrNull() ?: 1L
            val unit = oneTimeMatchSuffix.groupValues[3]
            val mins = if (unit.startsWith("h")) amt * 60L else amt
            if (mins > 0) {
                return ToolCall(
                    "schedule_reminder",
                    JSONObject().put("message", action).put("interval_minutes", mins).put("recurring", false)
                )
            }
        }

        // 6c. Specific clock-time reminders (at / for [clock time]):
        // e.g. "remind me at 7pm to call mom", "remind me to call mom at 7pm", "set reminder at 7:30 pm to buy milk"
        val clockReminderPrefix = Regex("\\b(?:remind(?:\\s+me)?|(?:set|add|create)?\\s*(?:a\\s+)?reminder)\\s+(?:at|for)\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?(?:(?:\\s+to)?\\s+(.+))?\\b").find(text)
        if (clockReminderPrefix != null) {
            var hour = clockReminderPrefix.groupValues[1].toIntOrNull()
            val minute = clockReminderPrefix.groupValues[2].ifBlank { "0" }.toIntOrNull()
            val meridiem = clockReminderPrefix.groupValues[3]
            if (hour != null && minute != null && minute in 0..59) {
                if (meridiem == "pm" && hour in 1..11) hour += 12
                if (meridiem == "am" && hour == 12) hour = 0
                if (hour in 0..23 && (meridiem.isBlank() || clockReminderPrefix.groupValues[1].toInt() in 1..12)) {
                    val rawAction = clockReminderPrefix.groupValues[4].ifBlank { "Reminder" }
                    val action = rawAction.removePrefix("to ").trim().ifBlank { "Reminder" }
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                        if (before(Calendar.getInstance())) {
                            add(Calendar.DAY_OF_YEAR, 1)
                        }
                    }
                    val diffMins = ((cal.timeInMillis - System.currentTimeMillis()) / (1000 * 60)).coerceAtLeast(1)
                    return ToolCall(
                        "schedule_reminder",
                        JSONObject().put("message", action).put("interval_minutes", diffMins).put("recurring", false)
                    )
                }
            }
        }

        val clockReminderSuffix = Regex("\\b(?:remind(?:\\s+me)?(?:\\s+to)?|(?:set|add|create)?\\s*(?:a\\s+)?reminder(?:\\s+to)?)\\s+(.+?)\\s+(?:at|for)\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?\\b").find(text)
        if (clockReminderSuffix != null) {
            val rawAction = clockReminderSuffix.groupValues[1]
            val action = rawAction.removePrefix("to ").trim().ifBlank { "Reminder" }
            var hour = clockReminderSuffix.groupValues[2].toIntOrNull()
            val minute = clockReminderSuffix.groupValues[3].ifBlank { "0" }.toIntOrNull()
            val meridiem = clockReminderSuffix.groupValues[4]
            if (hour != null && minute != null && minute in 0..59) {
                if (meridiem == "pm" && hour in 1..11) hour += 12
                if (meridiem == "am" && hour == 12) hour = 0
                if (hour in 0..23 && (meridiem.isBlank() || clockReminderSuffix.groupValues[2].toInt() in 1..12)) {
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                        if (before(Calendar.getInstance())) {
                            add(Calendar.DAY_OF_YEAR, 1)
                        }
                    }
                    val diffMins = ((cal.timeInMillis - System.currentTimeMillis()) / (1000 * 60)).coerceAtLeast(1)
                    return ToolCall(
                        "schedule_reminder",
                        JSONObject().put("message", action).put("interval_minutes", diffMins).put("recurring", false)
                    )
                }
            }
        }

        // 6d. Alarms: flexible matching for ANY hour, minute, am/pm, 24h, tomorrow, or standalone time answers
        // e.g. "set alarm 7am", "set alarm for 6:30", "alarm at 8pm", "wake me up at 7am", "wake me 6:15", "7am alarm", "alarm for tomorrow at 7am"
        // Also supports standalone time responses when answering an alarm prompt: "7am", "at 7am", "7:00", "7"
        val alarmMatch = Regex("\\b(?:(?:set|add|create|start|turn on)\\s+(?:an?\\s+)?)?alarm\\s+(?:(?:for\\s+)?tomorrow\\s+)?(?:for\\s+|at\\s+|to\\s+)?(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?\\b").find(text)
            ?: Regex("\\bwake\\s+(?:me\\s+)?(?:up\\s+)?(?:(?:for\\s+)?tomorrow\\s+)?(?:at\\s+|for\\s+)?(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?\\b").find(text)
            ?: Regex("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?\\s+alarm\\b").find(text)
            ?: Regex("^(?:(?:set|add)\\s+)?(?:at\\s+|for\\s+)?(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)$").find(text)
            ?: Regex("^(?:(?:set|add)\\s+)?(?:at\\s+|for\\s+)?(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?$").find(text)
            ?: Regex("^(?:at\\s+|for\\s+)?([1-9]|1[0-2])$").find(text)

        alarmMatch?.let { match ->
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = if (match.groupValues.size > 2) match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null else 0
            val meridiem = if (match.groupValues.size > 3) match.groupValues[3] else ""
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
