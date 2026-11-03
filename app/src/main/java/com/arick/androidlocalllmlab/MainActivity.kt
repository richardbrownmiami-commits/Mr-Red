package com.arick.androidlocalllmlab

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arick.androidlocalllmlab.ui.theme.AndroidLocalLlmLabTheme
import com.arick.androidlocalllmlab.ui.theme.AppColors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val chatViewModel by viewModels<ChatViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidLocalLlmLabTheme {
                MainAppScreen(viewModel = chatViewModel)
            }
        }
    }
}

enum class AppPage(val title: String, val icon: ImageVector) {
    Chat("Chat", Icons.Default.Chat),
    Dashboard("Dashboard", Icons.Default.Dashboard),
    Tools("Tools", Icons.Default.Build),
    Settings("Settings", Icons.Default.Settings)
}

@Composable
private fun MainAppScreen(viewModel: ChatViewModel) {
    var selectedPage by remember { mutableStateOf(AppPage.Chat) }
    val modelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadModel(it) }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = AppColors.PageBackground,
                contentColor = AppColors.PrimaryAction
            ) {
                AppPage.values().forEach { page ->
                    NavigationBarItem(
                        selected = selectedPage == page,
                        onClick = { selectedPage = page },
                        icon = { Icon(page.icon, contentDescription = page.title) },
                        label = { Text(page.title, fontSize = 12.sp, fontWeight = if (selectedPage == page) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppColors.PrimaryActionGlow,
                            selectedTextColor = AppColors.PrimaryActionGlow,
                            unselectedIconColor = AppColors.TextSecondary,
                            unselectedTextColor = AppColors.TextSecondary,
                            indicatorColor = Color(0x33212126)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(AppColors.PageBackground)
        ) {
            when (selectedPage) {
                AppPage.Chat -> ChatPage(
                    viewModel = viewModel,
                    onOpenModelPicker = { modelLauncher.launch(arrayOf("*/*")) },
                    onNavigateToSettings = { selectedPage = AppPage.Settings }
                )
                AppPage.Dashboard -> DashboardPage(
                    viewModel = viewModel,
                    onNavigateToChat = { selectedPage = AppPage.Chat },
                    onNavigateToTools = { selectedPage = AppPage.Tools }
                )
                AppPage.Tools -> ToolsPage(
                    viewModel = viewModel,
                    onNavigateToChat = { selectedPage = AppPage.Chat }
                )
                AppPage.Settings -> SettingsPage(
                    viewModel = viewModel,
                    onOpenModelPicker = { modelLauncher.launch(arrayOf("*/*")) }
                )
            }
        }
    }
}

/* =========================================================================================
 * 1. CHAT PAGE
 * ========================================================================================= */

@Composable
private fun ChatPage(
    viewModel: ChatViewModel,
    onOpenModelPicker: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val isStopRequested by viewModel.isStopRequested.collectAsState()
    val generationProgress by viewModel.generationProgress.collectAsState()
    val generationError by viewModel.generationError.collectAsState()
    val cpuThreads by viewModel.cpuThreads.collectAsState()

    var showClearConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
    ) {
        ChatHeader(
            uiState = uiState,
            cpuThreads = cpuThreads,
            onClearChat = { showClearConfirm = true },
            onResetContext = { showResetConfirm = true },
            onOpenSettings = onNavigateToSettings
        )

        HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 1.dp)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (val state = uiState) {
                is ChatUiState.Idle -> {
                    EmptyState(
                        title = "Select a GGUF model to start",
                        subtitle = "Mr-Red runs local LLM models and Android automation tools completely offline.",
                        actionText = "Select Model",
                        onAction = onOpenModelPicker
                    )
                }
                is ChatUiState.Preparing -> {
                    CenterMessage(message = state.message)
                }
                is ChatUiState.Error -> {
                    EmptyState(
                        title = "Model load failed",
                        subtitle = state.message,
                        actionText = "Retry / Select Model",
                        onAction = onOpenModelPicker
                    )
                }
                is ChatUiState.Ready -> {
                    if (messages.isEmpty()) {
                        ChatEmptyGreeting(
                            modelName = state.modelName,
                            nCtx = state.nCtx,
                            onQuickPrompt = { prompt -> viewModel.send(prompt) }
                        )
                    } else {
                        ChatMessagesList(
                            messages = messages,
                            isGenerating = isGenerating,
                            isStopRequested = isStopRequested,
                            generationProgress = generationProgress,
                            errorMessage = generationError,
                            onToggleReasoning = viewModel::toggleReasoning,
                            onToggleAgentTrace = viewModel::toggleAgentTrace
                        )
                    }
                }
            }
        }

        // Quick Action Chips Row
        if (uiState is ChatUiState.Ready && !isGenerating) {
            QuickActionChipsRow(
                onActionSelected = { prompt -> viewModel.send(prompt) }
            )
        }

        ChatComposer(
            enabled = uiState is ChatUiState.Ready,
            isGenerating = isGenerating,
            isStopRequested = isStopRequested,
            onSend = viewModel::send,
            onStop = viewModel::stopGenerating,
            onOpenModelPicker = onOpenModelPicker
        )
    }

    if (showClearConfirm) {
        ConfirmActionDialog(
            title = "Clear chat history?",
            message = "This will delete all messages from this session. The loaded model and context remain in memory.",
            confirmText = "Clear",
            onConfirm = {
                viewModel.clearConversation()
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false }
        )
    }

    if (showResetConfirm) {
        ConfirmActionDialog(
            title = "Reset KV Cache / Context?",
            message = "This clears the native KV cache without deleting chat messages or unloading the model.",
            confirmText = "Reset",
            onConfirm = {
                viewModel.resetContext()
                showResetConfirm = false
            },
            onDismiss = { showResetConfirm = false }
        )
    }
}

