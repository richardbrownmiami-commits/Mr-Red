package com.arick.androidlocalllmlab

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestClassifierTest {
    @Test
    fun defaultsToChatWhenEvidenceIsWeak() {
        assertEquals(RequestCategory.CHAT, RequestClassifier.decide(floatArrayOf(2f, 2.1f, 0f, 0f, 0f)).category)
    }

    @Test
    fun selectsNonChatWhenBothThresholdsAreMet() {
        assertEquals(RequestCategory.DEVICE_LIVE, RequestClassifier.decide(floatArrayOf(0f, 3f, 0f, 0f, 0f)).category)
    }

    @Test
    fun probabilityMustReachSixtyPercent() {
        assertEquals(RequestCategory.CHAT, RequestClassifier.decide(floatArrayOf(0f, 0.7f, 0f, 0f, 0f)).category)
    }

    @Test
    fun probabilityMustExceedChatByAtLeastPointTwoFive() {
        assertEquals(RequestCategory.CHAT, RequestClassifier.decide(floatArrayOf(0.4f, 0.7f, 0f, 0f, 0f)).category)
    }

    @Test
    fun mapsDatabaseCategoriesToTheirSingleTable() {
        assertEquals("events", RequestCategory.NOTIFICATIONS.table)
        assertEquals("facts", RequestCategory.SAVED_FACTS.table)
        assertEquals("conversation_turns", RequestCategory.CONVERSATION.table)
    }

    @Test
    fun malformedLogitsFailClosedToChat() {
        assertEquals(RequestCategory.CHAT, RequestClassifier.decide(floatArrayOf(Float.NaN)).category)
    }
}
