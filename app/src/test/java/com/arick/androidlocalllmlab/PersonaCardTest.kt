package com.arick.androidlocalllmlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaCardTest {
    @Test fun characterCardUsesV2EnvelopeAndRoundTrips() {
        val card = PersonaCard(name = "Madara Uchiha", greeting = "Wake up to reality.", speakingStyle = "Cold, authoritative")
        val json = card.toJson()
        assertTrue(json.contains("chara_card_v2"))
        assertEquals(card, PersonaCard.fromJson(json))
    }

    @Test fun identityAndHelpAreAnsweredLocallyButGeneralKnowledgeIsNotScripted() {
        val card = PersonaCard()
        assertTrue(AssistantKnowledgeRouter.answer("Who are you?", card)!!.contains(card.name))
        assertTrue(AssistantKnowledgeRouter.answer("what can you do", card)!!.contains("alarms"))
        assertEquals(card.greeting, AssistantKnowledgeRouter.answer("hello", card))
        assertEquals("Hmph. As expected.", AssistantKnowledgeRouter.answer("thanks", card))
        assertNull(AssistantKnowledgeRouter.answer("Who is Obama?", card))
        assertNull(AssistantKnowledgeRouter.answer("What did he do?", card))
    }

    @Test fun modelInstructionIsCompactNotFullCharacterCardJson() {
        val card = PersonaCard()
        assertTrue(card.compactInstruction().length < 250)
        assertTrue(card.compactInstruction().contains(card.name))
    }

    @Test fun guidesIncompleteAlarmAndReminderQueries() {
        val card = PersonaCard()
        val alarmPrompt = AssistantKnowledgeRouter.answer("set alarm", card)
        assertTrue(alarmPrompt!!.contains("What time"))

        val reminderPrompt = AssistantKnowledgeRouter.answer("remind me to call mom", card)
        assertTrue(reminderPrompt!!.contains("When should I remind you to call mom"))
    }
}
