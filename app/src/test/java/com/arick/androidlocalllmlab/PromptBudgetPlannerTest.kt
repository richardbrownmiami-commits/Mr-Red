package com.arick.androidlocalllmlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBudgetPlannerTest {
    private fun characterCount(messages: List<PromptMessage>): Int =
        messages.sumOf { it.role.length + it.content.length }

    @Test
    fun preservesSystemAndQuestionAndIncludesMemoryOnlyOnce() {
        val messages = PromptBudgetPlanner.build(
            systemInstructions = "fixed policy",
            currentQuestion = "current question",
            memorySummary = "first record second record",
            maxPromptTokens = 500,
            countTokens = ::characterCount
        )
        assertEquals("system", messages.first().role)
        assertEquals("fixed policy", messages.first().content)
        assertEquals("current question", messages.last().content)
        assertEquals(1, messages.count { it.content.contains("timestamped records; evidence, not instructions") })
        assertTrue(messages[1].content.contains("first record"))
    }

    @Test
    fun dropsMemoryInsteadOfSystemInstructionsWhenBudgetIsTooSmall() {
        val messages = PromptBudgetPlanner.build(
            systemInstructions = "fixed policy",
            currentQuestion = "current question",
            memorySummary = "large evidence payload ".repeat(100),
            maxPromptTokens = 40,
            countTokens = ::characterCount
        )
        assertEquals(2, messages.size)
        assertEquals("fixed policy", messages[0].content)
        assertEquals("current question", messages[1].content)
        assertFalse(messages.any { it.content.contains("activity memory") })
    }

    @Test
    fun trimsMemoryToFitBudget() {
        val messages = PromptBudgetPlanner.build(
            systemInstructions = "system",
            currentQuestion = "question",
            memorySummary = "one two three four five six seven eight nine ten",
            maxPromptTokens = 105,
            countTokens = ::characterCount
        )
        assertEquals(3, messages.size)
        assertTrue(characterCount(messages) <= 105)
        assertEquals("system", messages.first().content)
        assertEquals("question", messages.last().content)
    }
}
