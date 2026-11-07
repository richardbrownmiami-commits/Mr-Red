package com.arick.androidlocalllmlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTasksStoreTest {

    @Test
    fun roundTripsScheduledTaskItemJson() {
        val item = ScheduledTaskItem(
            id = "reminder_test_123",
            type = "reminder",
            title = "Recurring Reminder",
            note = "Drink 500ml water",
            scheduleDisplay = "Every 2 hour(s)",
            isRecurring = true,
            targetTimeMillis = 1700000000000L,
            createdAtMillis = 1699999000000L
        )

        val json = item.toJson()
        val restored = ScheduledTaskItem.fromJson(json)

        assertEquals("reminder_test_123", restored.id)
        assertEquals("reminder", restored.type)
        assertEquals("Recurring Reminder", restored.title)
        assertEquals("Drink 500ml water", restored.note)
        assertEquals("Every 2 hour(s)", restored.scheduleDisplay)
        assertTrue(restored.isRecurring)
        assertEquals(1700000000000L, restored.targetTimeMillis)
        assertEquals(1699999000000L, restored.createdAtMillis)
    }

    @Test
    fun roundTripsAlarmTaskItemJson() {
        val alarm = ScheduledTaskItem(
            id = "alarm_active",
            type = "alarm",
            title = "In-App Alarm at 07:00",
            note = "Morning workout",
            scheduleDisplay = "Fires at 07:00",
            isRecurring = false,
            targetTimeMillis = 1700003600000L
        )

        val json = alarm.toJson()
        val restored = ScheduledTaskItem.fromJson(json)

        assertEquals("alarm_active", restored.id)
        assertEquals("alarm", restored.type)
        assertEquals("Morning workout", restored.note)
        assertFalse(restored.isRecurring)
    }
}