@Composable
private fun ChatHeader(
    uiState: ChatUiState,
    cpuThreads: Int,
    onClearChat: () -> Unit,
    onResetContext: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "● Mr-Red",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.PrimaryActionGlow
            )
            val subtitle = when (uiState) {
                ChatUiState.Idle -> "No model loaded"
                is ChatUiState.Preparing -> uiState.message
                is ChatUiState.Ready -> "${uiState.modelName} · n_ctx ${uiState.nCtx} · ${cpuThreads}T"
                is ChatUiState.Error -> "Model error: ${uiState.message}"
            }
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = if (uiState is ChatUiState.Error) AppColors.Error else AppColors.TextSecondary,
                maxLines = 1
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onResetContext, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset Context",
                    tint = AppColors.TextSecondary
                )
            }
            IconButton(onClick = onClearChat, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Clear Chat",
                    tint = AppColors.TextSecondary
                )
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = AppColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun QuickActionChipsRow(onActionSelected: (String) -> Unit) {
    val quickActions = listOf(
        "⚡ Battery & RAM" to "Check my phone battery, RAM and storage vitals",
        "💬 Draft WhatsApp" to "Draft a WhatsApp message to +1234567890 saying Hello from Mr-Red",
        "📞 Open Dialer" to "Open the phone dialer",
        "⏰ Set 5m Timer" to "Set a countdown timer for 5 minutes",
        "⚙️ Battery Settings" to "Open device battery settings",
        "🌐 Web Search" to "Search the web for latest AI news"
    )

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(quickActions) { (label, prompt) ->
            AssistChip(
                onClick = { onActionSelected(prompt) },
                label = { Text(label, fontSize = 12.sp, color = AppColors.TextPrimary) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = AppColors.CardBackground
                ),
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
private fun ChatEmptyGreeting(
    modelName: String,
    nCtx: Int,
    onQuickPrompt: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Mr-Red Offline Assistant",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Model: $modelName · Context: $nCtx tokens\nLiving phone agent ready for offline dialogue and device tools.",
            fontSize = 13.sp,
            color = AppColors.TextSecondary,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        val prompts = listOf(
            "What are my device vitals right now?",
            "Draft a WhatsApp message to Mom: I will be home soon",
            "Set a countdown timer for 3 minutes",
            "Open phone battery settings",
            "Who created Linux?"
        )

        Text(
            text = "Suggested actions:",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextSecondary,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(8.dp))

        prompts.forEach { prompt ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onQuickPrompt(prompt) },
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = prompt,
                    fontSize = 13.sp,
                    color = AppColors.PrimaryAction,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun ChatMessagesList(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    isStopRequested: Boolean,
    generationProgress: String?,
    errorMessage: String?,
    onToggleReasoning: (Int) -> Unit,
    onToggleAgentTrace: (Int) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }

        items(messages.size) { index ->
            ChatMessageItem(
                message = messages[index],
                messageIndex = index,
                onToggleReasoning = { onToggleReasoning(index) },
                onToggleAgentTrace = { onToggleAgentTrace(index) }
            )
        }

        if (isGenerating || isStopRequested || !generationProgress.isNullOrBlank()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusText = if (isStopRequested) "Stopping…" else generationProgress ?: "Generating…"
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0x33E53935)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Error: $errorMessage",
                        fontSize = 12.sp,
                        color = AppColors.Error,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    messageIndex: Int,
    onToggleReasoning: () -> Unit,
    onToggleAgentTrace: () -> Unit
) {
    val isUser = message.role == MessageRole.User

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .background(AppColors.UserBubble, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message.content,
                    fontSize = 14.sp,
                    color = AppColors.TextPrimary
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.PageBackground, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                // Tool Execution Trace Section
                if (message.agentTrace.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .background(Color(0xFF21262D), RoundedCornerShape(8.dp))
                            .border(1.dp, AppColors.CardBorder, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleAgentTrace() },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val durationText = message.agentDurationMillis?.let { " · ${formatDuration(it)}" } ?: ""
                            Text(
                                text = "Tool Execution$durationText",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.TextSecondary
                            )
                            Icon(
                                imageVector = if (message.isAgentTraceExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle trace",
                                tint = AppColors.TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        if (message.isAgentTraceExpanded) {
                            Spacer(modifier = Modifier.height(6.dp))
                            message.agentTrace.forEach { trace ->
                                Text(
                                    text = "• ${trace.text}",
                                    fontSize = 11.sp,
                                    color = AppColors.TextSecondary,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // Reasoning Process Section
                if (message.reasoningContent.isNotBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .background(AppColors.CardBackground, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleReasoning() },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val durationText = message.reasoningDurationMillis?.let { " · ${formatDuration(it)}" } ?: ""
                            Text(
                                text = "Thinking Process$durationText",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.TextSecondary
                            )
                            Icon(
                                imageVector = if (message.isReasoningExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle reasoning",
                                tint = AppColors.TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        if (message.isReasoningExpanded) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = message.reasoningContent,
                                fontSize = 12.sp,
                                color = AppColors.TextTertiary,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // Main Answer Content
                if (message.content.isNotBlank()) {
                    MarkdownText(
                        markdown = message.content,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Source Links
                if (message.sourceLinks.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SourceLinks(sources = message.sourceLinks)
                }
            }
        }
    }
}

@Composable
private fun ChatComposer(
    enabled: Boolean,
    isGenerating: Boolean,
    isStopRequested: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onOpenModelPicker: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    Surface(
        color = AppColors.PageBackground,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color.White, RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                if (text.isEmpty()) {
                    val placeholder = if (enabled) "Type a message or command…" else "Select a model to begin…"
                    Text(
                        text = placeholder,
                        fontSize = 14.sp,
                        color = AppColors.TextHint
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(fontSize = 14.sp, color = AppColors.TextPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isGenerating) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(40.dp)
                        .background(AppColors.StopAction, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = Color.White
                    )
                }
            } else if (enabled) {
                IconButton(
                    onClick = {
                        val trimmed = text.trim()
                        if (trimmed.isNotEmpty()) {
                            onSend(trimmed)
                            text = ""
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (text.isNotBlank()) AppColors.PrimaryAction else AppColors.DisabledAction,
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Send",
                        tint = Color.White
                    )
                }
            } else {
                IconButton(
                    onClick = onOpenModelPicker,
                    modifier = Modifier
                        .size(40.dp)
                        .background(AppColors.PrimaryAction, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = "Load Model",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/* =========================================================================================
 * 2. DASHBOARD PAGE
 * ========================================================================================= */

@Composable
private fun DashboardPage(
    viewModel: ChatViewModel,
    onNavigateToChat: () -> Unit,
    onNavigateToTools: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val cpuThreads by viewModel.cpuThreads.collectAsState()
    val generationMetrics by viewModel.generationMetrics.collectAsState()
    val memorySnapshots by viewModel.memorySnapshots.collectAsState()
    val conversationSummary by viewModel.conversationSummary.collectAsState()

    var batteryPct by remember { mutableIntStateOf(-1) }
    var freeStorageGb by remember { mutableStateOf("...") }
    var totalStorageGb by remember { mutableStateOf("...") }
    var availRamMb by remember { mutableStateOf("...") }
    var totalRamMb by remember { mutableStateOf("...") }

    fun refreshVitals() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            freeStorageGb = String.format(Locale.US, "%.2f GB", freeBytes / (1024.0 * 1024.0 * 1024.0))
            totalStorageGb = String.format(Locale.US, "%.2f GB", totalBytes / (1024.0 * 1024.0 * 1024.0))
        }

        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            availRamMb = "${memInfo.availMem / (1024 * 1024)} MB"
            totalRamMb = "${memInfo.totalMem / (1024 * 1024)} MB"
        }
    }

    LaunchedEffect(Unit) {
        refreshVitals()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Living Phone Persona Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.PrimaryAction),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Mr-Red Mobile Agent",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Living Phone Persona · 32-bit ARMv7-a NEON · Android 11",
                    fontSize = 13.sp,
                    color = AppColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                val statusMessage = if (batteryPct > 0) {
                    "Battery is at $batteryPct%. Free storage: $freeStorageGb. All device tools are operational."
                } else {
                    "Device vitals initialized. Ready to run local inference and phone commands."
                }
                Text(
                    text = statusMessage,
                    fontSize = 13.sp,
                    color = Color.White,
                    lineHeight = 18.sp
                )
            }
        }

        // Live Device Vitals
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Device Vitals",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary
                    )
                    IconButton(onClick = { refreshVitals() }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = AppColors.PrimaryAction
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = Color(0xFFEEEEEE))

                DashboardMetricRow("Battery Level", if (batteryPct > 0) "$batteryPct%" else "Unavailable")
                DashboardMetricRow("Internal Storage", "$freeStorageGb free / $totalStorageGb total")
                DashboardMetricRow("Device RAM", "$availRamMb avail / $totalRamMb total")
                DashboardMetricRow("OS & Architecture", "Android 11 (API 30) · armeabi-v7a")
            }
        }

        // Local Model Runtime Vitals
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Model Inference Status",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = Color(0xFFEEEEEE))

                val modelState = when (val s = uiState) {
                    is ChatUiState.Ready -> s.modelName
                    is ChatUiState.Preparing -> s.message
                    is ChatUiState.Error -> "Error: ${s.message}"
                    ChatUiState.Idle -> "No model loaded"
                }
                DashboardMetricRow("Loaded Model", modelState)

                val nCtxText = (uiState as? ChatUiState.Ready)?.nCtx?.let { "$it tokens" } ?: "Not allocated"
                DashboardMetricRow("Context Window", nCtxText)
                DashboardMetricRow("CPU Threads", "$cpuThreads threads")

                val speedText = generationMetrics?.decodeTokensPerSecond?.let { formatTokensPerSecond(it) } ?: "No runs yet"
                DashboardMetricRow("Generation Speed", speedText)

                val prefillDuration = generationMetrics?.prefillMillis?.let { "$it ms" } ?: "No runs yet"
                DashboardMetricRow("Prefill Latency", prefillDuration)

                val nativeAlloc = memorySnapshots.current?.nativeAllocatedBytes?.let(::formatBytes) ?: "None"
                DashboardMetricRow("Native Memory", nativeAlloc)

                val cacheReuseText = generationMetrics?.reusedPromptTokenCount?.let {
                    if (it > 0) "$it tokens reused from KV cache" else "0 tokens (fresh prefill)"
                } ?: "No runs yet"
                DashboardMetricRow("KV Cache Reuse", cacheReuseText)

                val memoryText = if (conversationSummary.isNotBlank()) {
                    "Active (${conversationSummary.lines().size} notes in SQLite)"
                } else {
                    "SQLite Ready (persisting chat)"
                }
                DashboardMetricRow("Persistent Memory", memoryText)
            }
        }

        // Quick Controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Quick Phone Actions",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.toolExecutor.openSettings(JSONObject().put("page", "battery")) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33212126)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Battery", fontSize = 12.sp, color = AppColors.PrimaryAction)
                    }
                    Button(
                        onClick = { viewModel.toolExecutor.openSettings(JSONObject().put("page", "wifi")) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33212126)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Wi-Fi", fontSize = 12.sp, color = AppColors.PrimaryAction)
                    }
                    Button(
                        onClick = { viewModel.toolExecutor.openApp(JSONObject().put("app_name", "whatsapp")) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33212126)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("WhatsApp", fontSize = 12.sp, color = AppColors.PrimaryAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 13.sp, color = AppColors.TextSecondary)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
    }
}

/* =========================================================================================
 * 3. TOOLS & SKILLS PAGE
 * ========================================================================================= */

@Composable
private fun ToolsPage(
    viewModel: ChatViewModel,
    onNavigateToChat: () -> Unit
) {
    var toolMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    // WhatsApp / SMS Form State
    var messagePlatform by remember { mutableStateOf("whatsapp") }
    var messageRecipient by remember { mutableStateOf("") }
    var messageContent by remember { mutableStateOf("") }

    // Dialer State
    var dialerNumber by remember { mutableStateOf("") }

    // Timer State
    var timerSeconds by remember { mutableStateOf("300") }

    // Web Search State
    var searchQuery by remember { mutableStateOf("") }

    // Weather State
    var weatherCity by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Tools & Skills",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextPrimary
        )

        // Feedback Banner
        if (toolMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isError) Color(0x33E53935) else Color(0x3300C46A)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = toolMessage!!,
                        fontSize = 13.sp,
                        color = if (isError) AppColors.Error else AppColors.Success,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { toolMessage = null }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // 1. WhatsApp & SMS Messenger Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Message Composer (WhatsApp / SMS)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = messagePlatform == "whatsapp",
                        onClick = { messagePlatform = "whatsapp" }
                    )
                    Text("WhatsApp", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(16.dp))
                    RadioButton(
                        selected = messagePlatform == "sms",
                        onClick = { messagePlatform = "sms" }
                    )
                    Text("SMS", fontSize = 14.sp)
                }

                OutlinedTextField(
                    value = messageRecipient,
                    onValueChange = { messageRecipient = it },
                    label = { Text("Phone Number or Contact") },
                    placeholder = { Text("e.g. +1234567890") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = messageContent,
                    onValueChange = { messageContent = it },
                    label = { Text("Message Text") },
                    placeholder = { Text("Write your message here…") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        val result = viewModel.toolExecutor.sendMessage(
                            JSONObject().apply {
                                put("platform", messagePlatform)
                                put("recipient", messageRecipient.trim())
                                put("message", messageContent.trim())
                            }
                        )
                        when (result) {
                            is ToolResult.Success -> {
                                toolMessage = result.content
                                isError = false
                            }
                            is ToolResult.Failure -> {
                                toolMessage = result.message
                                isError = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAction)
                ) {
                    Text("Draft Message in ${if (messagePlatform == "whatsapp") "WhatsApp" else "SMS"}")
                }
            }
        }

        // 2. Phone Dialer Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Phone Dialer",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = dialerNumber,
                    onValueChange = { dialerNumber = it },
                    label = { Text("Phone Number to Dial") },
                    placeholder = { Text("e.g. 1234567890") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        val result = viewModel.toolExecutor.makeCall(
                            JSONObject().put("phone_number", dialerNumber.trim())
                        )
                        when (result) {
                            is ToolResult.Success -> {
                                toolMessage = result.content
                                isError = false
                            }
                            is ToolResult.Failure -> {
                                toolMessage = result.message
                                isError = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAction)
                ) {
                    Text("Open Phone Dialer")
                }
            }
        }

        // 3. Countdown Timer Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Alarms & Timers",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = timerSeconds,
                    onValueChange = { timerSeconds = it },
                    label = { Text("Duration in Seconds") },
                    placeholder = { Text("300") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        val secs = timerSeconds.toIntOrNull() ?: 60
                        val result = viewModel.toolExecutor.setTimer(
                            JSONObject().put("seconds", secs)
                        )
                        when (result) {
                            is ToolResult.Success -> {
                                toolMessage = result.content
                                isError = false
                            }
                            is ToolResult.Failure -> {
                                toolMessage = result.message
                                isError = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAction)
                ) {
                    Text("Set Countdown Timer")
                }
            }
        }

        // 4. Quick App Launcher Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Launch Installed App",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))

                val appList = listOf(
                    "WhatsApp" to "whatsapp",
                    "YouTube" to "youtube",
                    "Chrome" to "chrome",
                    "Camera" to "camera",
                    "Maps" to "maps",
                    "Settings" to "settings"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    appList.take(3).forEach { (label, name) ->
                        OutlinedButton(
                            onClick = {
                                val res = viewModel.toolExecutor.openApp(JSONObject().put("app_name", name))
                                if (res is ToolResult.Failure) {
                                    toolMessage = res.message
                                    isError = true
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    appList.drop(3).forEach { (label, name) ->
                        OutlinedButton(
                            onClick = {
                                val res = viewModel.toolExecutor.openApp(JSONObject().put("app_name", name))
                                if (res is ToolResult.Failure) {
                                    toolMessage = res.message
                                    isError = true
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        }

        // 5. Web Search Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Web Search Tool",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search Query") },
                    placeholder = { Text("e.g. latest news or Wikipedia topic") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        val trimmed = searchQuery.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.send("Search the web for: $trimmed")
                            onNavigateToChat()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAction)
                ) {
                    Text("Run Web Search in Chat")
                }
            }
        }

        // 6. Device Vitals Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Device Vitals & Status",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Reads battery capacity, available storage, and free RAM.",
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val result = viewModel.toolExecutor.getDeviceVitals()
                        when (result) {
                            is ToolResult.Success -> {
                                toolMessage = result.content
                                isError = false
                            }
                            is ToolResult.Failure -> {
                                toolMessage = result.message
                                isError = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAction)
                ) {
                    Text("Check Battery, Storage & RAM")
                }
            }
        }

        // 7. Weather Lookup Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Weather & Forecast",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Live temperature and 3-day forecast via Open-Meteo.",
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = weatherCity,
                    onValueChange = { weatherCity = it },
                    label = { Text("City Name") },
                    placeholder = { Text("e.g. Miami, London, Tokyo") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        val trimmed = weatherCity.trim()
                        if (trimmed.isNotEmpty()) {
                            coroutineScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    viewModel.toolExecutor.getWeather(JSONObject().put("city", trimmed)) {}
                                }
                                when (result) {
                                    is ToolResult.Success -> {
                                        toolMessage = result.content
                                        isError = false
                                    }
                                    is ToolResult.Failure -> {
                                        toolMessage = result.message
                                        isError = true
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAction)
                ) {
                    Text("Lookup Live Weather")
                }
            }
        }
    }
}

/* =========================================================================================
 * 4. SETTINGS & MODEL PAGE
 * ========================================================================================= */

@Composable
private fun SettingsPage(
    viewModel: ChatViewModel,
    onOpenModelPicker: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val systemPrompt by viewModel.systemPrompt.collectAsState()
    val finalPrompt by viewModel.finalPrompt.collectAsState()
    val promptTokenCount by viewModel.promptTokenCount.collectAsState()
    val trimmedHistoryTurnCount by viewModel.trimmedHistoryTurnCount.collectAsState()
    val requestedNctx by viewModel.requestedNctx.collectAsState()
    val cpuThreads by viewModel.cpuThreads.collectAsState()
    val inferenceConfig by viewModel.inferenceConfig.collectAsState()
    val thinkingEnabled by viewModel.thinkingEnabled.collectAsState()
    val generationMetrics by viewModel.generationMetrics.collectAsState()
    val modelPreparationMetrics by viewModel.modelPreparationMetrics.collectAsState()
    val memorySnapshots by viewModel.memorySnapshots.collectAsState()

    var showSystemPromptDialog by remember { mutableStateOf(false) }
    var showFinalPromptDialog by remember { mutableStateOf(false) }
    var showContextWindowDialog by remember { mutableStateOf(false) }
    var showCpuThreadsDialog by remember { mutableStateOf(false) }
    var showSamplingDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showReleaseConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings & Model Management",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextPrimary
        )

        // Model Management Section
        SettingsSection(title = "Local GGUF Model") {
            val modelName = (uiState as? ChatUiState.Ready)?.modelName ?: "No model loaded"
            SettingsRow(
                title = "Loaded Model",
                summary = modelName,
                onClick = onOpenModelPicker
            )
            SettingsRow(
                title = "Import New GGUF Model",
                summary = "Pick from device storage",
                onClick = onOpenModelPicker
            )
            if (uiState is ChatUiState.Ready) {
                SettingsRow(
                    title = "Unload Model",
                    summary = "Release native memory",
                    onClick = { showReleaseConfirm = true }
                )
            }
        }

        // Conversation Configuration
        SettingsSection(title = "Conversation & Persona") {
            SettingsRow(
                title = "System Prompt",
                summary = if (systemPrompt.isBlank()) "Default (Offline Agent)" else "Custom",
                onClick = { showSystemPromptDialog = true }
            )
            SettingsRow(
                title = "Final Formatted Prompt",
                summary = if (finalPrompt.isBlank()) "No conversation yet" else "View formatted prompt",
                onClick = { showFinalPromptDialog = true }
            )
            SettingsRow(
                title = "Clear Chat History",
                summary = "Retain loaded model",
                onClick = { showClearConfirm = true }
            )
        }

        // Inference Settings
        SettingsSection(title = "Inference & Runtime") {
            SettingsRow(
                title = "Context Window (n_ctx)",
                summary = "$requestedNctx tokens",
                onClick = { showContextWindowDialog = true }
            )
            SettingsRow(
                title = "CPU Threads",
                summary = "$cpuThreads threads",
                onClick = { showCpuThreadsDialog = true }
            )
            SettingsSwitchRow(
                title = "Qwen3 Thinking Mode",
                subtitle = "Generate reasoning before final response",
                checked = thinkingEnabled,
                onCheckedChange = viewModel::updateThinkingEnabled
            )
            SettingsRow(
                title = "Sampling Parameters",
                summary = if (inferenceConfig.temperature == 0f) "Stable (Greedy) · ${inferenceConfig.maxTokens} tokens" else "T=${inferenceConfig.temperature} · ${inferenceConfig.maxTokens} tokens",
                onClick = { showSamplingDialog = true }
            )
            SettingsRow(
                title = "Prompt Token Usage",
                summary = if (promptTokenCount > 0) "$promptTokenCount tokens" else "None"
            )
            SettingsRow(
                title = "History Trimming",
                summary = if (trimmedHistoryTurnCount > 0) "$trimmedHistoryTurnCount turns omitted" else "Full history retained"
            )
            SettingsRow(
                title = "Reset KV Cache / Context",
                summary = "Clear cached tokens",
                onClick = { showResetConfirm = true }
            )
        }

        // Performance Metrics
        SettingsSection(title = "Diagnostics & Speed") {
            SettingsRow(
                title = "Model Load Duration",
                summary = modelPreparationMetrics?.modelLoadMillis?.let { "$it ms" } ?: "None"
            )
            SettingsRow(
                title = "Context Allocation",
                summary = modelPreparationMetrics?.contextCreateMillis?.let { "$it ms" } ?: "None"
            )
            SettingsRow(
                title = "Generation Speed",
                summary = generationMetrics?.decodeTokensPerSecond?.let { formatTokensPerSecond(it) } ?: "None"
            )
            SettingsRow(
                title = "Prefill Duration",
                summary = generationMetrics?.prefillMillis?.let { "$it ms" } ?: "None"
            )
            SettingsRow(
                title = "First Token Latency",
                summary = generationMetrics?.firstTokenMillis?.let { "$it ms" } ?: "None"
            )
            SettingsRow(
                title = "Total Generation Time",
                summary = generationMetrics?.totalMillis?.let { "$it ms" } ?: "None"
            )
            SettingsRow(
                title = "Native Allocated Memory",
                summary = memorySnapshots.current?.nativeAllocatedBytes?.let(::formatBytes) ?: "None"
            )
            SettingsRow(
                title = "Native PSS Memory",
                summary = memorySnapshots.current?.nativePssKb?.let(::formatKb) ?: "None"
            )
        }
    }

    // System Prompt Dialog
    if (showSystemPromptDialog) {
        var draftPrompt by remember { mutableStateOf(systemPrompt) }
        AlertDialog(
            onDismissRequest = { showSystemPromptDialog = false },
            title = { Text("System Prompt", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = draftPrompt,
                    onValueChange = { draftPrompt = it },
                    placeholder = { Text("e.g. You are Mr-Red, an offline living-phone assistant.") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 6
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateSystemPrompt(draftPrompt)
                    showSystemPromptDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSystemPromptDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Final Prompt Viewer Dialog
    if (showFinalPromptDialog) {
        AlertDialog(
            onDismissRequest = { showFinalPromptDialog = false },
            title = { Text("Final Formatted Prompt", fontSize = 16.sp) },
            text = {
                Text(
                    text = finalPrompt.ifBlank { "No formatted prompt generated yet." },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showFinalPromptDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Context Window Selector Dialog
    if (showContextWindowDialog) {
        val options = listOf(512, 1024, 2048, 4096)
        var selectedNctx by remember { mutableIntStateOf(requestedNctx) }

        AlertDialog(
            onDismissRequest = { showContextWindowDialog = false },
            title = { Text("Context Window (n_ctx)", fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        text = "Larger context allows longer conversations and tool output, but requires more RAM. 1024 or 2048 is recommended for 32-bit ARMv7.",
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    options.forEach { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedNctx = opt }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedNctx == opt,
                                onClick = { selectedNctx = opt }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("$opt tokens", fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateContextWindow(selectedNctx)
                    showContextWindowDialog = false
                }) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { showContextWindowDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // CPU Threads Selector Dialog
    if (showCpuThreadsDialog) {
        val threadOptions = (1..Runtime.getRuntime().availableProcessors().coerceAtLeast(4)).toList()
        var selectedThreads by remember { mutableIntStateOf(cpuThreads) }

        AlertDialog(
            onDismissRequest = { showCpuThreadsDialog = false },
            title = { Text("CPU Threads", fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        text = "Configures CPU threads for prefill and token generation. 4 threads is recommended for 8-core ARMv7 devices.",
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    threadOptions.forEach { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedThreads = opt }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedThreads == opt,
                                onClick = { selectedThreads = opt }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("$opt threads", fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateCpuThreads(selectedThreads)
                    showCpuThreadsDialog = false
                }) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCpuThreadsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Sampling Parameters Dialog
    if (showSamplingDialog) {
        var samplingDraft by remember { mutableStateOf(inferenceConfig) }

        AlertDialog(
            onDismissRequest = { showSamplingDialog = false },
            title = { Text("Sampling Parameters", fontSize = 16.sp) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "When Temperature is 0, greedy mode is used. Presets offer stable or creative generation.",
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Presets", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val currentNctx = (uiState as? ChatUiState.Ready)?.nCtx ?: requestedNctx
                        SamplingPreset.values().forEach { preset ->
                            OutlinedButton(
                                onClick = { samplingDraft = preset.configFor(currentNctx) },
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(preset.label, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    SamplingSlider(
                        name = "Temperature",
                        presets = listOf("0 (Greedy)" to 0f, "0.2" to 0.2f, "0.7" to 0.7f, "1.0" to 1.0f),
                        current = samplingDraft.temperature
                    ) { samplingDraft = samplingDraft.copy(temperature = it) }

                    SamplingSlider(
                        name = "Top-K",
                        presets = listOf("20" to 20, "40" to 40, "80" to 80, "None" to 0),
                        current = samplingDraft.topK
                    ) { samplingDraft = samplingDraft.copy(topK = it) }

                    SamplingSlider(
                        name = "Seed",
                        presets = listOf("42" to 42, "2026" to 2026, "Random" to -1),
                        current = samplingDraft.seed
                    ) { samplingDraft = samplingDraft.copy(seed = it) }

                    SamplingSlider(
                        name = "Max Output Tokens",
                        presets = listOf("128" to 128, "256" to 256, "512" to 512, "1024" to 1024),
                        current = samplingDraft.maxTokens
                    ) { samplingDraft = samplingDraft.copy(maxTokens = it) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateInferenceConfig(samplingDraft)
                    showSamplingDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSamplingDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showClearConfirm) {
        ConfirmActionDialog(
            title = "Clear chat history?",
            message = "This clears all chat messages from memory. The loaded model and context remain available.",
            confirmText = "Clear",
            onConfirm = {
                viewModel.clearConversation()
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false }
        )
    }

    if (showResetConfirm) {
        ConfirmActionDialog(
            title = "Reset KV Cache / Context?",
            message = "This clears the native KV cache without removing chat messages or unloading the model.",
            confirmText = "Reset",
            onConfirm = {
                viewModel.resetContext()
                showResetConfirm = false
            },
            onDismiss = { showResetConfirm = false }
        )
    }

    if (showReleaseConfirm) {
        ConfirmActionDialog(
            title = "Unload local model?",
            message = "This frees the native context, model weights, and runtime memory.",
            confirmText = "Unload",
            onConfirm = {
                viewModel.releaseModel()
                showReleaseConfirm = false
            },
            onDismiss = { showReleaseConfirm = false }
        )
    }
}

/* =========================================================================================
 * 5. SHARED UI HELPERS & MARKDOWN
 * ========================================================================================= */

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    summary: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            color = AppColors.TextPrimary
        )
        Text(
            text = summary,
            fontSize = 13.sp,
            color = if (onClick != null) AppColors.PrimaryAction else AppColors.TextSecondary
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.sp, color = AppColors.TextPrimary)
            Text(text = subtitle, fontSize = 11.sp, color = AppColors.TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun <T> SamplingSlider(
    name: String,
    presets: List<Pair<String, T>>,
    current: T,
    onSelect: (T) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text("$current", fontSize = 12.sp, color = AppColors.TextSecondary)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            presets.forEach { (label, value) ->
                OutlinedButton(
                    onClick = { onSelect(value) },
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)
                ) {
                    Text(label, fontSize = 9.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = { Text(message, fontSize = 13.sp, color = AppColors.TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = AppColors.Error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SourceLinks(sources: List<SourceLink>) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Sources",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        sources.forEach { source ->
            Text(
                text = "• ${source.title}",
                fontSize = 11.sp,
                color = AppColors.PrimaryAction,
                modifier = Modifier
                    .clickable {
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(source.url))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    }
                    .padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        markdown.lines().forEach { line ->
            when {
                line.startsWith("# ") -> Text(line.removePrefix("# "), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                line.startsWith("## ") -> Text(line.removePrefix("## "), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                line.startsWith("### ") -> Text(line.removePrefix("### "), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                line.startsWith("- ") || line.startsWith("* ") -> {
                    Row {
                        Text("• ", fontSize = 14.sp)
                        Text(line.substring(2), fontSize = 14.sp, color = AppColors.TextPrimary)
                    }
                }
                line.isBlank() -> Spacer(modifier = Modifier.height(4.dp))
                else -> Text(line, fontSize = 14.sp, color = AppColors.TextPrimary, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    subtitle: String,
    actionText: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        Text(subtitle, fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAction)
        ) {
            Text(actionText)
        }
    }
}

@Composable
private fun CenterMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, fontSize = 14.sp, color = AppColors.TextSecondary)
    }
}

private fun formatDuration(durationMillis: Long): String = when {
    durationMillis < 1000 -> "${durationMillis}ms"
    else -> String.format(Locale.US, "%.1fs", durationMillis / 1000.0)
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun formatKb(kb: Int): String = formatBytes(kb.toLong() * 1024L)

private fun formatTokensPerSecond(value: Double): String = String.format(Locale.US, "%.1f t/s", value)
