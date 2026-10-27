package com.redmi9.offlineai.agent

import com.redmi9.offlineai.RwkvNativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

/** Minimal provider contract mirroring the OpenDroid LLMProvider boundary. */
interface AgentLLMProvider {
    val name: String
    val availableModels: List<String>
    suspend fun complete(request: AgentLLMRequest): AgentLLMResponse
    fun streamComplete(request: AgentLLMRequest): Flow<String>
    suspend fun isAvailable(): Boolean
}

data class AgentChatMessage(val role: String, val content: String)
data class AgentToolDefinition(val name: String, val description: String, val parametersJsonSchema: String)
data class AgentLLMRequest(
    val systemPrompt: String,
    val messages: List<AgentChatMessage>,
    val model: String? = null,
    val maxTokens: Int = 96,
    val tools: List<AgentToolDefinition> = emptyList()
)
data class AgentLLMResponse(
    val content: String,
    val tokensUsed: Int,
    val model: String,
    val provider: String,
    val latencyMs: Long
)

/**
 * Local provider backed by the repository's existing RWKV.cpp engine.
 * No LiteRT-LM dependency is used, keeping the provider viable on armeabi-v7a.
 */
class LlamaAgentProvider(
    private val engine: RwkvNativeEngine = RwkvNativeEngine()
) : AgentLLMProvider {
    override val name: String = "RWKV.cpp (local)"
    override val availableModels: List<String>
        get() = if (engine.isModelLoaded()) listOf("loaded-rwkv") else emptyList()

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching { engine.isModelLoaded() }.getOrDefault(false)
    }

    override suspend fun complete(request: AgentLLMRequest): AgentLLMResponse =
        withContext(Dispatchers.IO) {
            check(engine.isModelLoaded()) {
                "No RWKV model is loaded. Load a model in the app before selecting the local agent provider."
            }
            val userIndex = request.messages.indexOfLast { it.role.equals("user", ignoreCase = true) }
            require(userIndex >= 0) { "The local provider requires at least one user message." }
            val userText = request.messages[userIndex].content
            val history = request.messages.take(userIndex).map {
                RwkvNativeEngine.Turn(it.role, it.content)
            }
            val toolContext = if (request.tools.isEmpty()) "" else buildString {
                append("\n\n[AVAILABLE TOOLS — emit a JSON tool request when appropriate]\n")
                request.tools.forEach { tool ->
                    append("- ").append(tool.name).append(": ").append(tool.description).append('\n')
                    append("  JSON Schema: ").append(tool.parametersJsonSchema).append('\n')
                }
            }
            val maxTokens = request.maxTokens.coerceIn(1, RwkvNativeEngine.MAX_NATIVE_GENERATION_TOKENS)
            val started = System.nanoTime()
            val output = engine.nativeGenerateChat(
                request.systemPrompt + toolContext,
                engine.encodeHistory(history),
                userText,
                maxTokens
            )
            check(!output.startsWith("ERROR:")) { output.removePrefix("ERROR:").trim() }
            AgentLLMResponse(
                content = output,
                tokensUsed = (output.length / 4).coerceAtLeast(1),
                model = request.model ?: "loaded-rwkv",
                provider = name,
                latencyMs = (System.nanoTime() - started) / 1_000_000L
            )
        }

    override fun streamComplete(request: AgentLLMRequest): Flow<String> = channelFlow {
        check(engine.isModelLoaded()) {
            "No RWKV model is loaded. Load a model in the app before selecting the local agent provider."
        }
        val userIndex = request.messages.indexOfLast { it.role.equals("user", ignoreCase = true) }
        require(userIndex >= 0) { "The local provider requires at least one user message." }
        val history = request.messages.take(userIndex).map {
            RwkvNativeEngine.Turn(it.role, it.content)
        }
        val toolContext = if (request.tools.isEmpty()) "" else buildString {
            append("\n\n[AVAILABLE TOOLS — emit a JSON tool request when appropriate]\n")
            request.tools.forEach { tool ->
                append("- ").append(tool.name).append(": ").append(tool.description).append('\n')
                append("  JSON Schema: ").append(tool.parametersJsonSchema).append('\n')
            }
        }
        var nativeError: String? = null
        withContext(Dispatchers.IO) {
            engine.generateChatStreaming(
                request.systemPrompt + toolContext,
                engine.encodeHistory(history),
                request.messages[userIndex].content,
                request.maxTokens.coerceIn(1, RwkvNativeEngine.MAX_NATIVE_GENERATION_TOKENS)
            ) { token ->
                when {
                    token == "\u0001DONE" -> Unit
                    token.startsWith("\u0001ERROR:") -> nativeError = token.removePrefix("\u0001ERROR:").trim()
                    else -> trySend(token)
                }
            }
        }
        nativeError?.let { throw IllegalStateException(it) }
    }
}
