package com.redmi9.offlineai

/**
 * Shared native-engine facade for agent/provider integrations.
 *
 * The UI and agent must use this facade against the same process-wide llama.cpp
 * model/context. A GGUF model must be loaded before generateChat() is called.
 * This facade deliberately stays independent of any one agent framework.
 */
class LlamaNativeEngine {
    external fun nativeEngineInfo(): String
    external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): Boolean
    external fun nativeGenerateChat(
        systemContext: String,
        historyEncoded: String,
        userText: String,
        maxTokens: Int
    ): String
    external fun nativeGenerateChatStreaming(systemContext: String, historyEncoded: String, userText: String, maxTokens: Int)
    external fun nativeCancelGeneration()
    external fun nativeUnloadModel()
    external fun nativeModelInfo(): String
    external fun nativeMemoryBytes(): Long

    @Volatile private var tokenListener: ((String) -> Unit)? = null

    /** JNI callback used by llama.cpp for token chunks and terminal markers. */
    fun onNativeToken(token: String) {
        tokenListener?.invoke(token)
    }

    fun generateChatStreaming(
        systemContext: String,
        historyEncoded: String,
        userText: String,
        maxTokens: Int,
        onToken: (String) -> Unit
    ) {
        tokenListener = onToken
        try {
            nativeGenerateChatStreaming(systemContext, historyEncoded, userText, maxTokens)
        } finally {
            tokenListener = null
        }
    }

    fun isModelLoaded(): Boolean = !nativeModelInfo().startsWith("No GGUF model loaded")

    /**
     * Converts turns to the native engine's role/content wire format.
     * Control separators are stripped from input to avoid corrupting turn boundaries.
     */
    fun encodeHistory(turns: List<Turn>): String = turns.joinToString("\u001e") { turn ->
        val role = if (turn.role.equals("user", ignoreCase = true)) "user" else "assistant"
        val content = turn.content.replace("\u001e", " ").replace("\u001f", " ")
        "$role\u001f$content"
    }

    data class Turn(val role: String, val content: String)

    companion object {
        init {
            System.loadLibrary("offlineai")
        }

        const val MAX_NATIVE_GENERATION_TOKENS = 96
    }
}
