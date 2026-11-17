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

    // Completed turns are persisted privately and restored when the app process restarts.
    // The native KV cache is separate and is rebuilt from the transcript after restart.
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private val historyPreferences = application.getSharedPreferences("mr_red_chat_history", android.content.Context.MODE_PRIVATE)
    private val maxStoredTurns = 40

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _promptTokenCount = MutableStateFlow(0)
    val promptTokenCount: StateFlow<Int> = _promptTokenCount.asStateFlow()

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
        ActivityLogger.record(application, "APP", "view_model_created", "Chat runtime initialized")
        // Retire legacy chat, personal-memory, persona and summary stores from older installs.
        application.deleteDatabase("mr_red_conversation.db")
        application.deleteDatabase("mr_red_memory.db")
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
        ActivityLogger.record(getApplication(), "MODEL", "runtime_prepare_started", "Preparing native model and context")
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
        // The conversation transcript is restored from app-private storage; native KV state is not.
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
        ActivityLogger.record(getApplication(), "MODEL", "runtime_ready", "Native model and context are ready")
    }

    private fun restoreConversationHistory() {
        runCatching {
            val saved = JSONArray(historyPreferences.getString("turns", "[]"))
            val restored = mutableListOf<Pair<String, String>>()
            for (index in 0 until saved.length()) {
                val item = saved.optJSONObject(index) ?: continue
                val question = item.optString("question").trim()
                val answer = item.optString("answer").trim()
                if (question.isNotBlank() && answer.isNotBlank()) restored += question to answer
            }
            synchronized(conversationHistory) {
                conversationHistory.clear()
                conversationHistory.addAll(restored.takeLast(maxStoredTurns))
                _messages.value = conversationHistory.flatMap { (question, answer) ->
                    listOf(ChatMessage(MessageRole.User, question), ChatMessage(MessageRole.Assistant, answer))
                }
            }
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

    fun send(prompt: String) {
        if (prompt.isBlank() || _isGenerating.value) return

        // Retain both sides privately so the summary can describe the exchange without quoting it.
        ActivityLogger.record(getApplication(), "QUESTION", "asked", prompt)

        // Keep phone actions local; they are not injected into GGUF inference.
        DeviceIntentRouter.route(prompt)?.let { call ->
            executeRoutedDeviceRequest(prompt, call)
            return
        }

        if (_uiState.value !is ChatUiState.Ready) {
            _generationError.value = "Load a GGUF model before sending a question."
            return
        }

        ActivityLogger.record(getApplication(), "INFERENCE", "request_started", "Question submitted; message content omitted")
        val inferenceConfig = _inferenceConfig.value
        val userMessage = ChatMessage(MessageRole.User, prompt)
        _messages.value = _messages.value + userMessage + ChatMessage(MessageRole.Assistant, "")
        _generationError.value = null
        _promptTokenCount.value = 0
        _generationProgress.value = "Preparing current question…"
        _generationMetrics.value = null
        runtime.resetStopRequest()
        _isStopRequested.value = false
        _isGenerating.value = true

        viewModelScope.launch {
            var rawResponse = ""
            var templateStartsThinking = false
            try {
                val answer = withContext(Dispatchers.Default) {
                    // Keep the native KV cache between turns. Send the full in-session
                    // transcript so llama.cpp can reuse its longest common token prefix.
                    val readyState = _uiState.value as? ChatUiState.Ready
                    val promptBudget = ((readyState?.nCtx ?: 512) - inferenceConfig.maxTokens - 32)
                        .coerceAtLeast(1)
                    val historySnapshot = synchronized(conversationHistory) { conversationHistory.toList() }
                    val promptMessages = buildPromptMessages(
                        currentQuestion = prompt,
                        history = historySnapshot,
                        maxPromptTokens = promptBudget
                    )
                    runtime.generate(
                        messages = promptMessages,
                        inferenceConfig = inferenceConfig,
                        contextWindowTokens = readyState?.nCtx ?: 0,
                        onPrompt = { formattedPrompt, tokenCount ->
                            _promptTokenCount.value = tokenCount
                            _generationProgress.value = "Prefilling $tokenCount tokens…"
                            templateStartsThinking = formattedPrompt.trimEnd().endsWith("<think>")
                        },
                        onToken = { piece ->
                            rawResponse += piece
                            _generationProgress.value = "Generating response…"
                            replaceLatestAssistantMessage(
                                splitAssistantResponse(rawResponse, templateStartsThinking),
                                null
                            )
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
                if (answer.isBlank() && !_isStopRequested.value) {
                    error("Model returned no content")
                }
                if (answer.isNotBlank()) {
                    replaceLatestAssistantMessage(
                        splitAssistantResponse(answer, templateStartsThinking),
                        null
                    )
                }
                val finalAnswer = splitAssistantResponse(answer, templateStartsThinking).answer
                ActivityLogger.record(getApplication(), "ANSWER", "generated", finalAnswer)
                synchronized(conversationHistory) {
                    conversationHistory += prompt to finalAnswer
                    while (conversationHistory.size > maxStoredTurns) conversationHistory.removeAt(0)
                    persistConversationHistoryLocked()
                }
                ActivityLogger.record(getApplication(), "INFERENCE", "request_completed", "Model generation completed")
            } catch (error: Throwable) {
                val failureMessage = error.message ?: "Mr-Red could not complete the response."
                ActivityLogger.record(getApplication(), "ERROR", "inference_failed", error.javaClass.simpleName)
                ActivityLogger.record(getApplication(), "ANSWER", "generated", failureMessage)
                _generationError.value = failureMessage
                replaceLatestAssistantMessage(AssistantResponseParts("", failureMessage), null)
            } finally {
                _generationProgress.value = null
                _isGenerating.value = false
                _isStopRequested.value = false
            }
        }
    }

    /**
     * Builds real role-separated messages for the GGUF chat template. Oldest turns are
     * removed first to fit the context budget; the current question is always retained.
     */
    private fun buildPromptMessages(
        currentQuestion: String,
        history: List<Pair<String, String>>,
        maxPromptTokens: Int
    ): List<PromptMessage> {
        val systemInstructions = """
            You are Mr-Red. Be natural, clear, considerate and conversational. Be caring
            without overdoing it; offer practical suggestions when useful. Be honest about
            uncertainty and never invent personal experiences or claim to be human.
            Treat the supplied conversation turns as real prior context. Use them to resolve
            follow-ups and references; do not claim you have no memory when the detail is present.
            Answer the latest user message directly.
        """.trimIndent()

        var retainedHistory = history.takeLast(8)
        while (true) {
            val messages = buildList {
                add(PromptMessage("system", systemInstructions))
                retainedHistory.forEach { (question, answer) ->
                    add(PromptMessage("user", question))
                    add(PromptMessage("assistant", answer))
                }
                add(PromptMessage("user", currentQuestion))
            }
            // Include role labels in the estimate and leave room for the GGUF chat template.
            val estimate = messages.joinToString("\n") { "${it.role}\n${it.content}" }
            if (runtime.tokenize(estimate).size + 32 <= maxPromptTokens) return messages

            if (retainedHistory.isNotEmpty()) {
                retainedHistory = retainedHistory.drop(1)
            } else {
                // If even the system instruction plus current question cannot fit, prioritize
                // the user's complete question rather than silently truncating it.
                return if (runtime.tokenize(currentQuestion).size + 8 <= maxPromptTokens) {
                    listOf(PromptMessage("system", systemInstructions), PromptMessage("user", currentQuestion))
                } else {
                    listOf(PromptMessage("user", currentQuestion))
                }
            }
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
            "read_notifications" -> "Reading recent notifications…"
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
                val (content, sources, trace) = when (result) {
                    is ToolResult.Success -> Triple(result.content, result.sources, "Completed locally: ${call.name}")
                    is ToolResult.Failure -> Triple(result.message, emptyList(), "Could not complete: ${call.name}")
                }
                val elapsed = ((System.nanoTime() - startedAt) / 1_000_000).coerceAtLeast(0)
                val assistant = ChatMessage(
                    role = MessageRole.Assistant,
                    content = content,
                    agentTrace = listOf(AgentTraceItem("Handled locally: ${call.name}"), AgentTraceItem(trace)),
                    agentDurationMillis = elapsed,
                    sourceLinks = sources
                )
                _messages.value = _messages.value.dropLast(1) + assistant
                ActivityLogger.record(getApplication(), "ANSWER", "generated", content)
                synchronized(conversationHistory) {
                    conversationHistory += prompt to content
                    while (conversationHistory.size > maxStoredTurns) conversationHistory.removeAt(0)
                    persistConversationHistoryLocked()
                }
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
                val actualNctx = withContext(Dispatchers.Default) {
                    runtime.recreateContext(requestedNctx)
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

        _messages.value = emptyList()
        synchronized(conversationHistory) { conversationHistory.clear(); persistConversationHistoryLocked() }
        runtime.resetContext()
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

        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                runtime.resetContext()
            }
            synchronized(conversationHistory) {
                conversationHistory.clear()
                persistConversationHistoryLocked()
            }
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
            withContext(Dispatchers.Default) {
                // Native cleanup order: Context -> Model -> llama backend.
                runtime.close()
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
        // Release native heap memory on ViewModel disposal.
        runtime.close()
    }
}
