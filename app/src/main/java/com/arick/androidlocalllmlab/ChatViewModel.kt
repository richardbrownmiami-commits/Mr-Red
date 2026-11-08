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
    private val conversationDatabase = ConversationDatabase(application)
    private val personaCardStore = PersonaCardStore(application)
    private val _personaCard = MutableStateFlow(personaCardStore.load())
    val personaCard: StateFlow<PersonaCard> = _personaCard.asStateFlow()

    private val _conversationSummary = MutableStateFlow("")
    val conversationSummary: StateFlow<String> = _conversationSummary.asStateFlow()

    private val _summaryLines = MutableStateFlow(selectedModelStore.getSummaryLines())
    val summaryLines: StateFlow<Int> = _summaryLines.asStateFlow()

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // System prompt is session configuration, not rendered as a chat bubble.
    private val _systemPrompt = MutableStateFlow("")
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    // Callback after applying native chat template, showing actual formatted prompt.
    private val _finalPrompt = MutableStateFlow("")
    val finalPrompt: StateFlow<String> = _finalPrompt.asStateFlow()

    private val _promptTokenCount = MutableStateFlow(0)
    val promptTokenCount: StateFlow<Int> = _promptTokenCount.asStateFlow()

    private val _trimmedHistoryTurnCount = MutableStateFlow(0)
    val trimmedHistoryTurnCount: StateFlow<Int> = _trimmedHistoryTurnCount.asStateFlow()

    // Default context window sizing.
    private val _requestedNctx = MutableStateFlow(512)
    val requestedNctx: StateFlow<Int> = _requestedNctx.asStateFlow()

    // CPU threads bounded by available processor cores.
    private val _cpuThreads = MutableStateFlow(DEFAULT_CPU_THREADS)
    val cpuThreads: StateFlow<Int> = _cpuThreads.asStateFlow()

    private val _inferenceConfig = MutableStateFlow(InferenceConfig(maxTokens = 256))
    val inferenceConfig: StateFlow<InferenceConfig> = _inferenceConfig.asStateFlow()

    private val _thinkingEnabled = MutableStateFlow(true)
    val thinkingEnabled: StateFlow<Boolean> = _thinkingEnabled.asStateFlow()

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
        restorePersistedConversation()
    }

    private fun restorePersistedConversation() {
        viewModelScope.launch {
            val savedMessages = withContext(Dispatchers.IO) {
                conversationDatabase.loadAllMessages()
            }
            val savedSummary = withContext(Dispatchers.IO) {
                conversationDatabase.getSummary()
            }
            if (savedMessages.isNotEmpty()) {
                _messages.value = savedMessages
            }
            _conversationSummary.value = if (savedSummary.isNotBlank()) {
                savedSummary
            } else if (savedMessages.isNotEmpty()) {
                rebuildSummaryFromHistory(savedMessages, _summaryLines.value)
            } else {
                ""
            }
        }
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
        // Default thinking mode based on model capability.
        if ("$modelName $modelDescription".contains("instruct-2507", ignoreCase = true)) {
            _thinkingEnabled.value = false
        }
        val savedMessages = withContext(Dispatchers.IO) {
            conversationDatabase.loadAllMessages()
        }
        val savedSummary = withContext(Dispatchers.IO) {
            conversationDatabase.getSummary()
        }
        _messages.value = savedMessages
        _conversationSummary.value = if (savedSummary.isNotBlank()) {
            savedSummary
        } else if (savedMessages.isNotEmpty()) {
            rebuildSummaryFromHistory(savedMessages, _summaryLines.value)
        } else {
            ""
        }
        _finalPrompt.value = ""
        _promptTokenCount.value = 0
        _trimmedHistoryTurnCount.value = 0
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

    fun send(prompt: String) {
        if (prompt.isBlank() || _isGenerating.value) return

        // Deterministic device actions and lookups are routed by Kotlin FIRST.
        DeviceIntentRouter.route(prompt)?.let { toolCall ->
            if (isResearchTool(toolCall.name) && _uiState.value is ChatUiState.Ready) {
                executeSearchAugmentedRequest(prompt, toolCall)
                return
            }
            executeRoutedDeviceRequest(prompt, toolCall)
            return
        }

        // Local personality/help answers need neither model tokens nor model availability.
        AssistantKnowledgeRouter.answer(prompt, _personaCard.value)?.let { localAnswer ->
            appendLocalAssistantTurn(prompt, localAnswer, "Answered by Mr-Red locally")
            return
        }
        if (_uiState.value !is ChatUiState.Ready) {
            appendLocalAssistantTurn(
                prompt,
                "I can handle phone actions, device status, alarms, reminders, and online lookups without a model. For general questions, explanations, or writing, load a local GGUF model and I can help with that.",
                "Explained local-model requirement"
            )
            return
        }

        // Empty assistant message used as target for streaming tokens.
        // Not passed to native prompt until completed.
        val visibleHistory = _messages.value.filter { it.content.isNotBlank() }
        val actualNctx = (_uiState.value as? ChatUiState.Ready)?.nCtx ?: return
        val inferenceConfig = _inferenceConfig.value
        val nativeUserPrompt = prompt.withQwen3ThinkingInstruction()
        val userMessage = ChatMessage(MessageRole.User, prompt)
        _messages.value = visibleHistory + userMessage + listOf(
            ChatMessage(MessageRole.Assistant, "")
        )
        viewModelScope.launch(Dispatchers.IO) {
            conversationDatabase.saveMessage(userMessage)
        }
        _generationError.value = null
        _finalPrompt.value = ""
        _promptTokenCount.value = 0
        _trimmedHistoryTurnCount.value = 0
        _generationProgress.value = "Preparing prompt…"
        _generationMetrics.value = null
        // Clear previous stop flag before starting new generation.
        runtime.resetStopRequest()
        _isStopRequested.value = false
        _isGenerating.value = true
        val generationStartedAtNanos = System.nanoTime()

        viewModelScope.launch {
            try {
                val answer = withContext(Dispatchers.Default) {
                    val trimmedHistory = buildHistoryWithinContextBudget(
                        userSystemPrompt = _systemPrompt.value.trim().takeIf { it.isNotEmpty() },
                        currentUserPrompt = nativeUserPrompt,
                        nCtx = actualNctx,
                        reservedOutputTokens = inferenceConfig.maxTokens
                    )
                    _trimmedHistoryTurnCount.value = trimmedHistory.droppedTurnCount
                    runFunctionCallingLoop(
                        initialMessages = trimmedHistory.messages,
                        inferenceConfig = inferenceConfig,
                        nCtx = actualNctx
                    )
                }
                if (answer.isBlank() && !_isStopRequested.value) {
                    error("Model returned no content")
                } else if (answer.isNotBlank()) {
                    val finalAssistant = _messages.value.lastOrNull { it.role == MessageRole.Assistant }
                    if (finalAssistant != null && finalAssistant.content.isNotBlank()) {
                        withContext(Dispatchers.IO) {
                            conversationDatabase.saveMessage(finalAssistant)
                        }
                        recordCompletedTurnInSummary(prompt, finalAssistant.content)
                    }
                }
            } catch (error: Throwable) {
                _generationError.value = error.message ?: "Generation failed"
            } finally {
                finishLatestAssistantAgentTrace(generationStartedAtNanos)
                _generationProgress.value = null
                _isGenerating.value = false
                _isStopRequested.value = false
            }
        }
    }

    private fun executeRoutedDeviceRequest(prompt: String, call: ToolCall) {
        val userMessage = ChatMessage(MessageRole.User, prompt)
        val startedAt = System.nanoTime()
        _messages.value = _messages.value.filter { it.content.isNotBlank() } + listOf(
            userMessage,
            ChatMessage(MessageRole.Assistant, "", agentTrace = listOf(AgentTraceItem("Handled locally: ${call.name}")))
        )
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
                withContext(Dispatchers.IO) {
                    conversationDatabase.saveMessage(userMessage)
                    conversationDatabase.saveMessage(assistant)
                }
                recordCompletedTurnInSummary(prompt, assistant.content)
            } catch (error: Throwable) {
                _generationError.value = error.message ?: "Device request failed"
                val assistant = ChatMessage(MessageRole.Assistant, error.message ?: "Device request failed")
                _messages.value = _messages.value.dropLast(1) + assistant
                withContext(Dispatchers.IO) {
                    conversationDatabase.saveMessage(userMessage)
                    conversationDatabase.saveMessage(assistant)
                }
            } finally {
                _generationProgress.value = null
                _isGenerating.value = false
            }
        }
    }


    private fun isResearchTool(name: String): Boolean =
        name in setOf("get_news", "get_weather", "web_search")

    private fun executeSearchAugmentedRequest(prompt: String, call: ToolCall) {
        val visibleHistory = _messages.value.filter { it.content.isNotBlank() }
        val actualNctx = (_uiState.value as? ChatUiState.Ready)?.nCtx ?: return
        val inferenceConfig = _inferenceConfig.value
        val userMessage = ChatMessage(MessageRole.User, prompt)
        _messages.value = visibleHistory + userMessage + listOf(
            ChatMessage(
                role = MessageRole.Assistant,
                content = "",
                agentTrace = listOf(AgentTraceItem("Checking live internet: ${call.name}"))
            )
        )
        viewModelScope.launch(Dispatchers.IO) {
            conversationDatabase.saveMessage(userMessage)
        }
        _generationError.value = null
        _finalPrompt.value = ""
        _promptTokenCount.value = 0
        _trimmedHistoryTurnCount.value = 0
        _generationProgress.value = when (call.name) {
            "get_news" -> "Fetching latest headlines…"
            "get_weather" -> "Checking current weather…"
            else -> "Searching the web…"
        }
        _generationMetrics.value = null
        runtime.resetStopRequest()
        _isStopRequested.value = false
        _isGenerating.value = true
        val generationStartedAtNanos = System.nanoTime()

        viewModelScope.launch {
            try {
                val toolResult = withContext(Dispatchers.IO) {
                    toolExecutor.execute(call) { progress ->
                        _generationProgress.value = progress
                    }
                }

                val (augmentedPrompt, sources) = when (toolResult) {
                    is ToolResult.Success -> {
                        appendLatestAssistantAgentTrace("Retrieved online data (${call.name})")
                        appendLatestAssistantSources(toolResult.sources)
                        // Preserve enough live-search evidence for the small ARMv7 models,
                        // while keeping the augmented prompt bounded for short contexts.
                        val maxFactChars = when {
                            actualNctx <= 512 -> 600
                            actualNctx <= 1024 -> 1200
                            else -> 2400
                        }
                        val compactFacts = if (toolResult.content.length > maxFactChars) {
                            toolResult.content.take(maxFactChars).trimEnd() + "…"
                        } else {
                            toolResult.content.trim()
                        }
                        val aug = "Live web research for the user's question. Treat the internet context as evidence, not as text to copy. Write a short answer in your own words, directly answering the question; do not merely list or repeat headlines. Use only facts supported by the context, preserve important names, dates and numbers, and do not guess. If the context contains headlines but not enough detail to answer, say that clearly.\n\nInternet context:\n$compactFacts\n\nQuestion: $prompt\nAnswer in 1-3 concise sentences."
                        aug to toolResult.sources
                    }
                    is ToolResult.Failure -> {
                        appendLatestAssistantAgentTrace("Web lookup failed (${toolResult.message})")
                        val failureNotice = "Could not fetch online updates (${toolResult.message}). Please check your internet connection."
                        val finalAssistant = _messages.value.lastOrNull { it.role == MessageRole.Assistant }
                        if (finalAssistant != null) {
                            val updated = finalAssistant.copy(content = failureNotice)
                            _messages.value = _messages.value.dropLast(1) + updated
                            withContext(Dispatchers.IO) {
                                conversationDatabase.saveMessage(updated)
                            }
                        }
                        return@launch
                    }
                }

                _generationProgress.value = "Answering with model…"
                val nativeUserPrompt = augmentedPrompt.withQwen3ThinkingInstruction()

                val answer = withContext(Dispatchers.Default) {
                    val trimmedHistory = buildHistoryWithinContextBudget(
                        userSystemPrompt = _systemPrompt.value.trim().takeIf { it.isNotEmpty() },
                        currentUserPrompt = nativeUserPrompt,
                        nCtx = actualNctx,
                        reservedOutputTokens = inferenceConfig.maxTokens
                    )
                    _trimmedHistoryTurnCount.value = trimmedHistory.droppedTurnCount
                    runFunctionCallingLoop(
                        initialMessages = trimmedHistory.messages,
                        inferenceConfig = inferenceConfig,
                        nCtx = actualNctx
                    )
                }

                if (answer.isBlank() && !_isStopRequested.value) {
                    error("Model returned no content")
                } else if (answer.isNotBlank()) {
                    appendLatestAssistantAgentTrace("Synthesized with local model")
                    val finalAssistant = _messages.value.lastOrNull { it.role == MessageRole.Assistant }
                    if (finalAssistant != null && finalAssistant.content.isNotBlank()) {
                        val toSave = if (sources.isNotEmpty() && finalAssistant.sourceLinks.isEmpty()) {
                            finalAssistant.copy(sourceLinks = sources)
                        } else finalAssistant
                        withContext(Dispatchers.IO) {
                            conversationDatabase.saveMessage(toSave)
                        }
                        recordCompletedTurnInSummary(prompt, finalAssistant.content)
                    }
                }
            } catch (error: Throwable) {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: "Generation failed"
                _generationError.value = detail
                // Keep a visible explanation beside the already-attached sources instead
                // of leaving the user with links and an empty assistant bubble.
                val finalAssistant = _messages.value.lastOrNull { it.role == MessageRole.Assistant }
                if (finalAssistant != null && finalAssistant.content.isBlank()) {
                    val updated = finalAssistant.copy(
                        content = "I found live results, but the local model couldn't write a summary: $detail"
                    )
                    _messages.value = _messages.value.dropLast(1) + updated
                    withContext(Dispatchers.IO) {
                        conversationDatabase.saveMessage(updated)
                    }
                }
            } finally {
                finishLatestAssistantAgentTrace(generationStartedAtNanos)
                _generationProgress.value = null
                _isGenerating.value = false
                _isStopRequested.value = false
            }
        }
    }

    private fun runFunctionCallingLoop(
        initialMessages: List<ChatMessage>,
        inferenceConfig: InferenceConfig,
        nCtx: Int
    ): String {
        var messagesForModel = initialMessages
        repeat(FunctionCalling.maxToolCalls + 1) { attempt ->
            val promptTokenCount = runtime.countChatPromptTokens(messagesForModel)
            val availableOutputTokens = nCtx - promptTokenCount
            val minOutputTokens = minOf(32, (nCtx / 10).coerceAtLeast(16))
            if (availableOutputTokens < minOutputTokens) {
                // If context is completely exhausted after tool execution, return last response
                return if (attempt > 0) {
                    messagesForModel.lastOrNull { it.role == MessageRole.Assistant }?.content?.ifBlank { null }
                        ?: "Tool executed successfully."
                } else {
                    error("Prompt uses $promptTokenCount tokens, leaving only $availableOutputTokens tokens. Please shorten prompt or clear history.")
                }
            }
            // Context trimming budget management.
            // Adjust output tokens based on remaining context space.
            val effectiveInferenceConfig = inferenceConfig.copy(
                maxTokens = minOf(inferenceConfig.maxTokens, availableOutputTokens)
            )
            val generatedAnswer = generateForCurrentTurn(
                messages = messagesForModel,
                inferenceConfig = effectiveInferenceConfig
            )
            if (_isStopRequested.value) {
                return generatedAnswer
            }

            val toolCall = FunctionCalling.parseToolCall(generatedAnswer) ?: return generatedAnswer
            if (attempt == FunctionCalling.maxToolCalls) {
                return "Tool execution limit reached. Result: $generatedAnswer"
            }

            // Intermediate tool call JSON is processed and replaced by final response.
            replaceLatestAssistantMessage(AssistantResponseParts(reasoning = "", answer = ""), null)
            appendLatestAssistantAgentTrace(toolCall.traceDescription())
            _generationProgress.value = "Executing tool: ${toolCall.name}…"
            val toolResult = toolExecutor.execute(toolCall) { progress ->
                _generationProgress.value = progress
                appendLatestAssistantAgentTrace(progress)
            }
            val toolResultText = when (toolResult) {
                is ToolResult.Success -> {
                    appendLatestAssistantAgentTrace("Tool executed: ${toolCall.name}")
                    appendLatestAssistantSources(toolResult.sources)
                    toolResult.content
                }
                is ToolResult.Failure -> {
                    appendLatestAssistantAgentTrace("Tool failed: ${toolResult.message}")
                    "Tool failed: ${toolResult.message}"
                }
            }

            // Truncate tool result if context is compact (e.g. 512 context)
            val maxToolChars = if (nCtx <= 512) 250 else if (nCtx <= 1024) 500 else 1500
            val compactToolText = if (toolResultText.length > maxToolChars) {
                toolResultText.take(maxToolChars) + "… [trimmed]"
            } else {
                toolResultText
            }

            val generatedParts = splitAssistantResponse(generatedAnswer, templateStartsThinking = false)
            messagesForModel = messagesForModel + listOf(
                ChatMessage(
                    role = MessageRole.Assistant,
                    content = "{\"name\":\"${toolCall.name}\"}",
                    reasoningContent = generatedParts.reasoning
                ),
                ChatMessage(
                    MessageRole.User,
                    "Tool result: $compactToolText. Answer the user based on this result. Do not call tools again unless necessary."
                )
            )
        }
        return "Tool call process finished."
    }

    private fun generateForCurrentTurn(
        messages: List<ChatMessage>,
        inferenceConfig: InferenceConfig
    ): String {
        var assistantRawContent = ""
        var templateStartsThinking = false
        var shouldRenderAssistant = false
        var reasoningStartedAtNanos: Long? = null
        var reasoningFinishedAtNanos: Long? = null
        val generatedAnswer = runtime.generate(
            messages = messages,
            inferenceConfig = inferenceConfig,
            onPrompt = { finalPrompt, tokenCount ->
                _finalPrompt.value = finalPrompt
                _promptTokenCount.value = tokenCount
                _generationProgress.value = "Prefilling $tokenCount tokens…"
                templateStartsThinking = finalPrompt.trimEnd().endsWith("<think>")
            },
            onToken = { piece ->
                _generationProgress.value = "Generating response…"
                assistantRawContent += piece
                if (!shouldRenderAssistant) {
                    if (FunctionCalling.shouldDeferToolRendering(assistantRawContent)) {
                        return@generate
                    }
                    // Flush buffered tokens once response body is identified.
                    shouldRenderAssistant = true
                }
                val assistantParts = splitAssistantResponse(assistantRawContent, templateStartsThinking)
                val nowNanos = System.nanoTime()
                val durationMillis = assistantParts.reasoning.takeIf { it.isNotBlank() }?.let {
                    val startedAt = reasoningStartedAtNanos ?: nowNanos.also { timestamp ->
                        reasoningStartedAtNanos = timestamp
                    }
                    if (assistantParts.answer.isNotBlank() && reasoningFinishedAtNanos == null) {
                        reasoningFinishedAtNanos = nowNanos
                    }
                    ((reasoningFinishedAtNanos ?: nowNanos) - startedAt) / 1_000_000
                }
                replaceLatestAssistantMessage(assistantParts, durationMillis)
            },
            onMetrics = { metrics ->
                _generationMetrics.value = metrics
                _memorySnapshots.value = _memorySnapshots.value.copy(
                    afterGeneration = takeMemorySnapshot(),
                    afterModelReleased = null
                )
            }
        )
        // Backfill with complete UTF-8 response.
        // Tool JSON processed via function calling loop.
        if (FunctionCalling.parseToolCall(generatedAnswer) == null) {
            replaceLatestAssistantMessage(
                splitAssistantResponse(generatedAnswer, templateStartsThinking),
                null
            )
        }
        return generatedAnswer
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

    fun updateSummaryLines(lines: Int) {
        val safeLines = when (lines) {
            0 -> 0
            2 -> 2
            7 -> 7
            else -> 4
        }
        _summaryLines.value = safeLines
        selectedModelStore.setSummaryLines(safeLines)
        val refreshed = if (safeLines == 0) {
            ""
        } else if (_messages.value.isNotEmpty()) {
            rebuildSummaryFromHistory(_messages.value, safeLines)
        } else {
            _conversationSummary.value.split("\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .takeLast(safeLines)
                .joinToString("\n")
        }
        _conversationSummary.value = refreshed
        viewModelScope.launch(Dispatchers.IO) {
            conversationDatabase.saveSummary(refreshed)
        }
    }

    private fun recordCompletedTurnInSummary(userPrompt: String, assistantAnswer: String) {
        val maxLines = _summaryLines.value
        if (maxLines <= 0) {
            if (_conversationSummary.value.isNotEmpty()) {
                _conversationSummary.value = ""
                viewModelScope.launch(Dispatchers.IO) {
                    conversationDatabase.saveSummary("")
                }
            }
            return
        }

        val cleanUser = userPrompt.trim().take(80).replace("\n", " ").trim()
        val cleanAssistant = assistantAnswer.trim().take(120).replace("\n", " ").trim()
        if (cleanUser.isBlank() || cleanAssistant.isBlank()) return

        val newNote = "User: $cleanUser -> Assistant: $cleanAssistant"
        val currentNotes = _conversationSummary.value
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val updatedNotes = (currentNotes + newNote).takeLast(maxLines)
        val combined = updatedNotes.joinToString("\n")
        if (combined != _conversationSummary.value) {
            _conversationSummary.value = combined
            viewModelScope.launch(Dispatchers.IO) {
                conversationDatabase.saveSummary(combined)
            }
        }
    }

    private fun rebuildSummaryFromHistory(messages: List<ChatMessage>, maxLines: Int): String {
        if (maxLines <= 0) return ""
        val completedTurns = messages.toCompletedTurns()
        val notes = completedTurns.mapNotNull { turn ->
            val userMsg = turn.firstOrNull { it.role == MessageRole.User }?.content?.trim() ?: return@mapNotNull null
            val assistantMsg = turn.firstOrNull { it.role == MessageRole.Assistant }?.content?.trim() ?: return@mapNotNull null
            val cleanUser = userMsg.take(80).replace("\n", " ").trim()
            val cleanAssistant = assistantMsg.take(120).replace("\n", " ").trim()
            if (cleanUser.isNotBlank() && cleanAssistant.isNotBlank()) {
                "User: $cleanUser -> Assistant: $cleanAssistant"
            } else null
        }
        return notes.takeLast(maxLines).joinToString("\n")
    }

    /**
     * Builds prompt using only the configured compact rolling summary and the current user question.
     * Raw past turns are not included verbatim, ensuring prompt prefill token counts stay stable.
     */
    private fun buildHistoryWithinContextBudget(
        userSystemPrompt: String?,
        currentUserPrompt: String,
        nCtx: Int,
        reservedOutputTokens: Int
    ): TrimmedHistory {
        val minOutputReserve = minOf(48, (nCtx / 8).coerceAtLeast(16))
        val maxPromptBudget = (nCtx - minOutputReserve).coerceAtLeast(minOutputReserve)

        val targetOutputTokens = reservedOutputTokens.coerceIn(minOutputReserve, maxPromptBudget)
        val promptBudget = nCtx - targetOutputTokens

        val rawSummary = if (_summaryLines.value > 0) _conversationSummary.value.trim() else ""
        val summaryLinesList = if (rawSummary.isNotBlank()) {
            rawSummary.lines().map { it.trim() }.filter { it.isNotBlank() }
        } else emptyList()

        var droppedSummaryLines = 0
        var candidateMessages: List<ChatMessage>

        while (true) {
            val activeLines = summaryLinesList.drop(droppedSummaryLines)
            val memoryText = if (activeLines.isNotEmpty()) {
                "Conversation Memory:\n" + activeLines.joinToString("\n")
            } else null

            val systemText = listOfNotNull(
                userSystemPrompt?.takeIf { it.isNotBlank() },
                _personaCard.value.compactInstruction(),
                memoryText
            ).joinToString("\n\n").takeIf { it.isNotBlank() }

            candidateMessages = buildList {
                systemText?.takeIf { it.isNotBlank() }?.let { add(ChatMessage(MessageRole.System, it)) }
                add(ChatMessage(MessageRole.User, currentUserPrompt))
            }

            val tokenCount = runtime.countChatPromptTokens(candidateMessages)
            if (tokenCount <= promptBudget || droppedSummaryLines >= summaryLinesList.size) {
                break
            }
            droppedSummaryLines++
        }

        val baseTokenCount = runtime.countChatPromptTokens(candidateMessages)
        check(baseTokenCount <= maxPromptBudget) {
            "Prompt is too long ($baseTokenCount tokens). Context window of $nCtx requires at least $minOutputReserve tokens for output."
        }

        return TrimmedHistory(
            messages = candidateMessages,
            droppedTurnCount = droppedSummaryLines
        )
    }

    private fun List<ChatMessage>.toCompletedTurns(): List<List<ChatMessage>> {
        val completedTurns = mutableListOf<List<ChatMessage>>()
        var index = 0
        while (index + 1 < size) {
            val user = this[index]
            val assistant = this[index + 1]
            if (user.role == MessageRole.User && assistant.role == MessageRole.Assistant) {
                completedTurns += listOf(user, assistant)
                index += 2
            } else {
                index += 1
            }
        }
        return completedTurns
    }

    private data class TrimmedHistory(
        val messages: List<ChatMessage>,
        val droppedTurnCount: Int
    )

    fun updatePersonaCard(value: PersonaCard) {
        if (!_isGenerating.value) {
            _personaCard.value = value
            personaCardStore.save(value)
            // Retain the optional advanced custom prompt, if the user has set one.
        }
    }

    private fun appendLocalAssistantTurn(prompt: String, answer: String, trace: String) {
        val userMessage = ChatMessage(MessageRole.User, prompt)
        val assistant = ChatMessage(
            role = MessageRole.Assistant,
            content = answer,
            agentTrace = listOf(AgentTraceItem(trace))
        )
        _messages.value = _messages.value.filter { it.content.isNotBlank() } + userMessage + assistant
        _generationError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            conversationDatabase.saveMessage(userMessage)
            conversationDatabase.saveMessage(assistant)
        }
        recordCompletedTurnInSummary(prompt, answer)
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

    fun updateThinkingEnabled(enabled: Boolean) {
        if (!_isGenerating.value) {
            _thinkingEnabled.value = enabled
        }
    }

    /** Qwen3 thinking prompt instruction toggle. */
    private fun String.withQwen3ThinkingInstruction(): String {
        val readyState = _uiState.value as? ChatUiState.Ready
            ?: return this
        if (!readyState.supportsQwen3ThinkingSwitch()) {
            return this
        }
        return "$this\n${if (_thinkingEnabled.value) "/think" else "/no_think"}"
    }

    private fun ChatUiState.Ready.supportsQwen3ThinkingSwitch(): Boolean {
        val identity = "$modelName $modelDescription"
        return identity.contains("qwen3", ignoreCase = true) &&
                !identity.contains("instruct-2507", ignoreCase = true)
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
        // Set native atomic cancellation flag so native loop safely exits.
        // Native generation terminates at next safe token boundary.
        runtime.requestStop()
    }

    /** Clears chat history in UI and SQLite; retains loaded model and native context. */
    fun clearConversation() {
        if (_isGenerating.value || _uiState.value !is ChatUiState.Ready) {
            return
        }

        _messages.value = emptyList()
        _conversationSummary.value = ""
        viewModelScope.launch(Dispatchers.IO) {
            conversationDatabase.clearAll()
        }
        clearLastPromptState()
        _generationError.value = null
    }

    /**
     * Clears native KV cache while keeping Kotlin chat history and model.
     * Resets inference state.
     * KV cache state management.
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
        _finalPrompt.value = ""
        _promptTokenCount.value = 0
        _trimmedHistoryTurnCount.value = 0
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
