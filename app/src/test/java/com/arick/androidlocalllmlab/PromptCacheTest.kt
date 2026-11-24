package com.arick.androidlocalllmlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCacheTest {
    @Test
    fun fixedSystemPromptDefinesCompactModelContract() {
        val system = PromptCache.STABLE_SYSTEM
        assertTrue(system.contains("Choose the action for each request"))
        assertTrue(system.contains("CHAT|"))
        assertTrue(system.contains("SQL|"))
        assertTrue(system.contains("TOOL_CALL|"))
        assertTrue(system.contains("events(id,event_type"))
        assertTrue(system.contains("conversation_turns"))
        assertTrue(system.contains("DATABASE_RESULT_HANDOFF"))
        assertTrue(system.contains("TOOL_RESULT_HANDOFF"))
        assertTrue(system.length < 3100)
        assertTrue(!system.contains("Kotlin does not classify intent"))
        assertTrue(!system.contains("Every new user message is sent directly to you"))
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
}
