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
        val fuzzy = EventMemoryStore.matchDatabaseQuestion(
            context,
            "Show my latest notifications from WhtsApp"
        )
        assertTrue("A one-character application-name typo should still match", fuzzy.isDatabaseIntent)
        assertTrue(fuzzy.candidates.first().table == "events")
        assertTrue(fuzzy.candidates.first().availableValues.any { it.contains(appLabel) })
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
    fun selectedApplicationValueIsBoundAsSqlParameter() {
        val suffix = System.currentTimeMillis().toString()
        val appLabel = "O'Reilly Matcher $suffix"
        EventMemoryStore.recordNotification(
            context = context,
            appLabel = appLabel,
            packageName = "com.example.boundfilter.$suffix",
            notificationKey = "bound-filter-$suffix",
            title = "Bound parameter test",
            body = "The selected application must be filtered exactly.",
            postedAtMillis = System.currentTimeMillis()
        )

        val evidence = EventMemoryStore.executeModelReadOnlyQuery(
            context = context,
            sql = "SELECT id, source, title FROM events WHERE source = ? ORDER BY occurred_at DESC LIMIT 10",
            requiredTable = "events",
            requiredFilter = DatabaseQueryFilter("events", "source", appLabel)
        )

        assertTrue("Bound value containing an apostrophe must match exactly", evidence.contains(appLabel))
        assertTrue(evidence.contains("Bound parameter test"))
    }

    @Test
    fun requiredTableRejectsQueriesAgainstAnotherTable() {
        var rejected = false
        try {
            EventMemoryStore.executeModelReadOnlyQuery(
                context = context,
                sql = "SELECT id, fact FROM facts LIMIT 5",
                requiredTable = "events"
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("A plan for events must not read facts", rejected)
    }

    @Test
    fun genericDatabaseQuestionOffersAllThreeExistingTables() {
        val clarification = EventMemoryStore.matchDatabaseQuestion(context, "Show all my database records")
        val everything = EventMemoryStore.matchDatabaseQuestion(
            context,
            "Show all my database records",
            searchAll = true
        )

        assertTrue(clarification.isDatabaseIntent)
        assertTrue(clarification.clarification?.contains("events") == true)
        assertTrue(clarification.clarification?.contains("facts") == true)
        assertTrue(clarification.clarification?.contains("conversation turns") == true)
        assertTrue(everything.isDatabaseIntent)
        assertTrue(everything.candidates.map { it.table }.containsAll(listOf("events", "facts", "conversation_turns")))
    }

    @Test
    fun ordinaryChatDoesNotTriggerDatabaseRetrieval() {
        val result = EventMemoryStore.matchDatabaseQuestion(context, "Hi, how are you?")
        assertFalse(result.isDatabaseIntent)
        assertTrue(result.candidates.isEmpty())
    }
    @Test
    fun modelSqlValidatorRejectsUnsafeStatementsAndAcceptsReadOnlySelect() {
        val invalidQueries = listOf(
            "SELECT * FROM events LIMIT 5",
            "SELECT id FROM events JOIN facts ON events.id = facts.id LIMIT 5",
            "SELECT id FROM events UNION SELECT id FROM facts",
            "SELECT id FROM events;",
            "SELECT id FROM events -- comment",
            "SELECT id FROM events /* comment */ LIMIT 5",
            "SELECT name FROM sqlite_master LIMIT 5",
            "SELECT id FROM scheduled_tasks LIMIT 5"
        )
        invalidQueries.forEach { sql ->
            var rejected = false
            try {
                EventMemoryStore.executeModelReadOnlyQuery(
                    context = context,
                    sql = sql
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Unsafe SQL must be rejected: $sql", rejected)
        }

        val evidence = EventMemoryStore.executeModelReadOnlyQuery(
            context = context,
            sql = "SELECT id, source, title FROM events ORDER BY occurred_at DESC LIMIT 5",
            requiredTable = "events"
        )
        assertTrue(
            "A normal explicit-column SELECT should execute read-only",
            evidence.startsWith("ROWS:") || evidence.startsWith("Query completed. No matching records")
        )
    }

}
