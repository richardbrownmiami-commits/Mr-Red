package com.arick.androidlocalllmlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaCardTest {
    @Test fun characterCardUsesV2EnvelopeAndRoundTrips() {
        val card = PersonaCard(name = "Red", greeting = "Hey there", speakingStyle = "Warm, plainspoken")
        val json = card.toJson()
        assertTrue(json.contains("chara_card_v2"))
        assertEquals(card, PersonaCard.fromJson(json))
    }

    @Test fun identityAndHelpAreAnsweredLocallyButGeneralKnowledgeIsNotScripted() {
        val card = PersonaCard()
        assertTrue(AssistantKnowledgeRouter.answer("Who are you?", card)!!.contains("Mr-Red"))
        assertTrue(AssistantKnowledgeRouter.answer("what can you do", card)!!.contains("alarms"))
        assertEquals(card.greeting, AssistantKnowledgeRouter.answer("hello", card))
        assertEquals("You're welcome.", AssistantKnowledgeRouter.answer("thanks", card))
        assertNull(AssistantKnowledgeRouter.answer("Who is Obama?", card))
        assertNull(AssistantKnowledgeRouter.answer("What did he do?", card))
    }

    @Test fun modelInstructionIsCompactNotFullCharacterCardJson() {
        val card = PersonaCard()
        assertTrue(card.compactInstruction().length < 200)
        assertTrue(card.compactInstruction().contains("Mr-Red"))
    }
}
