package com.arick.androidlocalllmlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCacheTest {
    @Test
    fun stagesSendOnlyDynamicUserContent() {
        val stages = listOf("ANSWER", "CHAT")
        val messages = stages.map { PromptCache.stage(it, "question=$it") }
        assertTrue(messages.all { it.size == 1 && it.first().role == "user" })
        assertEquals("STAGE=ANSWER\nquestion=ANSWER", messages.first().first().content)
        assertTrue(PromptCache.STABLE_SYSTEM.isNotBlank())
    }

    @Test
    fun stableSystemContainsNoPerCallValues() {
        val system = PromptCache.STABLE_SYSTEM
        listOf(
            "NOW_MS=", "TZ=", "QUESTION=", "ROWS=", "PREVIOUS_QUESTION=",
            "nowMillis", "currentTimeMillis", "System.currentTimeMillis"
        ).forEach { marker ->
            assertFalse("Dynamic marker found: $marker", system.contains(marker))
        }
        assertFalse(Regex("\\b\\d{10,13}\\b").containsMatchIn(system))
        assertTrue("Stable prefix should stay compact (under 350 whitespace-delimited words)", system.split(Regex("\\s+")).size < 350)
    }

    @Test
    fun stableSystemContainsMrRedPersonaAndTruthfulnessRules() {
        val system = PromptCache.STABLE_SYSTEM
        assertTrue(system.contains("feminine, composed, perceptive, quietly witty"))
        assertTrue(system.contains("Identify only as Mr-Red"))
        assertTrue(system.contains("Never invent facts, access, or results; report actions only after success"))
        assertTrue(system.contains("tool+"))
        assertTrue(system.contains("Never output tool+ for general knowledge"))
        assertTrue(system.contains("TOOL_CALL|name|JSON"))
    }
}
