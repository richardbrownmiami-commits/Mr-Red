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
    private val fixedSystemInstructions = """
        You are Mr-Red: feminine, composed, perceptive, witty and quietly caring. Speak naturally.
        Answer the latest request directly; be concise unless detail is needed.
        Use supplied memory as historical context, never as proof of live device state.
        Use Android tools for current device data. Report actions only after confirmed results.
        Never invent facts, memories, access, search results or completed actions.
        State capability limits plainly. Identify yourself only as Mr-Red.
        Show warmth and subtle humour without claiming human consciousness or creating dependency.
    """.trimIndent()
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
        ActivityLogger.setMemoryChangeListener { scheduleMemoryWarmup() }
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
        memoryWarmupJob?.cancel()
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
        ActivityLogger.record(
            getApplication(), "MODEL", "runtime_ready",
            "name=$modelName description=$modelDescription n_ctx=${preparationMetrics.actualNctx} cpu_threads=${_cpuThreads.value}"
        )
        scheduleMemoryWarmup()
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

        // Answer stable capability/identity questions locally; a small on-device model
        // should not be allowed to hallucinate capabilities or drift on identity.
        localConversationReply(prompt)?.let { reply ->
            completeLocalReply(prompt, reply)
            return
        }

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
        _isStopRequested.value = false
        _isGenerating.value = true

        viewModelScope.launch {
            var rawResponse = ""
            var templateStartsThinking = false
            try {
                // Let a system-prefix warmup finish rather than aborting it and
                // paying the full prompt-prefill cost again for this request.
                memoryWarmupJob?.join()
                val answer = withContext(Dispatchers.Default) {
                    nativeInferenceMutex.withLock {
                    runtime.resetStopRequest()
                    // Keep the native KV cache between turns. The prompt uses the invariant
                    // system instructions, one compact logger summary, and the current question.
                    val readyState = _uiState.value as? ChatUiState.Ready
                    val contextCapacity = (readyState?.nCtx ?: 512).coerceAtLeast(128)
                    // Small context windows must reserve room for memory as well as output.
                    // The previous full output reservation left only ~224 prompt tokens at
                    // nCtx=512, causing the memory builder to shrink its summary to nothing.
                    val effectiveMaxTokens = inferenceConfig.maxTokens.coerceAtMost(
                        (contextCapacity / 3).coerceAtLeast(64)
                    )
                    val generationConfig = inferenceConfig.copy(maxTokens = effectiveMaxTokens)
                    val promptBudget = (contextCapacity - effectiveMaxTokens - 32).coerceAtLeast(1)
                    // One compact logger summary carries conversation Q&A and app/device events.
                    // Do not append a second verbatim transcript: it duplicates the same turns.
                    val rawMemorySummary = ActivityLogger.promptMemory(getApplication())
                    // No fixed memory-token ceiling: buildPromptMessages fits memory into
                    // the actual remaining context after system instructions and the question.
                    val memorySummary = rawMemorySummary
                    val promptMessages = buildPromptMessages(
                        currentQuestion = prompt,
                        memorySummary = memorySummary,
                        maxPromptTokens = promptBudget
                    )
                    ActivityLogger.record(
                        getApplication(), "INFERENCE", "prompt_composition",
                        "messages=${promptMessages.size} memory_chars=${memorySummary.length} " +
                            "memory_tokens=${runtime.tokenize(memorySummary).size} " +
                            "context=${contextCapacity} prompt_budget=${promptBudget} output_limit=${effectiveMaxTokens}"
                    )
                    runtime.generate(
                        messages = promptMessages,
                        inferenceConfig = generationConfig,
                        contextWindowTokens = readyState?.nCtx ?: 0,
                        onPrompt = { formattedPrompt, tokenCount ->
                            _promptTokenCount.value = tokenCount
                            _generationProgress.value = "Prefilling $tokenCount tokens…"
                            templateStartsThinking = formattedPrompt.trimEnd().endsWith("<think>")
                            updateLatestAssistantModelInput(formattedPrompt, tokenCount)
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
                            updateLatestAssistantGenerationMetrics(metrics)
                            _memorySnapshots.value = _memorySnapshots.value.copy(
                                afterGeneration = takeMemorySnapshot(),
                                afterModelReleased = null
                            )
                        }
                    )
                    }
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
                scheduleMemoryWarmup()
            }
        }
    }

    /**
     * Builds role-separated GGUF messages from the invariant system instructions,
     * one compact logger summary, and the current question. No separate transcript is added.
     */
    private fun trimMemoryToTokenBudget(memory: String, maxTokens: Int): String {
        if (memory.isBlank() || maxTokens <= 0) return ""
        val words = memory.trim().split(Regex("\\s+"))
        var low = 0
        var high = words.size
        var best = ""
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = words.take(mid).joinToString(" ")
            val tokenCount = if (candidate.isBlank()) 0 else runtime.tokenize(candidate).size
            if (tokenCount <= maxTokens) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    private fun buildMemoryPrefixMessages(memorySummary: String): List<PromptMessage> = buildList {
        add(PromptMessage("system", fixedSystemInstructions))
        if (memorySummary.isNotBlank()) {
            add(PromptMessage(
                "user",
                "Mr-Red activity memory (compact summary of prior Q&A and app/device events):\n$memorySummary"
            ))
        }
    }

    private fun buildPromptMessages(
        currentQuestion: String,
        memorySummary: String,
        maxPromptTokens: Int
    ): List<PromptMessage> {
        // This prefix is byte-for-byte the same prefix prepared by the idle memory feeder.
        val retainedMemory = memorySummary.trim()
        val messages = buildMemoryPrefixMessages(retainedMemory) + PromptMessage("user", currentQuestion)
        val estimate = messages.joinToString("\n") { "${it.role}\n${it.content}" }
        if (runtime.tokenize(estimate).size + 32 <= maxPromptTokens) return messages

        var memory = retainedMemory
        while (memory.isNotBlank()) {
            memory = memory.take(memory.length * 3 / 4).trimEnd()
            val reduced = buildMemoryPrefixMessages(memory) + PromptMessage("user", currentQuestion)
            val reducedEstimate = reduced.joinToString("\n") { "${it.role}\n${it.content}" }
            if (runtime.tokenize(reducedEstimate).size + 32 <= maxPromptTokens) return reduced
        }
        return if (runtime.tokenize(currentQuestion).size + 8 <= maxPromptTokens) {
            listOf(PromptMessage("system", fixedSystemInstructions), PromptMessage("user", currentQuestion))
        } else {
            listOf(PromptMessage("user", currentQuestion))
        }
    }

    /** Debounced idle-time prefill. This fills KV cache without sampling response tokens. */
    private fun scheduleMemoryWarmup() {
        if (_isGenerating.value || _uiState.value !is ChatUiState.Ready) return
        memoryWarmupJob?.cancel()
        memoryWarmupJob = viewModelScope.launch {
            delay(900)
            if (_uiState.value !is ChatUiState.Ready) return@launch
            val warmedTokens = withContext(Dispatchers.Default) {
                nativeInferenceMutex.withLock {
                    if (_uiState.value !is ChatUiState.Ready) {
                        0
                    } else {
                        // A foreground send joins this job before acquiring the native mutex,
                        // so the invariant system prefix is never abandoned mid-prefill.
                        runtime.resetStopRequest()
                        runtime.warmPrompt(listOf(PromptMessage("system", fixedSystemInstructions)))
                    }
                }
            }
            if (warmedTokens > 0) {
                android.util.Log.d("MrRedMemory", "Warmed $warmedTokens memory-prefix tokens")
            }
        }
    }

    private fun localConversationReply(prompt: String): String? {
        val text = prompt.trim().lowercase(java.util.Locale.US)
        if (text.matches(Regex("^[.!?,;:]+$"))) {
            return "I'm here. What would you like to do?"
        }
        if (Regex("\\b(who are you|what are you|introduce yourself|tell me about yourself)\\b").containsMatchIn(text)) {
            return "I'm Mr-Red, your on-device personal assistant. I can help with conversation and the Android features connected in this app, including active notifications, device status, alarms and reminders. I'm composed, observant and occasionally dry-witted; I'll be clear about what I can and cannot access."
        }
        if (Regex("\\b(email|emails|mail|inbox)\\b").containsMatchIn(text) &&
            Regex("\\b(any|check|show|read|new|unread|latest|have|got|see|look|my)\\b").containsMatchIn(text)) {
            return "I can't read your email inbox in this build because no email account connection or inbox-reading tool is configured. I can open Gmail on your phone, but I can't see your messages or verify whether you have new mail."
        }
        return null
    }

    private fun completeLocalReply(prompt: String, reply: String) {
        _messages.value = _messages.value +
            ChatMessage(MessageRole.User, prompt) +
            ChatMessage(MessageRole.Assistant, reply)
        _generationError.value = null
        ActivityLogger.record(getApplication(), "ANSWER", "generated", reply)
        synchronized(conversationHistory) {
            conversationHistory += prompt to reply
            while (conversationHistory.size > maxStoredTurns) conversationHistory.removeAt(0)
            persistConversationHistoryLocked()
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
                val (toolContent, sources, trace) = when (result) {
                    is ToolResult.Success -> Triple(result.content, result.sources, "Completed locally: ${call.name}")
                    is ToolResult.Failure -> Triple(result.message, emptyList(), "Could not complete: ${call.name}")
                }
                val memoryLayer = when (call.name) {
                    "read_notifications" -> "NOTIFICATION"
                    "web_search", "get_news", "get_weather" -> "WEB"
                    else -> "DEVICE"
                }
                ActivityLogger.record(
                    getApplication(),
                    memoryLayer,
                    "result_${call.name}",
                    toolContent
                )
                // Kotlin reads the live Android snapshot; the GGUF model analyses the
                // complete verified notification payload rather than returning a raw dump.
                val requiresNaturalAnswer = call.name in setOf(
                    "web_search", "get_news", "get_weather", "read_notifications"
                ) && _uiState.value is ChatUiState.Ready
                val content = if (requiresNaturalAnswer) {
                    val ready = _uiState.value as ChatUiState.Ready
                    appendLatestAssistantAgentTrace("Kotlin tool completed: ${call.name}")
                    appendLatestAssistantAgentTrace("Passing verified tool output to the local GGUF model for a natural-language answer")
                    appendLatestAssistantSources(sources)
                    _generationProgress.value = "Writing a natural-language answer…"
                    // Prior logger history is less useful than the complete live snapshot.
                    val memory = if (call.name == "read_notifications") "" else ActivityLogger.promptMemory(getApplication())
                    val contextCapacity = ready.nCtx.coerceAtLeast(128)
                    val maxOutput = _inferenceConfig.value.maxTokens.coerceAtMost((contextCapacity / 3).coerceAtLeast(64))
                    val promptBudget = (contextCapacity - maxOutput - 32).coerceAtLeast(1)
                    val synthesisRequest = """
                        Original user request: $prompt

                        Verified result returned by the Kotlin Android tool '${call.name}':
                        $toolContent

                        Answer the original request naturally and directly using the verified tool result.
                        For notification requests, analyse the FULL notification snapshot supplied below.
                        Identify actionable or time-sensitive items first: missed calls, personal messages,
                        account/security/bank alerts, reminders, deliveries and transaction updates.
                        Separate those from promotions, games and routine app chatter. Explain what needs
                        attention using the actual notification details. Do not label marketing as important
                        merely because it uses urgent wording. If nothing appears urgent, say so.
                        Treat the tool result as the only source of current facts. Never invent details.
                        If access failed or no data is available, explain that clearly.
                        Do not mention internal tool names unless useful to explain a limitation.
                    """.trimIndent()
                    var rawAnswer = ""
                    var startsThinking = false
                    val generated = withContext(Dispatchers.Default) {
                        nativeInferenceMutex.withLock {
                        runtime.resetStopRequest()
                        runtime.generate(
                            messages = buildPromptMessages(synthesisRequest, memory, promptBudget),
                            inferenceConfig = _inferenceConfig.value.copy(maxTokens = maxOutput),
                            contextWindowTokens = ready.nCtx,
                            onPrompt = { formatted, tokens ->
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
                    parts.answer.ifBlank { toolContent }
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
                scheduleMemoryWarmup()
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
