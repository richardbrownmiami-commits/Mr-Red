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
            appLabel = "Integration test app $now",
            packageName = "com.example.mrredtest",
            notificationKey = key,
            title = title,
            body = body,
            postedAtMillis = now,
            importance = "important"
        )
        val posted = EventMemoryStore.notificationContextForQuestion(
            context,
            "What notification from Integration test app $now arrived in the last hour?"
        )
        assertTrue("Notification title must come from SQLite", posted.contains(title))
        assertTrue(posted.contains(body))
        assertTrue(posted.contains("| active"))

        EventMemoryStore.markNotificationRemoved(
            context = context,
            packageName = "com.example.mrredtest",
            notificationKey = key,
            removedAtMillis = now + 2
        )
        val dismissed = EventMemoryStore.notificationContextForQuestion(
            context,
            "What notification from Integration test app $now arrived in the last hour?"
        )
        assertTrue("Dismissal must update the stored notification row", dismissed.contains("| dismissed"))
    }

    @Test
    fun actionNotificationContextIsCompactAndTracksPreviouslySharedRows() {
        val now = System.currentTimeMillis()
        val suffix = now.toString()
        val title = "Alice $suffix"
        val body = "Are you available for the meeting? $suffix"
        EventMemoryStore.recordNotification(
            context = context,
            appLabel = "Integration test app $suffix",
            packageName = "com.example.notificationactiontest",
            notificationKey = "notification-action-$suffix",
            title = title,
            body = body,
            postedAtMillis = now,
            importance = "important"
        )

        val first = EventMemoryStore.notificationContextForQuestion(
            context,
            "What notification from Integration test app $now arrived in the last hour?"
        )
        assertTrue("Action context must contain the matching title", first.contains(title))
        assertTrue("Action context must preserve the message text", first.contains(body))
        assertTrue("Action context must be compact evidence", first.contains("NOTIFICATION EVIDENCE"))

        val unread = EventMemoryStore.notificationContextForQuestion(
            context,
            "Any unread notifications from Integration test app $suffix?"
        )
        assertTrue(
            "A record already supplied to the model must not be returned as unread",
            unread.contains("No notifications remain unshared with the model")
        )
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
