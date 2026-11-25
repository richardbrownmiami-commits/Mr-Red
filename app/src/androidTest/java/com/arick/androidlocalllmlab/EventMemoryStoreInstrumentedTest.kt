package com.arick.androidlocalllmlab

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventMemoryStoreInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun notificationHistoryIsStoredAndTracksDismissal() {
        val now = System.currentTimeMillis()
        val key = "mr-red-instrumented-$now"
        val title = "SQLite integration title $now"
        val body = "SQLite integration body $now"
        EventMemoryStore.recordNotification(
            context = context,
            appLabel = "Integration test app",
            packageName = "com.example.mrredtest",
            notificationKey = key,
            title = title,
            body = body,
            postedAtMillis = now,
            importance = "important"
        )
        val posted = EventMemoryStore.browseEvents(context, "notifications", title)
        assertTrue(posted.any { it.title == title && it.body == body && it.isActive })
        EventMemoryStore.markNotificationRemoved(context, "com.example.mrredtest", key, now + 2)
        val dismissed = EventMemoryStore.browseEvents(context, "notifications", title)
        assertTrue(dismissed.any { it.title == title && !it.isActive })
    }

    @Test
    fun eventRecordsCanBeBrowsedBySection() {
        val now = System.currentTimeMillis()
        val detail = "direct-control integration event $now"
        EventMemoryStore.recordEvent(context, "DEVICE", "integration_test", detail, source = "instrumented_test", occurredAtMillis = now)
        val rows = EventMemoryStore.browseEvents(context, "actions", detail)
        assertTrue(rows.any { it.detail == detail && it.action == "integration_test" })
    }

    @Test
    fun databaseBrowserCountsExposeNotificationAndActivityCounts() {
        val counts = EventMemoryStore.databaseBrowserCounts(context)
        assertTrue(counts.containsKey("notifications"))
        assertTrue(counts.containsKey("activities"))
        assertEquals(false, counts.containsKey("chat"))
    }
}
