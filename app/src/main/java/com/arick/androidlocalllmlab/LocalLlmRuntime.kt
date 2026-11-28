package com.arick.androidlocalllmlab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalLlmRuntime {
    @Volatile private var loaded = false

    suspend fun load(path: String, contextSize: Int = 2048) = withContext(Dispatchers.IO) {
        check(!loaded) { "A model is already loaded" }
        loaded = NativeLlmBridge.load(path, contextSize)
        loaded
    }

    suspend fun primeSystemPrompt(prompt: String): Boolean = withContext(Dispatchers.IO) {
        check(loaded) { "Load a GGUF model first" }
        NativeLlmBridge.primeSystemPrompt(prompt)
    }

    suspend fun answer(prompt: String, maxTokens: Int = 256): String = withContext(Dispatchers.IO) {
        check(loaded) { "Load a GGUF model first" }
        NativeLlmBridge.generate(prompt, maxTokens)
    }

    suspend fun clearConversationCache() = withContext(Dispatchers.IO) {
        if (loaded) NativeLlmBridge.clearConversation()
    }

    suspend fun unload() = withContext(Dispatchers.IO) {
        if (loaded) {
            NativeLlmBridge.unload()
            loaded = false
        }
    }

    val isLoaded: Boolean get() = loaded
}
