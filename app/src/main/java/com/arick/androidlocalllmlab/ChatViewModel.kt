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

    // Completed turns are persisted privately and restored when the app process restarts.
    // The native KV cache is separate and is rebuilt from the transcript after restart.
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
        ActivityLogger.setMemoryChangeListener { scheduleMemoryWarmup() }
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
        scheduleStablePromptWarmup()
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


    /**
     * Runs a question through the real Kotlin retrieval and prompt-construction path,
     * then the loaded GGUF model. The sampler is read-only: it does not add test turns
     * to chat history, capture facts, execute Android tools, or mutate the memory DB.
     */
    fun sendSamplerQuestion(question: String) {
        val prompt = question.trim()
        if (prompt.isBlank() || _isGenerating.value || _samplerGenerating.value) return
        if (_uiState.value !is ChatUiState.Ready) {
            _samplerStatus.value = "No model is ready. Load a GGUF model first."
            return
        }

        _samplerMessages.value = _samplerMessages.value +
            ChatMessage(MessageRole.User, prompt) +
            ChatMessage(MessageRole.Assistant, "")
        _samplerRetrievedMemory.value = ""
        _samplerFormattedPrompt.value = ""
        _samplerPromptTokenCount.value = 0
        _samplerStatus.value = "Sending the question directly to GGUF…"
        _samplerGenerating.value = true
        val inferenceConfig = _inferenceConfig.value

        viewModelScope.launch {
            var rawResponse = ""
            var templateStartsThinking = false
            try {
                memoryWarmupJob?.join()
                val answer = withContext(Dispatchers.Default) {
                    nativeInferenceMutex.withLock {
                        runtime.resetStopRequest()
                        val ready = _uiState.value as? ChatUiState.Ready
                            ?: error("The model is no longer ready.")
                        val contextCapacity = ready.nCtx.coerceAtLeast(128)
                        val maxOutput = inferenceConfig.maxTokens.coerceAtMost((contextCapacity / 3).coerceAtLeast(64))
                        val promptBudget = minOf(
                            PromptCache.PROMPT_CAP,
                            (contextCapacity - maxOutput - 32).coerceAtLeast(1)
                        )
                        var firstThinking = false
                        val firstResponse = runtime.generate(
                            messages = PromptCache.stage("CHAT", "QUESTION=$prompt"),
                            inferenceConfig = inferenceConfig.copy(maxTokens = maxOutput),
                            contextWindowTokens = contextCapacity,
                            onPrompt = { formatted, tokens ->
                                require(tokens in 1..promptBudget) {
                                    "Formatted sampler prompt is $tokens tokens; limit is $promptBudget."
                                }
                                _samplerFormattedPrompt.value = formatted
                                _samplerPromptTokenCount.value = tokens
                                firstThinking = formatted.trimEnd().endsWith("<think>")
                            },
                            onToken = { piece ->
                                rawResponse += piece
                                val visible = splitAssistantResponse(rawResponse, firstThinking)
                                val candidate = visible.answer.trimStart()
                                val possibleToolMarker = candidate.isEmpty() ||
                                    "tool+".startsWith(candidate.take(5), ignoreCase = true) ||
                                    candidate.startsWith("tool+", ignoreCase = true) ||
                                    "TOOL_CALL|".startsWith(candidate, ignoreCase = true) ||
                                    candidate.startsWith("TOOL_CALL|", ignoreCase = true) ||
                                    "TOOL_CALL|".startsWith(candidate, ignoreCase = true) ||
                                    candidate.startsWith("TOOL_CALL|", ignoreCase = true)
                                if (!possibleToolMarker) {
                                    _samplerMessages.value = _samplerMessages.value.mapIndexed { index, message ->
                                        if (index == _samplerMessages.value.lastIndex) {
                                            message.copy(content = visible.answer, reasoningContent = visible.reasoning)
                                        } else message
                                    }
                                }
                            },
                            onMetrics = { metrics ->
                                _samplerMessages.value = _samplerMessages.value.mapIndexed { index, message ->
                                    if (index == _samplerMessages.value.lastIndex) message.copy(generationMetrics = metrics)
                                    else message
                                }
                            }
                        )
                        val firstParts = splitAssistantResponse(firstResponse, firstThinking)
                        if (!firstParts.answer.trim().startsWith("tool+", ignoreCase = true)) {
                            _samplerRetrievedMemory.value = "No database request was made."
                            templateStartsThinking = firstThinking
                            firstResponse
                        } else {
                            val evidence = executeToolPlusRequest(firstParts.answer.trim(), prompt, allowMemoryWrites = false)
                            _samplerRetrievedMemory.value = evidence
                            _samplerStatus.value = "GGUF requested stored data; executing validated read-only SQL…"
                            rawResponse = ""
                            runtime.resetStopRequest()
                            runtime.generate(
                                messages = buildDatabaseAnswerMessages(prompt, evidence, promptBudget),
                                inferenceConfig = inferenceConfig.copy(maxTokens = maxOutput),
                                contextWindowTokens = contextCapacity,
                                onPrompt = { formatted, tokens ->
                                    require(tokens in 1..promptBudget) {
                                        "Formatted sampler result prompt is $tokens tokens; limit is $promptBudget."
                                    }
                                    _samplerFormattedPrompt.value = formatted
                                    _samplerPromptTokenCount.value = tokens
                                    templateStartsThinking = formatted.trimEnd().endsWith("<think>")
                                },
                                onToken = { piece ->
                                    rawResponse += piece
                                    val parts = splitAssistantResponse(rawResponse, templateStartsThinking)
                                    _samplerMessages.value = _samplerMessages.value.mapIndexed { index, message ->
                                        if (index == _samplerMessages.value.lastIndex) {
                                            message.copy(content = parts.answer, reasoningContent = parts.reasoning)
                                        } else message
                                    }
                                },
                                onMetrics = { metrics ->
                                    _samplerMessages.value = _samplerMessages.value.mapIndexed { index, message ->
                                        if (index == _samplerMessages.value.lastIndex) message.copy(generationMetrics = metrics)
                                        else message
                                    }
                                }
                            )
                        }
                    }
                }
                if (answer.isBlank()) error("The model returned no content.")
                val parts = splitAssistantResponse(answer, templateStartsThinking)
                _samplerMessages.value = _samplerMessages.value.mapIndexed { index, message ->
                    if (index == _samplerMessages.value.lastIndex) message.copy(
                        content = parts.answer,
                        reasoningContent = parts.reasoning,
                        modelInputPrompt = _samplerFormattedPrompt.value,
                        modelInputTokenCount = _samplerPromptTokenCount.value
                    ) else message
                }
                _samplerStatus.value = if (_samplerRetrievedMemory.value == "No database request was made.") {
                    "Test complete. GGUF answered without a database lookup."
                } else {
                    "Test complete. GGUF requested data using TOOL+; validated results are shown below."
                }
            } catch (error: Throwable) {
                val failure = error.message ?: "Sampler inference failed."
                _samplerStatus.value = "Test failed: $failure"
                _samplerMessages.value = _samplerMessages.value.mapIndexed { index, message ->
                    if (index == _samplerMessages.value.lastIndex) message.copy(content = failure)
                    else message
                }
            } finally {
                _samplerGenerating.value = false
                scheduleMemoryWarmup()
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

    private fun executeToolPlusRequest(
        modelAnswer: String,
        originalQuestion: String,
        allowMemoryWrites: Boolean = true
    ): String {
        val requestBody = modelAnswer.trim().drop(5).trim()
        return runCatching {
            val jsonStart = requestBody.indexOf('{')
            val jsonEnd = requestBody.lastIndexOf('}')
            require(jsonStart >= 0 && jsonEnd >= jsonStart) {
                "tool+ must be followed by a JSON object."
            }
            val request = JSONObject(requestBody.substring(jsonStart, jsonEnd + 1))
            when (request.optString("operation", "query").lowercase(java.util.Locale.ROOT)) {
                "save_fact" -> {
                    require(allowMemoryWrites) { "The sampler is read-only and cannot save facts." }
                    EventMemoryStore.saveModelRequestedFact(
                        context = getApplication(),
                        fact = request.getString("fact"),
                        sourceText = originalQuestion
                    )
                }
                "query" -> {
                    val queries = request.optJSONArray("queries")
                    val requests = if (queries != null) {
                        require(queries.length() in 1..3) { "Provide one to three independent table queries." }
                        (0 until queries.length()).map { queries.getJSONObject(it) }
                    } else {
                        listOf(request)
                    }
                    val allowedTables = setOf("events", "facts", "conversation_turns")
                    val seenTables = mutableSetOf<String>()
                    val evidence = StringBuilder()
                    requests.forEach { query ->
                        val table = query.getString("table").lowercase(java.util.Locale.ROOT)
                        require(table in allowedTables) { "The requested database table is not allowed." }
                        require(seenTables.add(table)) { "Request each database table only once." }
                        val sql = query.getString("sql")
                        val result = runCatching {
                            EventMemoryStore.executeModelReadOnlyQuery(
                                context = getApplication(),
                                sql = sql,
                                requiredTable = table,
                                maxRows = 100,
                                maxChars = 18_000
                            )
                        }.getOrElse { error ->
                            "QUERY REJECTED: ${error.message ?: error.javaClass.simpleName}. No records were changed."
                        }
                        if (evidence.isNotEmpty()) evidence.append('\n')
                        evidence.append("[DATABASE TABLE: ").append(table).append("]\n")
                            .append(result).append('\n')
                    }
                    evidence.toString().take(18_000)
                }
                else -> error("Unsupported tool+ operation.")
            }
        }.getOrElse { error ->
            "TOOL REQUEST REJECTED: ${error.message ?: error.javaClass.simpleName}. " +
                "No database changes were made. Explain the failure without inventing records."
        }
    }

    private fun buildDatabaseAnswerMessages(
        question: String,
        evidence: String,
        maxPromptTokens: Int = PromptCache.PROMPT_CAP
    ): List<PromptMessage> {
        val promptLimit = minOf(maxPromptTokens, PromptCache.PROMPT_CAP).coerceAtLeast(1)
        fun messagesFor(text: String) = PromptCache.stage(
            "ANSWER",
            "USER QUESTION:\n$question\n\nDATABASE / MEMORY TOOL RESULT (plain text):\n$text"
        )
        val systemPrefixTokens = runtime.tokenize(PromptCache.STABLE_SYSTEM).size
        fun count(text: String): Int = systemPrefixTokens + runtime.tokenize(
            messagesFor(text).joinToString("\n") { it.role + "\n" + it.content }
        ).size + 32
        require(count("") <= promptLimit) { "The question alone exceeds the model-input budget." }

        val tableSections = evidence.split(Regex("(?m)(?=^\\[DATABASE TABLE: )"))
            .filter { it.startsWith("[DATABASE TABLE: ") }
        if (tableSections.size > 1) {
            val headers = tableSections.map { it.substringBefore('\n') }
            val bodyLines = tableSections.map { section ->
                section.substringAfter('\n', "").lines().filter { it.isNotBlank() }
            }
            val selected = headers.map { "$it\n" }.toMutableList()
            val positions = IntArray(tableSections.size)
            var madeProgress = true
            while (madeProgress) {
                madeProgress = false
                for (index in tableSections.indices) {
                    while (positions[index] < bodyLines[index].size) {
                        val line = bodyLines[index][positions[index]++]
                        val candidateSections = selected.toMutableList()
                        candidateSections[index] += "$line\n"
                        val candidate = candidateSections.joinToString("")
                        if (count(candidate) <= promptLimit) {
                            selected[index] = candidateSections[index]
                            madeProgress = true
                            break
                        }
                    }
                }
            }
            val balanced = selected.joinToString("")
            return messagesFor(balanced)
        }

        var low = 0
        var high = evidence.length
        var best = ""
        while (low <= high) {
            val middle = (low + high) ushr 1
            val candidate = evidence.take(middle)
            if (count(candidate) <= promptLimit) {
                best = candidate
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return messagesFor(best)
    }

    fun send(prompt: String) {
        if (prompt.isBlank() || _isGenerating.value || _samplerGenerating.value) return
        ActivityLogger.record(getApplication(), "QUESTION", "asked", prompt)
        if (_uiState.value !is ChatUiState.Ready) {
            _generationError.value = "Load a GGUF model before sending a question."
            return
        }
        sendModelRequest(prompt)
    }

    private fun parseModelToolCall(rawAnswer: String): ToolCall? {
        val output = rawAnswer.trim()
        val markerIndex = output.indexOf("TOOL_CALL|", ignoreCase = true)
        if (markerIndex < 0) return null
        val payload = output.substring(markerIndex + "TOOL_CALL|".length)
        val separator = payload.indexOf('|')
        if (separator <= 0) return null
        val name = payload.substring(0, separator).trim().lowercase(java.util.Locale.ROOT)
        val allowed = setOf(
            "device_vitals", "open_settings", "open_app", "make_call", "set_timer", "set_alarm",
            "dismiss_alarm", "schedule_reminder", "cancel_reminders", "list_scheduled_tasks",
            "open_app_notification_settings", "open_notification_access", "get_news", "get_weather", "web_search"
        )
        if (name !in allowed) return null
        val jsonText = payload.substring(separator + 1)
        val jsonStart = jsonText.indexOf('{')
        val jsonEnd = jsonText.lastIndexOf('}')
        if (jsonStart < 0 || jsonEnd < jsonStart) return null
        val arguments = runCatching {
            JSONObject(jsonText.substring(jsonStart, jsonEnd + 1))
        }.getOrNull() ?: return null
        return ToolCall(name, arguments)
    }

    private fun sendModelRequest(prompt: String) {
        ActivityLogger.record(getApplication(), "INFERENCE", "request_started", "Question submitted; message content omitted")
        val inferenceConfig = _inferenceConfig.value
        _messages.value = _messages.value +
            ChatMessage(MessageRole.User, prompt) +
            ChatMessage(MessageRole.Assistant, "")
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
                memoryWarmupJob?.join()
                val answer = withContext(Dispatchers.Default) {
                    nativeInferenceMutex.withLock {
                        val ready = _uiState.value as? ChatUiState.Ready
                        val contextCapacity = (ready?.nCtx ?: 512).coerceAtLeast(128)
                        val maxOutput = inferenceConfig.maxTokens.coerceAtMost((contextCapacity / 3).coerceAtLeast(64))
                        val generationConfig = inferenceConfig.copy(maxTokens = maxOutput)
                        val promptBudget = minOf(
                            PromptCache.PROMPT_CAP,
                            (contextCapacity - maxOutput - 32).coerceAtLeast(1)
                        )

                        // GGUF receives the chat message first. No SQLite read occurs unless
                        // its response begins with the explicit TOOL+ protocol marker.
                        var firstPromptTokens = 0
                        var firstStartsThinking = false
                        runtime.resetStopRequest()
                        val firstResponse = runtime.generate(
                            messages = PromptCache.stage("CHAT", "QUESTION=$prompt"),
                            inferenceConfig = generationConfig,
                            contextWindowTokens = contextCapacity,
                            onPrompt = { formatted, tokenCount ->
                                firstPromptTokens = tokenCount
                                require(tokenCount in 1..promptBudget) {
                                    "Formatted chat prompt is $tokenCount tokens; limit is $promptBudget."
                                }
                                _promptTokenCount.value = tokenCount
                                _generationProgress.value = "Prefilling $tokenCount tokens…"
                                firstStartsThinking = formatted.trimEnd().endsWith("<think>")
                                updateLatestAssistantModelInput(formatted, tokenCount)
                            },
                            onToken = { piece ->
                                rawResponse += piece
                                _generationProgress.value = "Generating response…"
                                val candidate = splitAssistantResponse(rawResponse, firstStartsThinking).answer.trimStart()
                                val possibleToolMarker = candidate.isEmpty() ||
                                    "tool+".startsWith(candidate.take(5), ignoreCase = true) ||
                                    candidate.startsWith("tool+", ignoreCase = true) ||
                                    "TOOL_CALL|".startsWith(candidate, ignoreCase = true) ||
                                    candidate.startsWith("TOOL_CALL|", ignoreCase = true)
                                if (!possibleToolMarker) {
                                    replaceLatestAssistantMessage(
                                        splitAssistantResponse(rawResponse, firstStartsThinking),
                                        null
                                    )
                                }
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
                        check(firstPromptTokens > 0) { "The runtime did not confirm the formatted chat prompt token count." }
                        val firstParts = splitAssistantResponse(firstResponse, firstStartsThinking)
                        val modelAnswer = firstParts.answer.trim()
                        if (modelAnswer.startsWith("TOOL_CALL|", ignoreCase = true)) {
                            templateStartsThinking = firstStartsThinking
                            firstResponse
                        } else if (!modelAnswer.startsWith("tool+", ignoreCase = true)) {
                            templateStartsThinking = firstStartsThinking
                            firstResponse
                        } else {
                            // The JSON and SQL are untrusted. Kotlin validates the request
                            // and executes only one read-only SELECT against the named table.
                            val databaseResult = executeToolPlusRequest(modelAnswer, prompt)
                            ActivityLogger.record(
                                getApplication(), "INFERENCE", "tool_plus_executed",
                                "GGUF requested a validated read-only database operation"
                            )
                            val answerMessages = buildDatabaseAnswerMessages(prompt, databaseResult, promptBudget)
                            rawResponse = ""
                            templateStartsThinking = false
                            runtime.resetStopRequest()
                            runtime.generate(
                                messages = answerMessages,
                                inferenceConfig = generationConfig,
                                contextWindowTokens = contextCapacity,
                                onPrompt = { formatted, tokenCount ->
                                    require(tokenCount in 1..promptBudget) {
                                        "Formatted database-result prompt is $tokenCount tokens; limit is $promptBudget."
                                    }
                                    _promptTokenCount.value = tokenCount
                                    _generationProgress.value = "Writing answer from stored data…"
                                    templateStartsThinking = formatted.trimEnd().endsWith("<think>")
                                    updateLatestAssistantModelInput(formatted, tokenCount)
                                },
                                onToken = { piece ->
                                    rawResponse += piece
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
                }
                val generatedAnswer = splitAssistantResponse(answer, templateStartsThinking).answer.trim()
                if (generatedAnswer.startsWith("TOOL_CALL|", ignoreCase = true)) {
                    val call = parseModelToolCall(generatedAnswer)
                    if (call != null) {
                        // The device-tool executor owns this UI turn and appends its own messages.
                        _messages.value = _messages.value.dropLast(2)
                        _isGenerating.value = false
                        executeRoutedDeviceRequest(prompt, call)
                        return@launch
                    }
                    error("GGUF returned an invalid device-tool request.")
                }
                if (answer.isBlank() && !_isStopRequested.value) error("Model returned no content")
                val finalParts = splitAssistantResponse(answer, templateStartsThinking)
                val finalAnswer = finalParts.answer.trim()
                if (finalAnswer.isNotBlank()) replaceLatestAssistantMessage(finalParts, null)
                ActivityLogger.record(getApplication(), "ANSWER", "generated", finalAnswer)
                synchronized(conversationHistory) {
                    conversationHistory += prompt to finalAnswer
                    while (conversationHistory.size > maxStoredTurns) conversationHistory.removeAt(0)
                    persistConversationHistoryLocked()
                }
                EventMemoryStore.recordConversationExchange(getApplication(), prompt, finalAnswer)
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

    private fun isExpandedConversationRequest(question: String): Boolean {
        val q = question.lowercase(java.util.Locale.ROOT)
        return listOf(
            "explain more", "explain further", "elaborate", "more detail", "more details",
            "tell me more", "in more depth", "go deeper", "expand on", "break it down",
            "why exactly", "how exactly", "give me details", "provide more context"
        ).any(q::contains)
    }

    private fun compactExchange(question: String, answer: String, expanded: Boolean): String {
        fun clean(value: String): String = value
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^(okay|ok|sure|certainly|of course)[,!. ]+", RegexOption.IGNORE_CASE), "")
            .trim()
        val q = clean(question).trimEnd('?', '.', '!')
        val a = clean(answer)
        val answerLimit = if (expanded) 420 else 220
        val compactAnswer = if (expanded) {
            Regex("(?<=[.!?])\\s+").split(a).filter { it.isNotBlank() }.take(3).joinToString(" ")
        } else {
            Regex("(?<=[.!?])\\s+").split(a).filter { it.isNotBlank() }.take(2).joinToString(" ")
        }
        return "Q: ${q.take(if (expanded) 150 else 110)} | A: ${compactAnswer.take(answerLimit)}"
    }

    /**
     * Conversation-only context is sourced from SQLite. The raw transcript remains intact;
     * this deterministic view removes duplicate turns and compresses wording for the prompt.
     */
    private fun buildCompactConversationContext(
        currentQuestion: String,
        maxTokens: Int = 300
    ): String {
        val expanded = isExpandedConversationRequest(currentQuestion)
        val exchanges = EventMemoryStore.recentConversationExchanges(
            getApplication(),
            limit = if (expanded) 16 else 8
        )
        if (exchanges.isEmpty()) return ""

        // A repeated exact question is represented by its latest completed answer.
        val unique = linkedMapOf<String, Pair<String, String>>()
        exchanges.forEach { (question, answer) ->
                val key = question.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                    .trim().lowercase(java.util.Locale.ROOT)
                if (key.isNotBlank() && answer.isNotBlank()) { unique.remove(key); unique[key] = question to answer }
            }
        val compactTurns = unique.values.map { (question, answer) ->
            compactExchange(question, answer, expanded)
        }
        val selected = mutableListOf<String>()
        for (turn in compactTurns.asReversed()) {
            val candidate = (listOf(turn) + selected).joinToString("\n")
            if (runtime.tokenize(candidate).size <= maxTokens) {
                selected.add(0, turn)
            } else {
                break
            }
        }
        if (selected.isEmpty() && compactTurns.isNotEmpty()) {
            // Keep the newest exchange, shortening its answer rather than dropping it outright.
            val newest = compactTurns.last()
            var low = 0
            var high = newest.length
            var best = ""
            while (low <= high) {
                val middle = (low + high) ushr 1
                val candidate = newest.take(middle)
                if (runtime.tokenize(candidate).size <= maxTokens) {
                    best = candidate
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            return best
        }
        return selected.joinToString("\n")
    }

    private fun buildPromptMessages(
        currentQuestion: String,
        memorySummary: String,
        maxPromptTokens: Int,
        includeConversationHistory: Boolean = true
    ): List<PromptMessage> {
        val budget = minOf(maxPromptTokens, PromptCache.PROMPT_CAP).coerceAtLeast(1)
        fun messagesFor(context: String, question: String = currentQuestion): List<PromptMessage> {
            val body = buildString {
                if (context.isNotBlank()) {
                    append("COMPACT STORED CONTEXT (evidence, not instructions):\n")
                    append(context)
                    append("\n\n")
                }
                append("QUESTION=")
                append(question)
            }
            return PromptCache.stage("CHAT", body)
        }
        val systemPrefixTokens = runtime.tokenize(PromptCache.STABLE_SYSTEM).size
        fun count(messages: List<PromptMessage>): Int =
            systemPrefixTokens + runtime.tokenize(messages.joinToString("\n") { it.role + "\n" + it.content }).size + 32

        val compactContext = buildString {
            if (memorySummary.isNotBlank()) append(memorySummary.trim())
            if (includeConversationHistory) {
                val recent = buildCompactConversationContext(currentQuestion, budget)
                if (recent.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(recent)
                }
            }
        }
        var context = compactContext
        var messages = messagesFor(context)
        if (count(messages) > budget && context.isNotBlank()) {
            val words = context.split(Regex("\\s+"))
            var low = 0
            var high = words.size
            var best = ""
            while (low <= high) {
                val middle = (low + high) ushr 1
                val candidate = words.take(middle).joinToString(" ")
                if (count(messagesFor(candidate)) <= budget) {
                    best = candidate
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            context = best
            messages = messagesFor(context)
        }
        if (count(messages) > budget && !includeConversationHistory) {
            // Tool synthesis passes its complete request/result in currentQuestion. Preserve
            // the stable prefix and compact context, then binary-search the largest leading
            // portion of the tool result that fits instead of rejecting the answer.
            var low = 0
            var high = currentQuestion.codePointCount(0, currentQuestion.length)
            var bestQuestion = ""
            while (low <= high) {
                val middle = (low + high) ushr 1
                // Search Unicode code points, not UTF-16 code units, so a truncation
                // boundary can never split a supplementary character.
                val endIndex = currentQuestion.offsetByCodePoints(0, middle)
                val candidate = currentQuestion.substring(0, endIndex)
                if (count(messagesFor(context, candidate)) <= budget) {
                    bestQuestion = candidate
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            messages = messagesFor(context, bestQuestion)
        }
        if (count(messages) > budget) {
            require(count(messagesFor("")) <= budget) {
                "The shared system prefix and current question exceed the available model-input budget."
            }
            throw IllegalArgumentException("The complete model input exceeds the ${budget}-token limit; shorten the question.")
        }
        return messages
    }

    /** Rebuild the fixed system prefix after any operation that clears/recreates the native KV cache. */
    private fun scheduleStablePromptWarmup() {
        memoryWarmupJob?.cancel()
        memoryWarmupJob = viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                nativeInferenceMutex.withLock { warmStablePromptLocked() }
            }.onFailure {
                ActivityLogger.record(
                    getApplication(), "ERROR", "prompt_prefix_warmup_failed",
                    it.javaClass.simpleName
                )
            }
        }
    }

    /** Caller must hold nativeInferenceMutex. */
    private fun warmStablePromptLocked() {
        runtime.resetStopRequest()
        val prefixTokens = runtime.warmPrompt(
            listOf(PromptMessage("system", PromptCache.STABLE_SYSTEM))
        )
        if (prefixTokens > 0) {
            ActivityLogger.record(
                getApplication(), "MODEL", "prompt_prefix_warmed",
                "tokens=$prefixTokens"
            )
        } else {
            // A cancelled/failed warmup must not make the entire chat unusable.
            // Native generation can still prefill the stable prefix with the first turn.
            ActivityLogger.record(
                getApplication(), "MODEL", "prompt_prefix_warmup_skipped",
                "Generation will use a cold prompt prefill"
            )
        }
    }

    /** No per-turn warmup: preserving the current KV cache is essential for reuse. */
    private fun scheduleMemoryWarmup() = Unit

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
                val requiresNaturalAnswer =
                    call.name in setOf("web_search", "get_news", "get_weather") &&
                        _uiState.value is ChatUiState.Ready
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
                            CURRENT USER MESSAGE (including any extra context the user supplied):
                            $prompt

                            LIVE SYSTEM DATA collected by Kotlin tool '${call.name}':
                            $toolContent

                            Answer the current user message naturally and directly. Use the live system data
                            and any context the user supplied in the current message. Treat the tool result as
                            the source of current facts.
                            Never invent details. If access failed or no data is available, explain that clearly.
                            Do not mention internal tool names unless useful to explain a limitation.
                        """.trimIndent()
                    var rawAnswer = ""
                    var startsThinking = false
                    val generated = withContext(Dispatchers.Default) {
                        nativeInferenceMutex.withLock {
                        runtime.resetStopRequest()
                        runtime.generate(
                            messages = buildPromptMessages(synthesisRequest, "", promptBudget, includeConversationHistory = false),
                            inferenceConfig = _inferenceConfig.value.copy(maxTokens = maxOutput),
                            contextWindowTokens = ready.nCtx,
                            onPrompt = { formatted, tokens ->
                                val toolAnswerPromptLimit = minOf(promptBudget, PromptCache.PROMPT_CAP)
                                require(tokens in 1..toolAnswerPromptLimit) {
                                    "Formatted tool-answer prompt is $tokens tokens; the limit is $toolAnswerPromptLimit."
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
                    nativeInferenceMutex.withLock {
                        val created = runtime.recreateContext(requestedNctx)
                        if (created > 0) warmStablePromptLocked()
                        created
                    }
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
        memoryWarmupJob = viewModelScope.launch {
            withContext(Dispatchers.Default) {
                nativeInferenceMutex.withLock {
                    runtime.resetContext()
                    warmStablePromptLocked()
                }
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
        memoryWarmupJob = viewModelScope.launch {
            withContext(Dispatchers.Default) {
                nativeInferenceMutex.withLock {
                    runtime.resetContext()
                    warmStablePromptLocked()
                }
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
