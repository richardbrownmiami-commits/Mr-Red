package com.arick.androidlocalllmlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionCallingTest {

    @Test
    fun parseToolCall_validToolJson() {
        val input = """{"tool": "open_settings", "arguments": {"setting": "battery"}}"""
        val call = FunctionCalling.parseToolCall(input)
        assertNotNull("ToolCall should not be null", call)
        assertEquals("open_settings", call?.name)
        assertEquals("battery", call?.arguments?.optString("setting"))
    }

    @Test
    fun parseToolCall_alternativeNameKey() {
        val input = """{"name": "make_call", "arguments": {"phone_number": "1234567890"}}"""
        val call = FunctionCalling.parseToolCall(input)
        assertNotNull("ToolCall should support 'name' key", call)
        assertEquals("make_call", call?.name)
        assertEquals("1234567890", call?.arguments?.optString("phone_number"))
    }

    @Test
    fun parseToolCall_markdownCodeblockWrapped() {
        val input = """
            ```json
            {
              "tool": "get_device_vitals",
              "arguments": {}
            }
            ```
        """.trimIndent()
        val call = FunctionCalling.parseToolCall(input)
        assertNotNull("ToolCall should handle markdown codeblocks", call)
        assertEquals("get_device_vitals", call?.name)
    }

    @Test
    fun parseToolCall_deepseekThinkTagsStripped() {
        val input = """<think>I need to check the battery level for the user.</think>{"tool": "get_device_vitals", "arguments": {}}"""
        val call = FunctionCalling.parseToolCall(input)
        assertNotNull("ToolCall should strip <think> tags", call)
        assertEquals("get_device_vitals", call?.name)
    }

    @Test
    fun parseToolCall_emptyOrMissingArgumentsDefaultsToEmptyJson() {
        val input = """{"tool": "get_device_vitals"}"""
        val call = FunctionCalling.parseToolCall(input)
        assertNotNull(call)
        assertEquals("get_device_vitals", call?.name)
        assertEquals(0, call?.arguments?.length())
    }

    @Test
    fun parseToolCall_normalChatReturnsNull() {
        val input = "Hello! I am your offline assistant. How can I help you today?"
        val call = FunctionCalling.parseToolCall(input)
        assertNull("Regular chat responses must return null", call)
    }

    @Test
    fun parseToolCall_malformedJsonReturnsNull() {
        val input = "{\"tool\": "
        val call = FunctionCalling.parseToolCall(input)
        assertNull("Malformed JSON should return null without throwing", call)
    }

    @Test
    fun parseToolCall_emptyStringReturnsNull() {
        assertNull(FunctionCalling.parseToolCall(""))
        assertNull(FunctionCalling.parseToolCall("   "))
    }

    @Test
    fun shouldDeferToolRendering_detection() {
        assertTrue(FunctionCalling.shouldDeferToolRendering("{\"tool"))
        assertTrue(FunctionCalling.shouldDeferToolRendering("{\"name"))
        assertTrue(FunctionCalling.shouldDeferToolRendering("  {\"tool\": \"open_app\""))
        assertTrue(FunctionCalling.shouldDeferToolRendering("<think>reasoning...</think>{\"tool"))

        assertFalse(FunctionCalling.shouldDeferToolRendering("I will open that app for you now."))
        assertFalse(FunctionCalling.shouldDeferToolRendering("Sure thing! Here is what I found:"))
    }

    @Test
    fun toolRegistry_executesMatchingTool() {
        val testTool = object : ToolDefinition {
            override val name: String = "test_echo"
            override val description: String = "Echoes back the test input"
            override val parameterSchema: List<ToolParameterSchema> = listOf(
                ToolParameterSchema("input", "Text to echo")
            )

            override fun execute(arguments: JSONObject, onProgress: (String) -> Unit): ToolResult {
                val input = arguments.optString("input")
                return ToolResult.Success("Echoed: $input")
            }
        }

        val registry = ToolRegistry(listOf(testTool))
        val call = ToolCall("test_echo", JSONObject().put("input", "hello world"))
        val result = registry.execute(call)

        assertTrue(result is ToolResult.Success)
        assertEquals("Echoed: hello world", (result as ToolResult.Success).content)
    }

    @Test
    fun toolRegistry_unknownToolReturnsFailure() {
        val registry = ToolRegistry(emptyList())
        val call = ToolCall("non_existent_tool", JSONObject())
        val result = registry.execute(call)

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).message.contains("Tool not allowed"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun toolRegistry_duplicateToolNameThrowsException() {
        val tool1 = object : ToolDefinition {
            override val name: String = "duplicate"
            override val description: String = "First"
            override val parameterSchema: List<ToolParameterSchema> = emptyList()
            override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = ToolResult.Success("1")
        }
        val tool2 = object : ToolDefinition {
            override val name: String = "duplicate"
            override val description: String = "Second"
            override val parameterSchema: List<ToolParameterSchema> = emptyList()
            override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = ToolResult.Success("2")
        }

        ToolRegistry(listOf(tool1, tool2))
    }

    @Test
    fun toolRegistry_modelInstructionContainsToolMetadata() {
        val testTool = object : ToolDefinition {
            override val name: String = "alarm_tool"
            override val description: String = "Sets an alarm clock"
            override val parameterSchema: List<ToolParameterSchema> = listOf(
                ToolParameterSchema("time", "Time of the alarm"),
                ToolParameterSchema("mode", "Alarm mode", allowedValues = listOf("silent", "loud"))
            )

            override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = ToolResult.Success("done")
        }

        val registry = ToolRegistry(listOf(testTool))
        val instruction = registry.modelInstruction()

        assertTrue(instruction.contains("alarm_tool"))
        assertTrue(instruction.contains("Sets an alarm clock"))
        assertTrue(instruction.contains("time"))
        assertTrue(instruction.contains("allowed values: silent or loud"))
    }

    @Test
    fun functionCalling_systemInstructionFormatsCorrectly() {
        val formatted = FunctionCalling.systemInstruction("Custom tool definitions here")
        assertTrue(formatted.contains("Available tools:"))
        assertTrue(formatted.contains("Custom tool definitions here"))
        assertTrue(formatted.contains("{\"tool\":\"tool_name\""))
    }
}
