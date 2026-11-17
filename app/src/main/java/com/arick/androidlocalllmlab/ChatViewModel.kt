package com.arick.androidlocalllmlab

import android.app.Application
import android.net.Uri
import android.os.Debug
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Compose renders state only; does not directly manage native runtime or file I/O.
sealed interface ChatUiState {
    data object Idle : ChatUiState

    data class Preparing(
        val message: String
    ) : ChatUiState

    data class Ready(
        val modelFile: File,
        val nCtx: Int,
        val modelDescription: String,
        val modelName: String
    ) : ChatUiState

    data class Error(
        val message: String
    ) : ChatUiState
}

enum class MessageRole {
    System,
    User,
    Assistant
}

data class ChatMessage(
    val role: MessageRole,
    val content: String,
    // Reasoning is kept separate from standard response and rendered as a collapsible section.
    val reasoningContent: String = "",
    // Expanded state is tracked in session data so streaming changes do not reset it.
    val isReasoningExpanded: Boolean = true,
    // Actual duration from first reasoning token to answer body start.
    val reasoningDurationMillis: Long? = null,
    // Tool execution records reflect actual Kotlin tool invocations.
    val agentTrace: List<AgentTraceItem> = emptyList(),
    val isAgentTraceExpanded: Boolean = false,
    val agentDurationMillis: Long? = null,
    // Search sources provided by tool result.
    val sourceLinks: List<SourceLink> = emptyList()
)

data class AgentTraceItem(val text: String)

data class MemorySnapshot(
    val javaUsedBytes: Long,
    val javaMaxBytes: Long,
    val nativeAllocatedBytes: Long,
    val totalPssKb: Int,
    val nativePssKb: Int
)

