package com.arick.androidlocalllmlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class EventMemoryQueryPlannerTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc, Locale.US).apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun thisMorningUsesLocalCalendarWindow() {
        val now = millis(2026, 11, 21, 14, 30)
        val plan = EventMemoryQueryPlanner.plan("What happened to us this morning?", now, utc)
        assertEquals(millis(2026, 11, 21, 5), plan.startMillis)
        assertEquals(millis(2026, 11, 21, 12), plan.endExclusiveMillis)
        assertEquals("this morning (05:00–12:00)", plan.rangeLabel)
        assertTrue(plan.eventTypes.containsAll(setOf("NOTIFICATION", "WEB", "PERMISSION", "QUESTION", "ANSWER")))
        assertTrue(plan.includeConversation)
    }

    @Test
    fun afternoonAndLastNightHaveBoundedRanges() {
        val now = millis(2026, 11, 21, 20)
        val afternoon = EventMemoryQueryPlanner.plan("activity this afternoon", now, utc)
        assertEquals(millis(2026, 11, 21, 12), afternoon.startMillis)
        assertEquals(millis(2026, 11, 21, 17), afternoon.endExclusiveMillis)

        val night = EventMemoryQueryPlanner.plan("what happened last night", now, utc)
        assertEquals(millis(2026, 11, 20, 18), night.startMillis)
        assertEquals(millis(2026, 11, 21, 6), night.endExclusiveMillis)
    }

    @Test
    fun relativeNumericAndWrittenRangesAreParsed() {
        val now = millis(2026, 11, 21, 14)
        val twoHours = EventMemoryQueryPlanner.plan("what happened in the last two hours", now, utc)
        assertEquals(now - 2L * 60 * 60 * 1000, twoHours.startMillis)
        assertEquals(now + 1, twoHours.endExclusiveMillis)

        val sevenDays = EventMemoryQueryPlanner.plan("show activity for the last seven days", now, utc)
        assertEquals(now - 7L * 24 * 60 * 60 * 1000, sevenDays.startMillis)
        assertEquals(now + 1, sevenDays.endExclusiveMillis)
    }

    @Test
    fun explicitDateUsesWholeLocalDay() {
        val now = millis(2026, 11, 21, 14)
        val plan = EventMemoryQueryPlanner.plan("show activity on 2026-11-19", now, utc)
        assertEquals(millis(2026, 11, 19, 0), plan.startMillis)
        assertEquals(millis(2026, 11, 20, 0), plan.endExclusiveMillis)
        assertEquals("2026-11-19", plan.rangeLabel)
    }

    @Test
    fun specificCategoriesAreAdditiveAndDoNotBecomeBroadQueries() {
        val plan = EventMemoryQueryPlanner.plan("show notifications and web search history", millis(2026, 11, 21, 14), utc)
        assertTrue(plan.eventTypes.contains("NOTIFICATION"))
        assertTrue(plan.eventTypes.contains("WEB"))
        assertFalse(plan.eventTypes.contains("APP"))
        assertFalse(plan.includeConversation)
    }

    @Test
    fun conversationAndFactsIntentsAreExplicit() {
        val conversation = EventMemoryQueryPlanner.plan("what did we talk about", millis(2026, 11, 21, 14), utc)
        assertTrue(conversation.eventTypes.containsAll(setOf("QUESTION", "ANSWER")))
        assertTrue(conversation.includeConversation)

        val facts = EventMemoryQueryPlanner.plan("what do you remember about me", millis(2026, 11, 21, 14), utc)
        assertTrue(facts.asksFacts)
        assertTrue(facts.eventTypes.contains("FACT"))
    }
}
