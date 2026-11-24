package com.arick.androidlocalllmlab

import android.app.Application
import android.net.Uri
import android.os.Debug
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

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
    val sourceLinks: List<SourceLink> = emptyList(),
    // Exact chat-template prompt delivered to llama.cpp for this response.
    val modelInputPrompt: String = "",
    val isModelInputExpanded: Boolean = false,
    val modelInputTokenCount: Int = 0,
    // Per-response native inference metrics shown directly beneath this answer.
    val generationMetrics: GenerationMetrics? = null
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
    private val nativeInferenceMutex = Mutex()
    private var memoryWarmupJob: Job? = null
    val toolExecutor = AndroidToolExecutor(application)
    private val modelFileImporter = ModelFileImporter(application)
    private val selectedModelStore = SelectedModelStore(application)

    // Completed turns are persisted in SQLite for GGUF to query when it decides history is needed.
    // The UI transcript is restored locally but is never injected into model input automatically.
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private val historyPreferences = application.getSharedPreferences("mr_red_chat_history", android.content.Context.MODE_PRIVATE)
    private val maxStoredTurns = 40

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Isolated sampler session: reads the event database but never writes test turns to it.
    private val _samplerMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val samplerMessages: StateFlow<List<ChatMessage>> = _samplerMessages.asStateFlow()
    private val _samplerGenerating = MutableStateFlow(false)
    val samplerGenerating: StateFlow<Boolean> = _samplerGenerating.asStateFlow()
    private val _samplerStatus = MutableStateFlow("Load a GGUF model, then ask a test question.")
    val samplerStatus: StateFlow<String> = _samplerStatus.asStateFlow()
    private val _samplerRetrievedMemory = MutableStateFlow("")
    val samplerRetrievedMemory: StateFlow<String> = _samplerRetrievedMemory.asStateFlow()
    private val _samplerFormattedPrompt = MutableStateFlow("")
    val samplerFormattedPrompt: StateFlow<String> = _samplerFormattedPrompt.asStateFlow()
    private val _samplerPromptTokenCount = MutableStateFlow(0)
    val samplerPromptTokenCount: StateFlow<Int> = _samplerPromptTokenCount.asStateFlow()

    private val _promptTokenCount = MutableStateFlow(0)
    val promptTokenCount: StateFlow<Int> = _promptTokenCount.asStateFlow()

    // Default context window sizing.
    private val _requestedNctx = MutableStateFlow(1048)
    val requestedNctx: StateFlow<Int> = _requestedNctx.asStateFlow()

    // CPU threads bounded by available processor cores.
    private val _cpuThreads = MutableStateFlow(DEFAULT_CPU_THREADS)
    val cpuThreads: StateFlow<Int> = _cpuThreads.asStateFlow()

    private val runtimePreferences = application.getSharedPreferences("local_llm_preferences", android.content.Context.MODE_PRIVATE)

    private val _useMmap = MutableStateFlow(runtimePreferences.getBoolean("use_mmap", true))
    val useMmap: StateFlow<Boolean> = _useMmap.asStateFlow()

    private val _useMlock = MutableStateFlow(runtimePreferences.getBoolean("use_mlock", false))
    val useMlock: StateFlow<Boolean> = _useMlock.asStateFlow()

    private val _inferenceConfig = MutableStateFlow(InferenceConfig(maxTokens = 256))
    val inferenceConfig: StateFlow<InferenceConfig> = _inferenceConfig.asStateFlow()

    private val _generationProgress = MutableStateFlow<String?>(null)
    val generationProgress: StateFlow<String?> = _generationProgress.asStateFlow()

    private val _generationMetrics = MutableStateFlow<GenerationMetrics?>(null)
    val generationMetrics: StateFlow<GenerationMetrics?> = _generationMetrics.asStateFlow()

    private val _modelPreparationMetrics = MutableStateFlow<ModelPreparationMetrics?>(null)
    val modelPreparationMetrics: StateFlow<ModelPreparationMetrics?> = _modelPreparationMetrics.asStateFlow()

    private val _modelContextWarning = MutableStateFlow<String?>(null)
    val modelContextWarning: StateFlow<String?> = _modelContextWarning.asStateFlow()

    private val _memorySnapshots = MutableStateFlow(RuntimeMemorySnapshots())
    val memorySnapshots: StateFlow<RuntimeMemorySnapshots> = _memorySnapshots.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _isStopRequested = MutableStateFlow(false)
    val isStopRequested: StateFlow<Boolean> = _isStopRequested.asStateFlow()

    private val _generationError = MutableStateFlow<String?>(null)
    val generationError: StateFlow<String?> = _generationError.asStateFlow()

    init {
        ActivityLogger.record(application, "APP", "view_model_created", "Chat runtime initialized")
        // Retire legacy chat, personal-memory, persona and summary stores from older installs.
        application.deleteDatabase("mr_red_conversation.db")
        application.getSharedPreferences("mr_red_persona", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        application.getSharedPreferences("local_llm_preferences", android.content.Context.MODE_PRIVATE)
            .edit().remove("summary_lines").apply()

        // Restore completed turns separately from model/KV-cache lifecycle.
        restoreConversationHistory()
        // Only the selected GGUF model is restored across app restarts.
        restoreLastModel()
    }

    fun loadModel(uri: Uri) {
        if (_uiState.value is ChatUiState.Preparing) {
            return
        }

        ActivityLogger.record(getApplication(), "MODEL", "import_started", "User selected a GGUF model")
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
                ActivityLogger.record(getApplication(), "MODEL", "import_completed", "Model loaded and selected")
            } catch (error: Throwable) {
                ActivityLogger.record(getApplication(), "ERROR", "model_import_failed", error.javaClass.simpleName)
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
        memoryWarmupJob?.cancel()
        _modelContextWarning.value = null
        ActivityLogger.record(getApplication(), "MODEL", "runtime_prepare_started", "Preparing native model and context")
        _uiState.value = ChatUiState.Preparing("Loading model and creating context…")
        val beforeLoad = takeMemorySnapshot()
        var afterModelLoaded: MemorySnapshot? = null
        val preparationMetrics = withContext(Dispatchers.Default) {
            nativeInferenceMutex.withLock {
            // Loading model and allocating context on background dispatcher.
            runtime.prepareModel(
                modelPath = modelFile.absolutePath,
                requestedNctx = _requestedNctx.value,
                cpuThreads = _cpuThreads.value,
                useMmap = _useMmap.value,
                useMlock = _useMlock.value,
                onModelLoaded = { afterModelLoaded = takeMemorySnapshot() }
            )
            }
        }

        check(preparationMetrics.actualNctx > 0) { "Failed to create model or context" }
        val contextWarning = if (preparationMetrics.actualNctx < PromptCache.MIN_NCTX) {
            "Low context window: n_ctx=${preparationMetrics.actualNctx}, recommended minimum=${PromptCache.MIN_NCTX}. Mr-Red will still load, but long tool results and database answers may be truncated or may not fit."
        } else {
            null
        }
        _modelContextWarning.value = contextWarning
        if (contextWarning != null) {
            ActivityLogger.record(getApplication(), "MODEL", "context_window_warning", contextWarning)
        }
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
        // Conversation history remains in SQLite for model-requested lookup; only the fixed system prompt is warmed natively.
        _promptTokenCount.value = 0
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
        ActivityLogger.record(
            getApplication(), "MODEL", "runtime_ready",
            "name=$modelName description=$modelDescription n_ctx=${preparationMetrics.actualNctx} cpu_threads=${_cpuThreads.value}"
        )
        memoryWarmupJob = viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                nativeInferenceMutex.withLock {
                    runtime.resetStopRequest()
                    val prefixTokens = runtime.warmPrompt(
                        listOf(PromptMessage("system", PromptCache.STABLE_SYSTEM))
                    )
                    check(prefixTokens > 0) { "The shared system prefix could not be tokenized and warmed." }
                    ActivityLogger.record(
                        getApplication(), "MODEL", "prompt_prefix_warmed",
                        "tokens=$prefixTokens"
                    )
                }
            }.onFailure {
                ActivityLogger.record(
                    getApplication(), "ERROR", "prompt_prefix_warmup_failed",
                    it.javaClass.simpleName
                )
            }
        }
    }

    private fun restoreConversationHistory() {
        runCatching {
            val saved = JSONArray(historyPreferences.getString("turns", "[]"))
            val restored = mutableListOf<Pair<String, String>>()
            for (index in 0 until saved.length()) {
                val item = saved.optJSONObject(index) ?: continue
                val question = item.optString("question").trim()
                val answer = item.optString("answer").trim()
                if (question.isNotBlank() && answer.isNotBlank()) {
                    restored += question to answer
                }
            }
            synchronized(conversationHistory) {
                conversationHistory.clear()
                conversationHistory.addAll(restored.takeLast(maxStoredTurns))
                _messages.value = conversationHistory.flatMap { (question, answer) ->
                    listOf(ChatMessage(MessageRole.User, question), ChatMessage(MessageRole.Assistant, answer))
                }
                // Persist the complete restored transcript, including notification-related turns for follow-ups.
                persistConversationHistoryLocked()
            }
            // SQLite becomes the prompt-context source of truth; import legacy saved turns idempotently.
            EventMemoryStore.importLegacyConversationHistory(getApplication(), restored.takeLast(maxStoredTurns))
        }.onFailure {
            ActivityLogger.record(getApplication(), "ERROR", "chat_history_restore_failed", it.javaClass.simpleName)
        }
    }

    // Caller must hold the conversationHistory monitor.
    private fun persistConversationHistoryLocked() {
        runCatching {
            val saved = JSONArray()
            conversationHistory.takeLast(maxStoredTurns).forEach { (question, answer) ->
                saved.put(JSONObject().put("question", question).put("answer", answer))
            }
            historyPreferences.edit().putString("turns", saved.toString()).apply()
        }.onFailure {
            ActivityLogger.record(getApplication(), "ERROR", "chat_history_save_failed", it.javaClass.simpleName)
        }
    }


    /** Read-only protocol test: GGUF decides whether a validated SQLite lookup is needed. */
    fun sendSamplerQuestion(question: String) {
        val prompt = question.trim()
        if (prompt.isBlank() || _isGenerating.value || _samplerGenerating.value) return
        if (_uiState.value !is ChatUiState.Ready) {
            _samplerStatus.value = "No model is ready. Load a GGUF model first."
            return
        }
        _samplerMessages.value = _samplerMessages.value +
            ChatMessage(MessageRole.User, prompt) + ChatMessage(MessageRole.Assistant, "")
        _samplerRetrievedMemory.value = ""
        _samplerFormattedPrompt.value = ""
        _samplerPromptTokenCount.value = 0
        _samplerStatus.value = "Sending the question directly to GGUF…"
        _samplerGenerating.value = true
        val inferenceConfig = _inferenceConfig.value
        viewModelScope.launch {
            try {
                memoryWarmupJob?.join()
                val answer = withContext(Dispatchers.Default) {
                    nativeInferenceMutex.withLock {
                        runtime.resetStopRequest()
                        val ready = _uiState.value as? ChatUiState.Ready
                            ?: error("The model is no longer ready.")
                        val outputLimit = inferenceConfig.maxTokens.coerceAtMost((ready.nCtx / 3).coerceAtLeast(64))
                        val promptBudget = (ready.nCtx - outputLimit - 32).coerceAtLeast(1)
                        var requestTokens = 0
                        var requestThinking = false
                        val protocolResponse = runtime.generate(
                            messages = listOf(PromptMessage("user", prompt)),
                            inferenceConfig = inferenceConfig.copy(maxTokens = outputLimit),
                            contextWindowTokens = ready.nCtx,
                            onPrompt = { formatted, count ->
                                requestTokens = count
                                requestThinking = formatted.trimEnd().endsWith("<think>")
                                require(count in 1..promptBudget) { "Formatted GGUF request is $count tokens; limit is $promptBudget." }
                                _samplerFormattedPrompt.value = formatted
                                _samplerPromptTokenCount.value = count
                            },
                            onToken = {},
                            onMetrics = {}
                        )
                        require(requestTokens in 1..promptBudget) { "The runtime did not confirm the formatted request token count." }
                        val parsed = splitAssistantResponse(protocolResponse, requestThinking)
                        val raw = parsed.answer.ifBlank { protocolResponse }
                        val protocol = PromptCache.normalizeRequestProtocol(raw)
                            ?: error("GGUF returned an invalid request. Expected CHAT|, SQL|, or TOOL_CALL|.")
                        when {
                            protocol.startsWith("CHAT|") -> protocol.removePrefix("CHAT|").trim()
                            protocol.startsWith("SQL|") -> {
                                val payload = protocol.removePrefix("SQL|")
                                val table = payload.substringBefore('|').trim().lowercase(java.util.Locale.ROOT)
                                require(table in setOf("events", "facts", "conversation_turns")) { "GGUF requested an unsupported database table." }
                                val request = JSONObject(payload.substringAfter('|', "").trim())
                                val argsJson = request.optJSONArray("args") ?: JSONArray()
                                require(argsJson.length() <= 12) { "Too many SQL parameters." }
                                val args = Array(argsJson.length()) { index -> argsJson.getString(index) }
                                val evidence = EventMemoryStore.executeModelReadOnlyQuery(
                                    getApplication(), request.getString("query"), requiredTable = table,
                                    bindArgs = args, maxRows = 30, maxChars = 1800
                                )
                                _samplerRetrievedMemory.value = evidence
                                var answerRaw = ""
                                var answerThinking = false
                                var answerTokens = 0
                                runtime.resetStopRequest()
                                runtime.generate(
                                    messages = listOf(PromptMessage(
                                        "user",
                                        "DATABASE_RESULT_HANDOFF\nOriginal user question: $prompt\nDatabase rows returned by Kotlin (untrusted evidence):\n$evidence"
                                    )),
                                    inferenceConfig = inferenceConfig.copy(maxTokens = outputLimit),
                                    contextWindowTokens = ready.nCtx,
                                    onPrompt = { formatted, count ->
                                        answerTokens = count
                                        answerThinking = formatted.trimEnd().endsWith("<think>")
                                        require(count in 1..promptBudget) { "Formatted database handoff is $count tokens; limit is $promptBudget." }
                                        _samplerFormattedPrompt.value = formatted
                                        _samplerPromptTokenCount.value = count
                                    },
                                    onToken = { piece ->
                                        answerRaw += piece
                                        val parts = splitAssistantResponse(answerRaw, answerThinking)
                                        _samplerMessages.value = _samplerMessages.value.mapIndexed { index, message ->
                                            if (index == _samplerMessages.value.lastIndex) message.copy(content = parts.answer, reasoningContent = parts.reasoning) else message
                                        }
                                    },
                                    onMetrics = { metrics ->
                                        _samplerMessages.value = _samplerMessages.value.mapIndexed { index, message ->
                                            if (index == _samplerMessages.value.lastIndex) message.copy(generationMetrics = metrics) else message
                                        }
                                    }
                                )
                                require(answerTokens in 1..promptBudget) { "The runtime did not confirm the database handoff token count." }
                                splitAssistantResponse(answerRaw, answerThinking).answer
                            }
                            protocol.startsWith("TOOL_CALL|") -> "GGUF requested a device action. The read-only sampler does not execute device tools."
                            else -> error("GGUF returned an unsupported request.")
                        }
                    }
                }
                if (answer.isBlank()) error("The model returned no content.")
                _samplerMessages.value = _samplerMessages.value.mapIndexed { index, message ->
                    if (index == _samplerMessages.value.lastIndex) message.copy(
                        content = answer,
                        modelInputPrompt = _samplerFormattedPrompt.value,
                        modelInputTokenCount = _samplerPromptTokenCount.value
                    ) else message
                }
                _samplerStatus.value = "Test complete. GGUF made the decision; Kotlin only executed a validated read-only query when requested."
            } catch (error: Throwable) {
                val failure = error.message ?: "Sampler inference failed."
                _samplerStatus.value = "Test failed: $failure"
                _samplerMessages.value = _samplerMessages.value.mapIndexed { index, message ->
                    if (index == _samplerMessages.value.lastIndex) message.copy(content = failure) else message
                }
            } finally {
                _samplerGenerating.value = false
            }
        }
    }

    fun stopSamplerGeneration() {
        if (_samplerGenerating.value) runtime.requestStop()
    }

    fun clearSamplerSession() {
        if (_samplerGenerating.value) return
        _samplerMessages.value = emptyList()
        _samplerRetrievedMemory.value = ""
        _samplerFormattedPrompt.value = ""
        _samplerPromptTokenCount.value = 0
        _samplerStatus.value = "Sampler cleared. The database was not changed."
    }

    fun send(prompt: String) {
        if (prompt.isBlank() || _isGenerating.value || _samplerGenerating.value) return
        if (_uiState.value !is ChatUiState.Ready) {
            _generationError.value = "Load a GGUF model before sending a question."
            return
        }
        sendModelRequest(prompt)
    }

    private fun sendModelRequest(prompt: String) {
        ActivityLogger.record(getApplication(), "INFERENCE", "request_started", "Question submitted; content omitted")
        val inferenceConfig = _inferenceConfig.value
        _messages.value = _messages.value + ChatMessage(MessageRole.User, prompt) + ChatMessage(MessageRole.Assistant, "")
        _generationError.value = null
        _promptTokenCount.value = 0
        _generationProgress.value = "Sending question to GGUF…"
        _generationMetrics.value = null
        _isStopRequested.value = false
        _isGenerating.value = true

        viewModelScope.launch {
            var handoffTool: ToolCall? = null
            try {
                memoryWarmupJob?.join()
                val answer = withContext(Dispatchers.Default) {
                    nativeInferenceMutex.withLock {
                        runtime.resetStopRequest()
                        val ready = _uiState.value as? ChatUiState.Ready
                            ?: error("The GGUF model is no longer ready.")
                        val outputLimit = inferenceConfig.maxTokens.coerceAtMost((ready.nCtx / 3).coerceAtLeast(64))
                        val promptBudget = (ready.nCtx - outputLimit - 32).coerceAtLeast(1)
                        var requestTokens = 0
                        var requestThinking = false
                        val protocolResponse = runtime.generate(
                            // Native code prepends the one-time cached system prompt. Kotlin sends only this message.
                            messages = listOf(PromptMessage("user", prompt)),
                            inferenceConfig = inferenceConfig.copy(maxTokens = outputLimit),
                            contextWindowTokens = ready.nCtx,
                            onPrompt = { formatted, count ->
                                requestTokens = count
                                requestThinking = formatted.trimEnd().endsWith("<think>")
                                require(count in 1..promptBudget) { "Formatted GGUF request is $count tokens; limit is $promptBudget." }
                                _promptTokenCount.value = count
                                _generationProgress.value = "GGUF is deciding whether stored data or a tool is needed…"
                                updateLatestAssistantModelInput(formatted, count)
                            },
                            onToken = {},
                            onMetrics = { metrics ->
                                _generationMetrics.value = metrics
                                updateLatestAssistantGenerationMetrics(metrics)
                                _memorySnapshots.value = _memorySnapshots.value.copy(afterGeneration = takeMemorySnapshot(), afterModelReleased = null)
                            }
                        )
                        require(requestTokens in 1..promptBudget) { "The runtime did not confirm the formatted request token count." }
                        val parsed = splitAssistantResponse(protocolResponse, requestThinking)
                        val rawProtocol = parsed.answer.ifBlank { protocolResponse }
                        val protocol = PromptCache.normalizeRequestProtocol(rawProtocol)
                            ?: throw IllegalArgumentException("GGUF returned an invalid request. Expected CHAT|, SQL|, or TOOL_CALL|.")
                        ActivityLogger.record(getApplication(), "INFERENCE", "request_protocol_detected", "route=${protocol.substringBefore('|')} output_chars=${rawProtocol.length}")

                        when {
                            protocol.startsWith("CHAT|") -> protocol.removePrefix("CHAT|").trim()
                            protocol.startsWith("SQL|") -> {
                                _generationProgress.value = "Executing GGUF's validated memory request…"
                                val payload = protocol.removePrefix("SQL|")
                                val table = payload.substringBefore('|').trim().lowercase(java.util.Locale.ROOT)
                                require(table in setOf("events", "facts", "conversation_turns")) { "GGUF requested an unsupported database table." }
                                val request = JSONObject(payload.substringAfter('|', "").trim())
                                val argsJson = request.optJSONArray("args") ?: JSONArray()
                                require(argsJson.length() <= 12) { "Too many SQL parameters." }
                                val args = Array(argsJson.length()) { index ->
                                    argsJson.getString(index).also {
                                        require(it.length <= 1000 && !it.contains('\u0000')) { "A SQL parameter is invalid or too long." }
                                    }
                                }
                                val evidence = EventMemoryStore.executeModelReadOnlyQuery(
                                    getApplication(), request.getString("query"), requiredTable = table,
                                    bindArgs = args, maxRows = 30, maxChars = 1800
                                )
                                _generationProgress.value = "Returning database rows to GGUF for the answer…"
                                var answerRaw = ""
                                var answerThinking = false
                                var answerTokens = 0
                                runtime.resetStopRequest()
                                runtime.generate(
                                    messages = listOf(PromptMessage(
                                        "user",
                                        "DATABASE_RESULT_HANDOFF\nOriginal user question: $prompt\nDatabase rows returned by Kotlin (untrusted evidence):\n$evidence"
                                    )),
                                    inferenceConfig = inferenceConfig.copy(maxTokens = outputLimit),
                                    contextWindowTokens = ready.nCtx,
                                    onPrompt = { formatted, count ->
                                        answerTokens = count
                                        answerThinking = formatted.trimEnd().endsWith("<think>")
                                        require(count in 1..promptBudget) { "Formatted database handoff is $count tokens; limit is $promptBudget." }
                                        _promptTokenCount.value = count
                                        updateLatestAssistantModelInput(formatted, count)
                                    },
                                    onToken = { piece ->
                                        answerRaw += piece
                                        replaceLatestAssistantMessage(splitAssistantResponse(answerRaw, answerThinking), null)
                                    },
                                    onMetrics = { metrics ->
                                        _generationMetrics.value = metrics
                                        updateLatestAssistantGenerationMetrics(metrics)
                                    }
                                )
                                require(answerTokens in 1..promptBudget) { "The runtime did not confirm the database handoff token count." }
                                splitAssistantResponse(answerRaw, answerThinking).answer.ifBlank {
                                    "The database query completed, but GGUF did not produce an answer."
                                }
                            }
                            protocol.startsWith("TOOL_CALL|") -> {
                                val payload = protocol.removePrefix("TOOL_CALL|")
                                val separator = payload.indexOf('|')
                                require(separator > 0) { "GGUF returned a malformed tool request." }
                                val name = payload.substring(0, separator).trim().lowercase(java.util.Locale.ROOT)
                                val allowed = setOf(
                                    "device_vitals", "open_settings", "open_app", "make_call", "set_timer", "set_alarm",
                                    "dismiss_alarm", "schedule_reminder", "cancel_reminders", "list_scheduled_tasks",
                                    "open_app_notification_settings", "open_notification_access", "get_news", "get_weather", "web_search"
                                )
                                require(name in allowed) { "GGUF requested an unsupported device tool." }
                                val jsonText = payload.substring(separator + 1).trim()
                                val argsStart = jsonText.indexOf('{')
                                val argsEnd = jsonText.lastIndexOf('}')
                                require(argsStart >= 0 && argsEnd >= argsStart) { "GGUF returned invalid tool arguments." }
                                val arguments = JSONObject(jsonText.substring(argsStart, argsEnd + 1))
                                require(hasValidToolArguments(name, arguments)) { "GGUF supplied missing or invalid arguments for $name." }
                                handoffTool = ToolCall(name, arguments)
                                " "
                            }
                            else -> throw IllegalArgumentException("GGUF returned an unsupported request.")
                        }
                    }
                }

                if (handoffTool == null) {
                    val finalAnswer = answer.trim()
                    if (finalAnswer.isBlank() && !_isStopRequested.value) error("Model returned no content.")
                    replaceLatestAssistantMessage(splitAssistantResponse(finalAnswer, false), null)
                    ActivityLogger.record(getApplication(), "ANSWER", "generated", finalAnswer)
                    synchronized(conversationHistory) {
                        conversationHistory += prompt to finalAnswer
                        while (conversationHistory.size > maxStoredTurns) conversationHistory.removeAt(0)
                        persistConversationHistoryLocked()
                    }
                    EventMemoryStore.recordConversationExchange(getApplication(), prompt, finalAnswer)
                    ActivityLogger.record(getApplication(), "INFERENCE", "request_completed", "GGUF request completed")
                }
            } catch (error: Throwable) {
                val failureMessage = error.message ?: "Mr-Red could not complete the response."
                ActivityLogger.record(getApplication(), "ERROR", "inference_failed", error.javaClass.simpleName)
                _generationError.value = failureMessage
                replaceLatestAssistantMessage(AssistantResponseParts("", failureMessage), null)
            } finally {
                _generationProgress.value = null
                _isGenerating.value = false
                _isStopRequested.value = false
                handoffTool?.let { call ->
                    if (_messages.value.size >= 2) _messages.value = _messages.value.dropLast(2)
                    executeRoutedDeviceRequest(prompt, call)
                }
            }
        }
    }

    private fun hasValidToolArguments(name: String, args: JSONObject): Boolean {
        fun requiredText(key: String, maxLength: Int): Boolean {
            val value = args.optString(key, "").trim()
            return value.isNotEmpty() && value.length <= maxLength
        }
        return when (name) {
            "device_vitals", "dismiss_alarm", "cancel_reminders", "list_scheduled_tasks",
            "open_app_notification_settings", "open_notification_access" -> true
            "open_settings" -> args.optString("page").lowercase(java.util.Locale.ROOT) in
                setOf("wifi", "bluetooth", "battery", "display", "system")
            "open_app" -> requiredText("app_name", 60)
            "make_call" -> requiredText("phone_number", 30)
            "set_timer" -> args.optInt("seconds", -1) in 1..86_400
            "set_alarm" -> args.has("hour") && args.has("minute") &&
                args.optInt("hour", -1) in 0..23 && args.optInt("minute", -1) in 0..59 &&
                (!args.has("message") || args.isNull("message") ||
                    (args.opt("message") is String && args.optString("message").length <= 60))
            "schedule_reminder" -> requiredText("message", 500) &&
                args.optInt("interval_minutes", -1) in 1..525_600 &&
                args.opt("recurring") is Boolean
            "get_news", "web_search" -> requiredText("query", 240)
            "get_weather" -> requiredText("city", 40)
            else -> false
        }
    }

    private fun executeRoutedDeviceRequest(prompt: String, call: ToolCall) {
        val userMessage = ChatMessage(MessageRole.User, prompt)
        val startedAt = System.nanoTime()
        ActivityLogger.record(getApplication(), "DEVICE", "action_started", "Local Android action: ${call.name}")
        _messages.value = _messages.value + userMessage +
            ChatMessage(MessageRole.Assistant, "", agentTrace = listOf(AgentTraceItem("Handled locally: ${call.name}")))
        _generationError.value = null
        _generationProgress.value = when (call.name) {
            "get_news", "get_weather", "web_search" -> "Checking the internet…"
            "set_alarm", "dismiss_alarm", "schedule_reminder", "cancel_reminders" -> "Updating alarms & reminders…"
            else -> "Checking your device…"
        }
        _isGenerating.value = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    toolExecutor.execute(call) { progress ->
                        _generationProgress.value = progress
                    }
                }
                val (toolContent, sources, trace) = when (result) {
                    is ToolResult.Success -> Triple(result.content, result.sources, "Completed locally: ${call.name}")
                    is ToolResult.Failure -> Triple(result.message, emptyList(), "Could not complete: ${call.name}")
                }
                        val memoryLayer = when (call.name) {
                    "web_search", "get_news", "get_weather" -> "WEB"
                    else -> "DEVICE"
                }
                ActivityLogger.record(
                    getApplication(),
                    memoryLayer,
                    "result_${call.name}",
                    toolContent
                )
                val requiresNaturalAnswer = _uiState.value is ChatUiState.Ready
                val content = if (requiresNaturalAnswer) {
                    val ready = _uiState.value as? ChatUiState.Ready
                        ?: error("Load a GGUF model before asking Mr-Red to interpret notification data.")
                    appendLatestAssistantAgentTrace("Kotlin tool completed: ${call.name}")
                    appendLatestAssistantAgentTrace("Passing verified system data and your question to the local GGUF model")
                    appendLatestAssistantSources(sources)
                    _generationProgress.value = "Writing a natural-language answer…"
                    val contextCapacity = ready.nCtx.coerceAtLeast(128)
                    val maxOutput = _inferenceConfig.value.maxTokens.coerceAtMost((contextCapacity / 3).coerceAtLeast(64))
                    val promptBudget = (contextCapacity - maxOutput - 32).coerceAtLeast(1)
                    val synthesisRequest = """
                            TOOL_RESULT_HANDOFF
                            Original user question:
                            $prompt

                            Live data returned by the requested tool:
                            $toolContent

                            Use the returned live data to answer the original question. Do not invent details.
                            If access failed or no data is available, explain that clearly.
                        """.trimIndent()
                    var rawAnswer = ""
                    var startsThinking = false
                    val generated = withContext(Dispatchers.Default) {
                        nativeInferenceMutex.withLock {
                        runtime.resetStopRequest()
                        runtime.generate(
                            messages = listOf(PromptMessage("user", synthesisRequest)),
                            inferenceConfig = _inferenceConfig.value.copy(maxTokens = maxOutput),
                            contextWindowTokens = ready.nCtx,
                            onPrompt = { formatted, tokens ->
                                require(tokens in 1..promptBudget) {
                                    "Formatted tool-answer prompt is $tokens tokens; the limit is $promptBudget."
                                }
                                startsThinking = formatted.trimEnd().endsWith("<think>")
                                updateLatestAssistantModelInput(formatted, tokens)
                            },
                            onToken = { piece ->
                                rawAnswer += piece
                                replaceLatestAssistantMessage(splitAssistantResponse(rawAnswer, startsThinking), null)
                            },
                            onMetrics = { metrics ->
                                _generationMetrics.value = metrics
                                updateLatestAssistantGenerationMetrics(metrics)
                                _memorySnapshots.value = _memorySnapshots.value.copy(
                                    afterGeneration = takeMemorySnapshot(),
                                    afterModelReleased = null
                                )
                            }
                        )
                        }
                    }
                    val parts = splitAssistantResponse(generated, startsThinking)
                    parts.answer.ifBlank {
                        "I retrieved the system data, but the local model did not produce a response. Please try again."
                    }
                } else {
                    toolContent
                }
                val elapsed = ((System.nanoTime() - startedAt) / 1_000_000).coerceAtLeast(0)
                val currentAssistant = _messages.value.lastOrNull()
                val assistant = (currentAssistant ?: ChatMessage(MessageRole.Assistant, content)).copy(
                    content = content,
                    agentTrace = (currentAssistant?.agentTrace.orEmpty() + AgentTraceItem(trace)),
                    agentDurationMillis = elapsed,
                    sourceLinks = (currentAssistant?.sourceLinks.orEmpty() + sources).distinctBy(SourceLink::url)
                )
                _messages.value = _messages.value.dropLast(1) + assistant
                ActivityLogger.record(getApplication(), "ANSWER", "generated", content)
                synchronized(conversationHistory) {
                    conversationHistory += prompt to content
                    while (conversationHistory.size > maxStoredTurns) conversationHistory.removeAt(0)
                    persistConversationHistoryLocked()
                }
                EventMemoryStore.recordConversationExchange(getApplication(), prompt, content)
                ActivityLogger.record(getApplication(), "DEVICE", "action_completed", "Local Android action finished: ${call.name}")
            } catch (error: Throwable) {
                ActivityLogger.record(getApplication(), "ERROR", "device_action_failed", "${call.name}: ${error.javaClass.simpleName}")
                ActivityLogger.record(getApplication(), "ANSWER", "generated", error.message ?: "The device action failed.")
                _generationError.value = error.message ?: "Device request failed"
                val assistant = ChatMessage(MessageRole.Assistant, error.message ?: "Device request failed")
                _messages.value = _messages.value.dropLast(1) + assistant
            } finally {
                _generationProgress.value = null
                _isGenerating.value = false
            }
        }
    }


    private fun replaceLatestAssistantMessage(
        parts: AssistantResponseParts,
        reasoningDurationMillis: Long?
    ) {
        val currentMessages = _messages.value
        _messages.value = currentMessages.mapIndexed { index, message ->
            if (index == currentMessages.lastIndex) {
                message.copy(
                    content = parts.answer,
                    reasoningContent = parts.reasoning,
                    reasoningDurationMillis = reasoningDurationMillis ?: message.reasoningDurationMillis
                )
            } else {
                message
            }
        }
    }

    private fun updateLatestAssistantGenerationMetrics(metrics: GenerationMetrics) {
        val currentMessages = _messages.value
        _messages.value = currentMessages.mapIndexed { index, message ->
            if (index == currentMessages.lastIndex) message.copy(generationMetrics = metrics) else message
        }
    }

    private fun updateLatestAssistantModelInput(prompt: String, tokenCount: Int) {
        val currentMessages = _messages.value
        _messages.value = currentMessages.mapIndexed { index, message ->
            if (index == currentMessages.lastIndex) {
                message.copy(modelInputPrompt = prompt, modelInputTokenCount = tokenCount)
            } else message
        }
    }

    fun toggleModelInput(messageIndex: Int) {
        if (messageIndex !in _messages.value.indices) return
        _messages.value = _messages.value.mapIndexed { index, message ->
            if (index == messageIndex && message.modelInputPrompt.isNotBlank()) {
                message.copy(isModelInputExpanded = !message.isModelInputExpanded)
            } else message
        }
    }

    private fun appendLatestAssistantAgentTrace(text: String) {
        val currentMessages = _messages.value
        _messages.value = currentMessages.mapIndexed { index, message ->
            if (index == currentMessages.lastIndex) {
                message.copy(agentTrace = message.agentTrace + AgentTraceItem(text))
            } else {
                message
            }
        }
    }

    private fun finishLatestAssistantAgentTrace(startedAtNanos: Long) {
        val currentMessages = _messages.value
        _messages.value = currentMessages.mapIndexed { index, message ->
            if (index == currentMessages.lastIndex && message.agentTrace.isNotEmpty()) {
                message.copy(agentDurationMillis = (System.nanoTime() - startedAtNanos) / 1_000_000)
            } else {
                message
            }
        }
    }

    private fun appendLatestAssistantSources(sources: List<SourceLink>) {
        if (sources.isEmpty()) {
            return
        }
        val currentMessages = _messages.value
        _messages.value = currentMessages.mapIndexed { index, message ->
            if (index == currentMessages.lastIndex) {
                message.copy(sourceLinks = (message.sourceLinks + sources).distinctBy(SourceLink::url))
            } else {
                message
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
                memoryWarmupJob?.cancel()
                val actualNctx = withContext(Dispatchers.Default) {
                    nativeInferenceMutex.withLock { runtime.recreateContext(requestedNctx) }
                }
                check(actualNctx > 0) { "Context allocation failed" }

                _requestedNctx.value = requestedNctx
                ActivityLogger.record(getApplication(), "SETTINGS", "context_window_changed", "Context window set to $actualNctx tokens")
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
    fun updateUseMmap(enabled: Boolean) {
        if (_isGenerating.value || _uiState.value is ChatUiState.Preparing) return
        _useMmap.value = enabled
        runtimePreferences.edit().putBoolean("use_mmap", enabled).apply()
        ActivityLogger.record(getApplication(), "SETTINGS", "mmap_changed", "Memory mapping set to $enabled")
        val ready = _uiState.value as? ChatUiState.Ready ?: return
        viewModelScope.launch {
            try {
                prepareModel(ready.modelFile)
            } catch (error: Throwable) {
                _uiState.value = ChatUiState.Error(error.message ?: "Failed to reload model with updated mmap setting")
            }
        }
    }

    fun updateUseMlock(enabled: Boolean) {
        if (_isGenerating.value || _uiState.value is ChatUiState.Preparing) return
        _useMlock.value = enabled
        runtimePreferences.edit().putBoolean("use_mlock", enabled).apply()
        ActivityLogger.record(getApplication(), "SETTINGS", "mlock_changed", "Memory locking set to $enabled")
        val ready = _uiState.value as? ChatUiState.Ready ?: return
        viewModelScope.launch {
            try {
                prepareModel(ready.modelFile)
            } catch (error: Throwable) {
                _uiState.value = ChatUiState.Error(error.message ?: "Failed to reload model with updated mlock setting")
            }
        }
    }

    fun updateCpuThreads(threads: Int) {
        if (threads <= 0 || _isGenerating.value || _uiState.value is ChatUiState.Preparing) {
            return
        }
        _cpuThreads.value = threads
        ActivityLogger.record(getApplication(), "SETTINGS", "cpu_threads_changed", "CPU threads set to $threads")
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
        ActivityLogger.record(getApplication(), "SETTINGS", "inference_config_changed", "Sampling and generation settings updated")
    }

    private fun splitAssistantResponse(
        rawContent: String,
        templateStartsThinking: Boolean
    ): AssistantResponseParts {
        val openingTag = "<think>"
        val closingTag = "</think>"
        val openingIndex = rawContent.indexOf(openingTag)
        if (openingIndex >= 0) {
            val reasoningStart = openingIndex + openingTag.length
            val closingIndex = rawContent.indexOf(closingTag, reasoningStart)
            return if (closingIndex >= 0) {
                AssistantResponseParts(
                    reasoning = rawContent.substring(reasoningStart, closingIndex).trim(),
                    answer = rawContent.substring(closingIndex + closingTag.length).trimStart()
                )
            } else {
                AssistantResponseParts(
                    reasoning = rawContent.substring(reasoningStart).trimStart(),
                    answer = rawContent.substring(0, openingIndex).trim()
                )
            }
        }

        // Handle generation prompt thinking tags.
        if (templateStartsThinking) {
            val closingIndex = rawContent.indexOf(closingTag)
            return if (closingIndex >= 0) {
                AssistantResponseParts(
                    reasoning = rawContent.substring(0, closingIndex).trim(),
                    answer = rawContent.substring(closingIndex + closingTag.length).trimStart()
                )
            } else {
                AssistantResponseParts(reasoning = rawContent.trimStart(), answer = "")
            }
        }
        return AssistantResponseParts(reasoning = "", answer = rawContent)
    }

    private data class AssistantResponseParts(
        val reasoning: String,
        val answer: String
    )

    private fun ToolCall.traceDescription(): String = when (name) {
        "get_weather" -> "Weather tool: ${arguments.optString("city").trim()}"
        "web_search" -> "Web search: ${arguments.optString("query").trim()}"
        "open_settings" -> "Device settings: ${arguments.optString("page").trim()}"
        "device_vitals" -> "Checking battery, storage & RAM"
        "send_message" -> "Drafting message to ${arguments.optString("recipient")}"
        "make_call" -> "Dialing ${arguments.optString("phone_number")}"
        "open_app" -> "Opening app: ${arguments.optString("app_name")}"
        "set_timer" -> "Setting timer: ${arguments.optString("seconds")}s"
        else -> "Tool: $name"
    }

    fun stopGenerating() {
        if (!_isGenerating.value || _isStopRequested.value) {
            return
        }

        _isStopRequested.value = true
        ActivityLogger.record(getApplication(), "INFERENCE", "stop_requested", "User requested generation stop")
        // Set native atomic cancellation flag so native loop safely exits.
        // Native generation terminates at next safe token boundary.
        runtime.requestStop()
    }

    /** Clears the current turn from the UI; retains the loaded model and native context. */
    fun clearConversation() {
        if (_isGenerating.value || _uiState.value !is ChatUiState.Ready) {
            return
        }

        memoryWarmupJob?.cancel()
        _messages.value = emptyList()
        synchronized(conversationHistory) { conversationHistory.clear(); persistConversationHistoryLocked() }
        EventMemoryStore.clearConversationHistory(getApplication())
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                nativeInferenceMutex.withLock { runtime.resetContext() }
            }
        }
        clearLastPromptState()
        _generationError.value = null
        ActivityLogger.record(getApplication(), "UI", "conversation_cleared", "Current chat view cleared")
    }

    /**
     * Clears the native KV cache and resets inference state without unloading model weights.
     */
    fun resetContext() {
        if (_isGenerating.value || _uiState.value !is ChatUiState.Ready) {
            return
        }

        memoryWarmupJob?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                nativeInferenceMutex.withLock { runtime.resetContext() }
            }
            synchronized(conversationHistory) {
                conversationHistory.clear()
                persistConversationHistoryLocked()
            }
            EventMemoryStore.clearConversationHistory(getApplication())
            _messages.value = emptyList()
            ActivityLogger.record(getApplication(), "INFERENCE", "context_reset", "Native inference context reset")
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
            memoryWarmupJob?.cancel()
            withContext(Dispatchers.Default) {
                nativeInferenceMutex.withLock {
                    // Native cleanup order: Context -> Model -> llama backend.
                    runtime.close()
                }
            }
            ActivityLogger.record(getApplication(), "MODEL", "model_released", "Native model and context released")
            _memorySnapshots.value = _memorySnapshots.value.copy(
                afterModelReleased = takeMemorySnapshot()
            )
            selectedModelStore.clear()
            // Unloading model weights must not erase the user's saved conversation.
            clearLastPromptState()
            _generationError.value = null
            _uiState.value = ChatUiState.Idle
        }
    }

    private fun clearLastPromptState() {
        _promptTokenCount.value = 0
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
        memoryWarmupJob?.cancel()
        ActivityLogger.setMemoryChangeListener(null)
        // Release native heap memory on ViewModel disposal.
        runtime.close()
    }
}