data class RuntimeMemorySnapshots(
    val beforeLoad: MemorySnapshot? = null,
    val afterModelLoaded: MemorySnapshot? = null,
    val afterContextCreated: MemorySnapshot? = null,
    val afterGeneration: MemorySnapshot? = null,
    val afterModelReleased: MemorySnapshot? = null
) {
    val current: MemorySnapshot?
        get() = afterModelReleased ?: afterGeneration ?: afterContextCreated ?: afterModelLoaded ?: beforeLoad
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        val DEFAULT_CPU_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }

    // Runtime holds native JNI handle with ViewModel lifecycle.
    private val runtime = LocalLlmRuntime()
    val toolExecutor = AndroidToolExecutor(application)
    private val modelFileImporter = ModelFileImporter(application)
    private val selectedModelStore = SelectedModelStore(application)

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // System prompt is session configuration, not rendered as a chat bubble.
    // Callback after applying native chat template, showing actual formatted prompt.
    // Default context window sizing.
    private val _requestedNctx = MutableStateFlow(512)
    val requestedNctx: StateFlow<Int> = _requestedNctx.asStateFlow()

    // CPU threads bounded by available processor cores.
    private val _cpuThreads = MutableStateFlow(DEFAULT_CPU_THREADS)
    val cpuThreads: StateFlow<Int> = _cpuThreads.asStateFlow()

    private val _inferenceConfig = MutableStateFlow(InferenceConfig(maxTokens = 256))
    val inferenceConfig: StateFlow<InferenceConfig> = _inferenceConfig.asStateFlow()

    private val _generationProgress = MutableStateFlow<String?>(null)
    val generationProgress: StateFlow<String?> = _generationProgress.asStateFlow()

    private val _generationMetrics = MutableStateFlow<GenerationMetrics?>(null)
    val generationMetrics: StateFlow<GenerationMetrics?> = _generationMetrics.asStateFlow()

    private val _modelPreparationMetrics = MutableStateFlow<ModelPreparationMetrics?>(null)
    val modelPreparationMetrics: StateFlow<ModelPreparationMetrics?> = _modelPreparationMetrics.asStateFlow()

    private val _memorySnapshots = MutableStateFlow(RuntimeMemorySnapshots())
    val memorySnapshots: StateFlow<RuntimeMemorySnapshots> = _memorySnapshots.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _isStopRequested = MutableStateFlow(false)
    val isStopRequested: StateFlow<Boolean> = _isStopRequested.asStateFlow()

    private val _generationError = MutableStateFlow<String?>(null)
    val generationError: StateFlow<String?> = _generationError.asStateFlow()

    init {
        // Models in filesDir persist across app restarts.
        restoreLastModel()
    }

    fun loadModel(uri: Uri) {
        if (_uiState.value is ChatUiState.Preparing) {
            return
        }

        viewModelScope.launch {
            var importedFile: File? = null
            try {
                _uiState.value = ChatUiState.Preparing("Importing model file…")
                val modelFile = modelFileImporter.importModel(uri)
                importedFile = modelFile

                // Save model for auto-restore only after successful initialization.
                prepareModel(modelFile)
                selectedModelStore.save(modelFile)
                modelFileImporter.cleanOrphanModels(exceptFile = modelFile)
            } catch (error: Throwable) {
                importedFile?.delete()
                _uiState.value = ChatUiState.Error(
                    error.message ?: "Failed to load model"
                )
            }
        }
    }

    private fun restoreLastModel() {
        val modelFile = selectedModelStore.selectedModelFile() ?: return
        if (!modelFile.isFile) {
            // Avoid invalid path attempts if app data was cleared.
            selectedModelStore.clear()
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = ChatUiState.Preparing("Restoring previous model…")
                prepareModel(modelFile)
            } catch (error: Throwable) {
                _uiState.value = ChatUiState.Error(
                    error.message ?: "Failed to restore previous model"
                )
            }
        }
    }

    private fun defaultMaxTokensFor(nCtx: Int): Int = when {
        nCtx <= 512 -> 256
        nCtx <= 1024 -> 512
        nCtx <= 2048 -> 1024
        else -> 2048
    }

    private suspend fun prepareModel(modelFile: File) {
        _uiState.value = ChatUiState.Preparing("Loading model and creating context…")
        val beforeLoad = takeMemorySnapshot()
        var afterModelLoaded: MemorySnapshot? = null
        val preparationMetrics = withContext(Dispatchers.Default) {
            // Loading model and allocating context on background dispatcher.
            runtime.prepareModel(
                modelPath = modelFile.absolutePath,
                requestedNctx = _requestedNctx.value,
                cpuThreads = _cpuThreads.value,
                onModelLoaded = { afterModelLoaded = takeMemorySnapshot() }
            )
        }

        check(preparationMetrics.actualNctx > 0) { "Failed to create model or context" }
        _modelPreparationMetrics.value = preparationMetrics
        _memorySnapshots.value = RuntimeMemorySnapshots(
            beforeLoad = beforeLoad,
            afterModelLoaded = afterModelLoaded,
            afterContextCreated = takeMemorySnapshot()
        )
        val modelDescription = withContext(Dispatchers.Default) {
            runtime.modelDescription()
        }
        val modelName = withContext(Dispatchers.Default) {
            runtime.modelMetadata("general.name")
        }
        _generationProgress.value = null
        _generationMetrics.value = null
        _generationError.value = null
        val safeMaxTokens = defaultMaxTokensFor(preparationMetrics.actualNctx)
        _inferenceConfig.value = _inferenceConfig.value.copy(
            maxTokens = _inferenceConfig.value.maxTokens.coerceAtMost(safeMaxTokens)
        )
        _uiState.value = ChatUiState.Ready(
            modelFile,
            preparationMetrics.actualNctx,
            modelDescription,
            modelName
        )
    }

    /**
     * Minimal GGUF path: the current user question is the only message sent to
     * llama.cpp. No persona/system prompt, prior turns, summary, memory, or
     * app-side tool orchestration is added to the model input.
     */
    fun send(prompt: String) {
        if (prompt.isBlank() || _isGenerating.value) return
        val ready = _uiState.value as? ChatUiState.Ready ?: run {
            _generationError.value = "Load a GGUF model before sending a question."
            return
        }

        val userMessage = ChatMessage(MessageRole.User, prompt)
        _messages.value = listOf(userMessage, ChatMessage(MessageRole.Assistant, ""))
        _generationError.value = null
        _generationProgress.value = "Running GGUF inference…"
        _generationMetrics.value = null
        runtime.resetStopRequest()
        _isStopRequested.value = false
        _isGenerating.value = true

        val config = _inferenceConfig.value.copy(
            maxTokens = _inferenceConfig.value.maxTokens.coerceAtMost((ready.nCtx - 1).coerceAtLeast(1))
        )
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    runtime.generate(
                        messages = listOf(userMessage),
                        inferenceConfig = config,
                        onPrompt = { _, tokenCount ->
                            _generationProgress.value = "Prefilling $tokenCount tokens…"
                        },
                        onToken = { piece ->
                            _generationProgress.value = "Generating response…"
                            val current = _messages.value
                            if (current.isNotEmpty()) {
                                val last = current.last()
                                _messages.value = current.dropLast(1) + last.copy(content = last.content + piece)
                            }
                        },
                        onMetrics = { metrics ->
                            _generationMetrics.value = metrics
                            _memorySnapshots.value = _memorySnapshots.value.copy(
                                afterGeneration = takeMemorySnapshot(),
                                afterModelReleased = null
                            )
                        }
                    )
                }
                if (_messages.value.lastOrNull()?.content.isNullOrBlank() && !_isStopRequested.value) {
                    error("Model returned no content")
                }
            } catch (error: Throwable) {
                _generationError.value = error.message ?: "Generation failed"
            } finally {
                _generationProgress.value = null
                _isGenerating.value = false
                _isStopRequested.value = false
            }
        }
    }

    fun toggleReasoning(messageIndex: Int) {
        if (messageIndex !in _messages.value.indices) {
            return
        }
        _messages.value = _messages.value.mapIndexed { index, message ->
            if (index == messageIndex && message.reasoningContent.isNotBlank()) {
                message.copy(isReasoningExpanded = !message.isReasoningExpanded)
            } else {
                message
            }
        }
    }

    fun toggleAgentTrace(messageIndex: Int) {
        if (messageIndex !in _messages.value.indices) {
            return
        }
        _messages.value = _messages.value.mapIndexed { index, message ->
            if (index == messageIndex && message.agentTrace.isNotEmpty()) {
                message.copy(isAgentTraceExpanded = !message.isAgentTraceExpanded)
            } else {
                message
            }
        }
    }

    fun updateContextWindow(requestedNctx: Int) {
        if (requestedNctx <= 0 || _isGenerating.value || _uiState.value is ChatUiState.Preparing) {
            return
        }

        val currentState = _uiState.value as? ChatUiState.Ready
        if (currentState == null) {
            // Save requested n_ctx for subsequent model loads.
            _requestedNctx.value = requestedNctx
            return
        }
        if (currentState.nCtx == requestedNctx) {
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = ChatUiState.Preparing("Rebuilding context…")
                val actualNctx = withContext(Dispatchers.Default) {
                    runtime.recreateContext(requestedNctx)
                }
                check(actualNctx > 0) { "Context allocation failed" }

                _requestedNctx.value = requestedNctx
                // Adjust output reservation when n_ctx shrinks.
                val safeMaxTokens = defaultMaxTokensFor(actualNctx)
                _inferenceConfig.value = _inferenceConfig.value.copy(
                    maxTokens = _inferenceConfig.value.maxTokens.coerceAtMost(safeMaxTokens)
                )
                _uiState.value = ChatUiState.Ready(
                    currentState.modelFile,
                    actualNctx,
                    currentState.modelDescription,
                    currentState.modelName
                )
            } catch (error: Throwable) {
                _uiState.value = ChatUiState.Error(
                    error.message ?: "Failed to rebuild context"
                )
            }
        }
    }

    /** Updates decode and prefill thread counts in llama.cpp without recreating context. */
    fun updateCpuThreads(threads: Int) {
        if (threads <= 0 || _isGenerating.value || _uiState.value is ChatUiState.Preparing) {
            return
        }
        _cpuThreads.value = threads
        if (_uiState.value is ChatUiState.Ready) {
            viewModelScope.launch {
                withContext(Dispatchers.Default) {
                    runtime.setCpuThreads(threads)
                }
            }
        }
    }

    fun updateInferenceConfig(config: InferenceConfig) {
        if (_isGenerating.value) {
            return
        }
        require(config.temperature >= 0f) { "temperature must not be negative" }
        require(config.topK >= 0) { "topK must not be negative" }
        require(config.topP in 0f..1f) { "topP must be between 0 and 1" }
        require(config.minP in 0f..1f) { "minP must be between 0 and 1" }
        require(config.repeatPenalty > 0f) { "repeatPenalty must be positive" }
        require(config.maxTokens > 0) { "maxTokens must be positive" }
        val actualNctx = (_uiState.value as? ChatUiState.Ready)?.nCtx
        val safeMaxTokens = if (actualNctx != null) {
            config.maxTokens.coerceIn(32, (actualNctx - 48).coerceAtLeast(32))
        } else {
            config.maxTokens
        }
        _inferenceConfig.value = config.copy(maxTokens = safeMaxTokens)
    }

    fun stopGenerating() {
        if (!_isGenerating.value || _isStopRequested.value) {
            return
        }

        _isStopRequested.value = true
        // Set native atomic cancellation flag so native loop safely exits.
        // Native generation terminates at next safe token boundary.
        runtime.requestStop()
    }

    /** Clears the current, in-memory question and answer; retains the loaded model. */
    fun clearConversation() {
        if (_isGenerating.value || _uiState.value !is ChatUiState.Ready) {
            return
        }

        _messages.value = emptyList()
        clearLastPromptState()
        _generationError.value = null
    }

    /**
     * Clears the native KV cache while keeping the loaded model.
     */
    fun resetContext() {
        if (_isGenerating.value || _uiState.value !is ChatUiState.Ready) {
            return
        }

        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                runtime.resetContext()
            }
            clearLastPromptState()
            _generationError.value = null
        }
    }

    fun releaseModel() {
        if (_uiState.value is ChatUiState.Preparing || _isGenerating.value) {
            return
        }

        viewModelScope.launch {
            _uiState.value = ChatUiState.Preparing("Unloading model…")
            withContext(Dispatchers.Default) {
                // Native cleanup order: Context -> Model -> llama backend.
                runtime.close()
            }
            _memorySnapshots.value = _memorySnapshots.value.copy(
                afterModelReleased = takeMemorySnapshot()
            )
            selectedModelStore.clear()
            _messages.value = emptyList()
            clearLastPromptState()
            _generationError.value = null
            _uiState.value = ChatUiState.Idle
        }
    }

    private fun clearLastPromptState() {
        _generationProgress.value = null
        _generationMetrics.value = null
    }

    private fun takeMemorySnapshot(): MemorySnapshot {
        val runtime = Runtime.getRuntime()
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        return MemorySnapshot(
            javaUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            javaMaxBytes = runtime.maxMemory(),
            nativeAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
            totalPssKb = memoryInfo.totalPss,
            nativePssKb = memoryInfo.nativePss
        )
    }

    override fun onCleared() {
        // Release native heap memory on ViewModel disposal.
        runtime.close()
    }
}
