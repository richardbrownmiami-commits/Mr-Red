package com.arick.androidlocalllmlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PromptCacheTest {
    @Test
    fun everyStageUsesIdenticalSystemContent() {
        val stages = listOf("ROUTE", "INTENT", "SQL", "ANSWER", "CHAT")
        val systems = stages.map { PromptCache.stage(it, "question=$it").first().content }
        assertEquals(1, systems.distinct().size)
        assertEquals(PromptCache.STABLE_SYSTEM, systems.first())
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
    }
}
