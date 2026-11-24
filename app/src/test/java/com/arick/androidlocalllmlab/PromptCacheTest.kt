package com.arick.androidlocalllmlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCacheTest {
    @Test
    fun everyStageUsesIdenticalSystemContent() {
        val stages = listOf("REQUEST", "ROUTE", "INTENT", "SQL", "ANSWER", "CHAT")
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
        assertTrue("Stable prefix should stay compact (under 300 whitespace-delimited words)", system.split(Regex("\\s+")).size < 300)
    }

    @Test
    fun stableSystemContainsMrRedPersonaAndTruthfulnessRules() {
        val system = PromptCache.STABLE_SYSTEM
        assertTrue(system.contains("feminine, composed, perceptive, quietly witty"))
        assertTrue(system.contains("Identify only as Mr-Red"))
        assertTrue(system.contains("Never invent facts, access, or results; report actions only after success"))
    }

    @Test
    fun requestStageDefinesModelOwnedRoutingAndBoundSql() {
        val system = PromptCache.stage("REQUEST", "question").first().content
        assertTrue(system.contains("CHAT|"))
        assertTrue(system.contains("SQL|<table>|"))
        assertTrue(system.contains("TOOL_CALL|"))
        assertTrue(system.contains("matching string args"))
        assertTrue(system.contains("events, facts, conversation_turns"))
    }
    @Test
    fun requestProtocolNormalizerAcceptsPipeAndSlashDelimiters() {
        assertEquals("CHAT|Hello.", PromptCache.normalizeRequestProtocol("CHAT|Hello."))
        assertEquals("CHAT|Hello.", PromptCache.normalizeRequestProtocol("CHAT/Hello."))
        assertEquals(
            "SQL|events|{\"query\":\"SELECT id FROM events LIMIT 5\",\"args\":[]}",
            PromptCache.normalizeRequestProtocol("SQL/events/{\"query\":\"SELECT id FROM events LIMIT 5\",\"args\":[]}")
        )
        assertEquals("TOOL_CALL|device_vitals|{}", PromptCache.normalizeRequestProtocol("TOOL_CALL/device_vitals/{}"))
    }

    @Test
    fun requestProtocolNormalizerRemovesCodeFenceButRejectsPlainProse() {
        assertEquals("CHAT|Hello.", PromptCache.normalizeRequestProtocol("```text\nCHAT/Hello.\n```"))
        assertEquals(null, PromptCache.normalizeRequestProtocol("Hello, how can I help?"))
        assertEquals(null, PromptCache.normalizeRequestProtocol("NO_TOOL"))
    }

    @Test
    fun systemDefinesNonConflictingStageContracts() {
        val system = PromptCache.STABLE_SYSTEM
        assertTrue(system.contains("STAGE=REQUEST"))
        assertTrue(system.contains("STAGE=SQL"))
        assertTrue(system.contains("STAGE=INTENT"))
        assertTrue(system.contains("Do not mix contracts"))
    }

}
