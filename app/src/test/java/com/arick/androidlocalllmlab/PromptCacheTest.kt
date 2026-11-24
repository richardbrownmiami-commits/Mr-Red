package com.arick.androidlocalllmlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCacheTest {
    @Test
    fun everyStageUsesIdenticalSystemContent() {
        val stages = listOf("CLASSIFY", "TOOL", "SQL", "ANSWER", "CHAT")
        val systems = stages.map { PromptCache.stage(it, "question=$it").first().content }
        assertEquals(1, systems.distinct().size)
        assertEquals(PromptCache.STABLE_SYSTEM, systems.first())
    }

    @Test
    fun stableSystemContainsNoPerCallValues() {
        val system = PromptCache.STABLE_SYSTEM
        listOf("NOW_MS=", "TZ=", "QUESTION=", "ROWS=", "PREVIOUS_QUESTION=",
            "nowMillis", "currentTimeMillis", "System.currentTimeMillis").forEach { marker ->
            assertFalse("Dynamic marker found: $marker", system.contains(marker))
        }
        assertTrue(system.split(Regex("\\s+")).size < 300)
        assertEquals(520, PromptCache.MAX_STABLE_PREFIX_TOKENS)
    }

    @Test
    fun stableSystemContainsRequiredRules() {
        val system = PromptCache.STABLE_SYSTEM
        assertTrue(system.contains("You cannot read email; say so and never claim to see an inbox."))
        assertTrue(system.contains("Saved chat history: up to 40 exchanges on this device."))
        assertTrue(system.contains("Never answer battery, storage, RAM, or weather without a successful tool result."))
    }

    @Test
    fun generatedRoutingStagesAreAbsent() {
        assertFalse(PromptCache.STABLE_SYSTEM.contains("ROUTE:"))
        assertFalse(PromptCache.STABLE_SYSTEM.contains("INTENT:"))
    }
}
