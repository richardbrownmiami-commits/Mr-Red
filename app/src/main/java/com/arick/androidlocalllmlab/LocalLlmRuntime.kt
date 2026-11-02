package com.arick.androidlocalllmlab

import android.os.SystemClock

data class ModelPreparationMetrics(
    val modelLoadMillis: Long,
    val contextCreateMillis: Long,
    val actualNctx: Int
)

class LocalLlmRuntime : AutoCloseable {
    private var nativeHandle: Long = 0L
    private var cpuThreads: Int = 1
    val isCreated: Boolean
        get() = nativeHandle != 0L

    fun create() {
        check(nativeHandle == 0L) { "Runtime already exists" }
        nativeHandle = NativeLlmBridge.nativeCreateRuntime()
    }

    override fun close() {
        if (nativeHandle != 0L) {
            NativeLlmBridge.nativeReleaseRuntime(nativeHandle)
            nativeHandle = 0L
        }
    }

    fun loadModel(modelPath: String): Boolean {
        check(nativeHandle != 0L) { "Runtime has not been created" }
        return NativeLlmBridge.nativeLoadModel(nativeHandle, modelPath)
    }

    fun unloadModel() {
        check(nativeHandle != 0L) { "Runtime has not been created" }
        NativeLlmBridge.nativeUnloadModel(nativeHandle)
    }

    fun modelDescription(): String {
        check(nativeHandle != 0L) { "Runtime has not been created" }
        return NativeLlmBridge.nativeModelDescription(nativeHandle)
    }

    fun modelMetadata(key: String): String {
        check(nativeHandle != 0L) { "Runtime has not been created" }
        return NativeLlmBridge.nativeModelMetadata(nativeHandle, key)
    }

    fun createContext(requestedNctx: Int): Int {
        check(nativeHandle != 0L) { "Runtime has not been created" }
        check(requestedNctx > 0) { "n_ctx must be positive" }

        val actualNctx = NativeLlmBridge.nativeCreateContext(
            nativeHandle,
            requestedNctx
        )
        if (actualNctx > 0) {
            NativeLlmBridge.nativeSetThreads(nativeHandle, cpuThreads, cpuThreads)
        }
        return actualNctx
    }

    fun releaseContext() {
        check(nativeHandle != 0L) { "Runtime has not been created" }
        NativeLlmBridge.nativeReleaseContext(nativeHandle)
    }

    fun resetContext() {
        check(nativeHandle != 0L) { "Runtime has not been created" }
        NativeLlmBridge.nativeResetContext(nativeHandle)
    }

    fun requestStop() {
        if (nativeHandle != 0L) {
            NativeLlmBridge.nativeRequestStop(nativeHandle)
        }
    }

    fun resetStopRequest() {
        check(nativeHandle != 0L) { "Runtime has not been created" }
        NativeLlmBridge.nativeResetStopRequest(nativeHandle)
    }

    fun createIfNeeded() {
        if (nativeHandle == 0L) {
            nativeHandle = NativeLlmBridge.nativeCreateRuntime()
        }
    }

    fun prepareModel(
        modelPath: String,
        requestedNctx: Int,
        cpuThreads: Int,
        onModelLoaded: () -> Unit = {}
    ): ModelPreparationMetrics {
        this.cpuThreads = cpuThreads
        createIfNeeded()

        // Release old context and model before switching, avoiding leaked native memory.
        unloadModel()

        val modelLoadStartedAt = SystemClock.elapsedRealtime()
        if (!loadModel(modelPath)) {
            return ModelPreparationMetrics(
                modelLoadMillis = SystemClock.elapsedRealtime() - modelLoadStartedAt,
                contextCreateMillis = 0L,
                actualNctx = 0
            )
        }
        val modelLoadMillis = SystemClock.elapsedRealtime() - modelLoadStartedAt
        onModelLoaded()

        val contextCreateStartedAt = SystemClock.elapsedRealtime()
        val actualNctx = createContext(requestedNctx)
        val contextCreateMillis = SystemClock.elapsedRealtime() - contextCreateStartedAt
        if (actualNctx == 0) {
            // Unload model if context creation fails, preventing inconsistent UI state.
            unloadModel()
        }

        return ModelPreparationMetrics(
            modelLoadMillis = modelLoadMillis,
            contextCreateMillis = contextCreateMillis,
            actualNctx = actualNctx
        )
    }

    fun recreateContext(requestedNctx: Int): Int {
        check(nativeHandle != 0L) { "Runtime has not been created" }
        check(requestedNctx > 0) { "n_ctx must be positive" }

        // Recreate inference context: model weights remain in native memory, KV cache is freed.
        releaseContext()
        return createContext(requestedNctx)
    }

    /**
     * llama.cpp differentiates single-token decode and batched prefill thread counts.
     * Using the same value for both decode and prefill threads for stability.
     */
    fun setCpuThreads(threads: Int): Int {
        check(threads > 0) { "cpu threads must be positive" }
        cpuThreads = threads
        if (nativeHandle == 0L) {
            return cpuThreads
        }
        return NativeLlmBridge.nativeSetThreads(nativeHandle, threads, threads)
    }

    fun tokenize(text: String): IntArray {
        check(nativeHandle != 0L) { "Runtime has not been created" }

        return NativeLlmBridge.nativeTokenize(
            handle = nativeHandle,
            text = text
        )
    }

