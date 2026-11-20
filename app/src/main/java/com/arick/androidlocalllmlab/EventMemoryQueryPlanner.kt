package com.arick.androidlocalllmlab

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Deterministic, Android-independent query planning for the event-memory database.
 * All ranges are half-open [startMillis, endExclusiveMillis) in the supplied device timezone.
 */
internal data class EventMemoryQueryPlan(
    val startMillis: Long,
    val endExclusiveMillis: Long,
    val rangeLabel: String,
    val eventTypes: Set<String>,
    val asksFacts: Boolean,
    val includeConversation: Boolean
)

internal object EventMemoryQueryPlanner {
    private val numberWords = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12
    )
    private val allActivityTypes = linkedSetOf(
        "NOTIFICATION", "QUESTION", "ANSWER", "APP", "UI", "DEVICE",
        "SETTINGS", "ALARM", "REMINDER", "ERROR", "MODEL", "INFERENCE",
        "WEB", "FEATURE", "PERMISSION", "FATAL", "FACT"
    )

    fun plan(
        question: String,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): EventMemoryQueryPlan {
        val q = question.lowercase(Locale.ROOT)
        val asksNotifications = listOf("notification", "notifications", "message", "sms", "missed call", "anything important").any(q::contains)
        val asksConversation = listOf("conversation", "discuss", "what did we talk", "what did you say", "what you said", "previous answer", "remember our chat").any(q::contains)
        val asksFacts = listOf("remember me", "remember about me", "what do you remember", "what do you know about me", "my preference", "my facts", "long-term memory").any(q::contains)
        val asksAlarms = listOf("alarm", "alarms").any(q::contains)
        val asksReminders = listOf("reminder", "reminders").any(q::contains)
        val asksSettings = listOf("setting", "settings", "configuration").any(q::contains)
        val asksErrors = listOf("error", "crash", "failed", "failure", "model slow", "performance").any(q::contains)
        val asksWeb = listOf("web search", "searched online", "search history", "browsing", "website").any(q::contains)
        val broadActivity = listOf(
            "what happened", "what have i done", "what did i do", "activity",
            "anything happen", "what's been going on", "what has been going on",
            "what went on", "how was my day", "summary of my day",
            "today", "yesterday", "this morning", "this afternoon",
            "this evening", "tonight", "last night"
        ).any(q::contains) || Regex("\\b(last|past)\\s+([\\w-]+)\\s+(hours?|days?)\\b").containsMatchIn(q)

        val calendar = Calendar.getInstance(timeZone, Locale.getDefault()).apply { timeInMillis = nowMillis }
        fun midnight(value: Calendar) {
            value.set(Calendar.HOUR_OF_DAY, 0)
            value.set(Calendar.MINUTE, 0)
            value.set(Calendar.SECOND, 0)
            value.set(Calendar.MILLISECOND, 0)
        }
        fun dateBounds(dateText: String): Pair<Long, Long>? {
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
                isLenient = false
                this.timeZone = timeZone
            }
            val position = ParsePosition(0)
            val parsed = formatter.parse(dateText, position) ?: return null
            if (position.index != dateText.length) return null
            val day = Calendar.getInstance(timeZone, Locale.getDefault()).apply { time = parsed }
            midnight(day)
            val start = day.timeInMillis
            day.add(Calendar.DAY_OF_YEAR, 1)
            return start to day.timeInMillis
        }

        val dateMatch = Regex("\\b20\\d{2}-\\d{2}-\\d{2}\\b").find(q)
        val explicitBounds = dateMatch?.value?.let(::dateBounds)
        val start: Long
        val endExclusive: Long
        val label: String
        val lastRange = Regex("\\b(last|past)\\s+([\\w-]+)\\s+(hours?|days?)\\b").find(q)
        val amount = lastRange?.groupValues?.get(2)?.let { token ->
            token.toIntOrNull() ?: numberWords[token]
        }
        when {
            explicitBounds != null -> {
                start = explicitBounds.first
                endExclusive = explicitBounds.second
                label = dateMatch!!.value
            }
            q.contains("last night") -> {
                midnight(calendar)
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                calendar.set(Calendar.HOUR_OF_DAY, 18)
                start = calendar.timeInMillis
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 6)
                endExclusive = minOf(nowMillis + 1, calendar.timeInMillis)
                label = "last night (18:00–06:00)"
            }
            q.contains("this morning") -> {
                midnight(calendar)
                calendar.set(Calendar.HOUR_OF_DAY, 5)
                start = calendar.timeInMillis
                calendar.set(Calendar.HOUR_OF_DAY, 12)
                endExclusive = minOf(nowMillis + 1, calendar.timeInMillis)
                label = "this morning (05:00–12:00)"
            }
            q.contains("this afternoon") -> {
                midnight(calendar)
                calendar.set(Calendar.HOUR_OF_DAY, 12)
                start = calendar.timeInMillis
                calendar.set(Calendar.HOUR_OF_DAY, 17)
                endExclusive = minOf(nowMillis + 1, calendar.timeInMillis)
                label = "this afternoon (12:00–17:00)"
            }
            q.contains("tonight") || q.contains("this evening") -> {
                midnight(calendar)
                calendar.set(Calendar.HOUR_OF_DAY, 17)
                start = calendar.timeInMillis
                endExclusive = nowMillis + 1
                label = "this evening"
            }
            q.contains("yesterday") -> {
                midnight(calendar)
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                start = calendar.timeInMillis
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                endExclusive = calendar.timeInMillis
                label = "yesterday"
            }
            q.contains("today") -> {
                midnight(calendar)
                start = calendar.timeInMillis
                endExclusive = nowMillis + 1
                label = "today"
            }
            lastRange != null && amount != null && lastRange.groupValues[3] == "days" -> {
                val days = amount.coerceIn(1, 30)
                start = nowMillis - days * 24L * 60 * 60 * 1000
                endExclusive = nowMillis + 1
                label = "last $days days"
            }
            lastRange != null && amount != null && lastRange.groupValues[3] == "hours" -> {
                val hours = amount.coerceIn(1, 24 * 30)
                start = nowMillis - hours * 60L * 60 * 1000
                endExclusive = nowMillis + 1
                label = "last $hours hours"
            }
            q.contains("last hour") || q.contains("past hour") || q.contains("recent notification") -> {
                start = nowMillis - 60L * 60 * 1000
                endExclusive = nowMillis + 1
                label = "last hour"
            }
            asksConversation -> {
                start = nowMillis - 30L * 24 * 60 * 60 * 1000
                endExclusive = nowMillis + 1
                label = "recent conversation history"
            }
            else -> {
                start = nowMillis - 24L * 60 * 60 * 1000
                endExclusive = nowMillis + 1
                label = "last 24 hours"
            }
        }

        val selected = linkedSetOf<String>()
        if (asksNotifications) selected += "NOTIFICATION"
        if (asksConversation) selected += listOf("QUESTION", "ANSWER")
        if (asksAlarms) selected += "ALARM"
        if (asksReminders) selected += "REMINDER"
        if (asksSettings) selected += "SETTINGS"
        if (asksErrors) selected += listOf("ERROR", "INFERENCE", "MODEL", "FATAL")
        if (asksWeb) selected += "WEB"
        if (broadActivity) selected += allActivityTypes
        if (asksFacts) selected += "FACT"

        return EventMemoryQueryPlan(
            startMillis = start,
            endExclusiveMillis = endExclusive,
            rangeLabel = label,
            eventTypes = selected,
            asksFacts = asksFacts,
            includeConversation = asksConversation || broadActivity
        )
    }
}
