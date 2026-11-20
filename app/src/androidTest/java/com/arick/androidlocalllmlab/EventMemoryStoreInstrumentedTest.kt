package com.arick.androidlocalllmlab

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventMemoryStoreInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun notificationHistoryIsReadFromSqliteAndTracksDismissal() {
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
        val posted = EventMemoryStore.recentNotificationDigest(context, now - 1, now + 1)
        assertTrue("Notification title must come from SQLite", posted.contains(title))
        assertTrue(posted.contains(body))
        assertTrue(posted.contains("status=active"))

        EventMemoryStore.markNotificationRemoved(
            context = context,
            packageName = "com.example.mrredtest",
            notificationKey = key,
            removedAtMillis = now + 2
        )
        val dismissed = EventMemoryStore.recentNotificationDigest(context, now - 1, now + 1)
        assertTrue("Dismissal must update the stored notification row", dismissed.contains("status=dismissed"))
    }

    @Test
    fun broadTodayRetrievalIncludesDifferentRecordedCategories() {
        val now = System.currentTimeMillis()
        val suffix = now.toString()
        val details = listOf(
            "WEB" to "web integration event $suffix",
            "PERMISSION" to "permission integration event $suffix",
            "APP" to "app integration event $suffix"
        )
        details.forEach { (category, detail) ->
            EventMemoryStore.recordEvent(
                context = context,
                category = category,
                action = "integration_test",
                detail = detail,
                source = "instrumented_test",
                occurredAtMillis = now
            )
        }

        val retrieved = EventMemoryStore.relevantContext(context, "What happened in the last two hours?", now + 1000)
        details.forEach { (_, detail) ->
            assertTrue("Broad retrieval must include $detail", retrieved.contains(detail))
        }
    }
}
