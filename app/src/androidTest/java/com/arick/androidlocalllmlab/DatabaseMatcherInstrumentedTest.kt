package com.arick.androidlocalllmlab

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMatcherInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun matcherRecognizesNotificationsAndDiscoversActualApplicationValues() {
        val suffix = System.currentTimeMillis().toString()
        val appLabel = "Matcher WhatsApp $suffix"
        EventMemoryStore.recordNotification(
            context = context,
            appLabel = appLabel,
            packageName = "com.example.matcher.$suffix",
            notificationKey = "matcher-$suffix",
            title = "Matcher test notification",
            body = "Private body must not be exposed during discovery",
            postedAtMillis = System.currentTimeMillis()
        )

        val result = EventMemoryStore.matchDatabaseQuestion(
            context,
            "Show my latest notifications from $appLabel"
        )

        assertTrue("Notification lookup should be recognized as database intent", result.isDatabaseIntent)
        assertTrue("events should be the leading match", result.candidates.first().table == "events")
        assertTrue("Actual app label should be discovered", result.candidates.first().availableValues.any { it.contains(appLabel) })
        assertTrue("Discovery must not include notification body", result.candidates.none { it.availableValues.any { value -> value.contains("Private body") } })
    }

    @Test
    fun matcherRecognizesConversationAndSavedFactTables() {
        val suffix = System.currentTimeMillis().toString()
        EventMemoryStore.recordConversationExchange(
            context,
            "What did we discuss in the matcher test $suffix?",
            "We discussed a database matcher test.",
            System.currentTimeMillis()
        )
        EventMemoryStore.captureExplicitFact(context, "Remember that matcher test fact $suffix")

        val conversation = EventMemoryStore.matchDatabaseQuestion(context, "Find my previous conversations about matcher test")
        val facts = EventMemoryStore.matchDatabaseQuestion(context, "Show my saved facts")

        assertTrue(conversation.isDatabaseIntent)
        assertTrue("conversation_turns should be the leading match", conversation.candidates.first().table == "conversation_turns")
        assertTrue(facts.isDatabaseIntent)
        assertTrue("facts should be the leading match", facts.candidates.first().table == "facts")
    }

    @Test
    fun ordinaryChatDoesNotTriggerDatabaseRetrieval() {
        val result = EventMemoryStore.matchDatabaseQuestion(context, "Hi, how are you?")
        assertFalse(result.isDatabaseIntent)
        assertTrue(result.candidates.isEmpty())
    }
}