    /**
     * Applies GGUF Chat Template first, then computes actual prompt token count.
     * Token count used for context history trimming.
     */
    fun countChatPromptTokens(messages: List<ChatMessage>): Int {
        check(nativeHandle != 0L) { "Runtime has not been created" }
        check(messages.isNotEmpty()) { "messages must not be empty" }

        return NativeLlmBridge.nativeCountChatPromptTokens(
            handle = nativeHandle,
            roles = messages.map { message ->
                when (message.role) {
                    MessageRole.System -> "system"
                    MessageRole.User -> "user"
                    MessageRole.Assistant -> "assistant"
                }
            }.toTypedArray(),
            contents = messages.map(ChatMessage::toNativeContent).toTypedArray()
        )
    }

    fun prefill(tokenIds: IntArray): Int {
        check(nativeHandle != 0L) { "Runtime has not been created" }

        return NativeLlmBridge.nativePrefill(
            handle = nativeHandle,
            tokenIds = tokenIds
        )
    }

    fun sampleNextToken(): Int {
        check(nativeHandle != 0L) { "Runtime has not been created" }
        return NativeLlmBridge.nativeSampleNextToken(nativeHandle)
    }

    fun tokenToPiece(tokenId: Int): String {
        check(nativeHandle != 0L) { "Runtime has not been created" }
        return NativeLlmBridge.nativeTokenToPiece(nativeHandle, tokenId)
    }

    fun generate(
        messages: List<ChatMessage>,
        inferenceConfig: InferenceConfig,
        onPrompt: (String, Int) -> Unit,
        onToken: (String) -> Unit,
        onMetrics: (GenerationMetrics) -> Unit
    ): String {
        check(nativeHandle != 0L) { "Runtime has not been created" }
        check(messages.isNotEmpty()) { "messages must not be empty" }
        check(inferenceConfig.maxTokens > 0) { "maxTokens must be positive" }

        return NativeLlmBridge.nativeGenerate(
            handle = nativeHandle,
            roles = messages.map { message ->
                when (message.role) {
                    MessageRole.System -> "system"
                    MessageRole.User -> "user"
                    MessageRole.Assistant -> "assistant"
                }
            }.toTypedArray(),
            contents = messages.map(ChatMessage::toNativeContent).toTypedArray(),
            maxTokens = inferenceConfig.maxTokens,
            temperature = inferenceConfig.temperature,
            topK = inferenceConfig.topK,
            topP = inferenceConfig.topP,
            minP = inferenceConfig.minP,
            repeatPenalty = inferenceConfig.repeatPenalty,
            seed = inferenceConfig.seed,
            generationCallback = object : NativeGenerationCallback {
                override fun onPrompt(prompt: String, tokenCount: Int) =
                    onPrompt(prompt, tokenCount)

                override fun onToken(piece: String) = onToken(piece)

                override fun onMetrics(
                    generatedTokenCount: Int,
                    reusedPromptTokenCount: Int,
                    prefillMillis: Long,
                    firstTokenMillis: Long,
                    decodeMillis: Long,
                    totalMillis: Long
                ) = onMetrics(
                    GenerationMetrics(
                        generatedTokenCount = generatedTokenCount,
                        reusedPromptTokenCount = reusedPromptTokenCount,
                        prefillMillis = prefillMillis,
                        firstTokenMillis = firstTokenMillis,
                        decodeMillis = decodeMillis,
                        totalMillis = totalMillis
                    )
                )
            }
        )
    }
}

/** Assistant trace for native runtime; UI displays reasoning separately. */
private fun ChatMessage.toNativeContent(): String {
    if (role != MessageRole.Assistant || reasoningContent.isBlank()) {
        return content
    }
    return "<think>\n$reasoningContent\n</think>\n\n$content"
}

/**
 * Sampler configuration. Greedy sampling is used when temperature is 0;
 * other parameters do not participate during greedy mode.
 */
data class InferenceConfig(
    val temperature: Float = 0f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.10f,
    val seed: Int = 42,
    // Default output ceiling of 2048 tokens.
    val maxTokens: Int = 512
) {
    val isGreedy: Boolean
        get() = temperature <= 0f
}

/** Native inference duration in milliseconds. */
data class GenerationMetrics(
    val generatedTokenCount: Int,
    /** Number of tokens already cached in native KV cache. */
    val reusedPromptTokenCount: Int,
    val prefillMillis: Long,
    val firstTokenMillis: Long,
    val decodeMillis: Long,
    val totalMillis: Long
) {
    val decodeTokensPerSecond: Double
        get() = if (decodeMillis <= 0L) 0.0 else generatedTokenCount * 1_000.0 / decodeMillis
}

enum class SamplingPreset(
    val label: String,
    val config: InferenceConfig
) {
    Stable("Stable", InferenceConfig(temperature = 0f, maxTokens = 512)),
    Balanced("Balanced", InferenceConfig(temperature = 0.7f, topK = 40, topP = 0.95f, minP = 0.05f, repeatPenalty = 1.10f, seed = -1, maxTokens = 512)),
    Creative("Creative", InferenceConfig(temperature = 1.0f, topK = 80, topP = 0.95f, minP = 0.05f, repeatPenalty = 1.05f, seed = -1, maxTokens = 512)),
    Reproducible("Reproducible", InferenceConfig(temperature = 0.7f, topK = 40, topP = 0.95f, minP = 0.05f, repeatPenalty = 1.10f, seed = 42, maxTokens = 512));

    fun configFor(nCtx: Int): InferenceConfig = config.copy(maxTokens = config.maxTokens.coerceIn(32, maxOf(32, nCtx - 64)))
}
