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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
import androidx.compose.material3.SliderDefaults
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
import kotlinx.coroutines.launch
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
                containerColor = AppColors.Surface,
                contentColor = AppColors.PrimaryActionGlow
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
                            indicatorColor = Color(0x33E53935)
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

        HorizontalDivider(color = AppColors.CardBorder, thickness = 1.dp)

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
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Color(0xFF211518), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0x60E53935), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "MR",
                    color = AppColors.PrimaryActionGlow,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Column {
                Text(
                    text = "Mr-Red",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
                val subtitle = when (uiState) {
                    ChatUiState.Idle -> "⚪ Offline Living Phone · Select model"
                    is ChatUiState.Preparing -> "⏳ ${uiState.message}"
                    is ChatUiState.Ready -> "🟢 ${uiState.modelName} · n_ctx ${uiState.nCtx} · ${cpuThreads}T"
                    is ChatUiState.Error -> "🔴 ${uiState.message}"
                }
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = if (uiState is ChatUiState.Error) AppColors.Error else AppColors.TextSecondary,
                    maxLines = 1
                )
            }
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
        "🔋 Battery" to "Check my battery",
        "💾 Storage" to "Check my storage",
        "🧠 RAM" to "Check my RAM",
        "🕒 Clock" to "What time is it?",
        "📱 Overview" to "Check device status",
        "⏰ Set Alarm" to "Set an alarm for 7:00 AM",
        "⏳ Set Timer" to "Set a countdown timer for 5 minutes",
        "💬 WhatsApp" to "Draft a WhatsApp message to +1234567890 saying Hello from Mr-Red",
        "📞 Open Dialer" to "Open the phone dialer",
        "⚙️ Battery Settings" to "Open device battery settings",
        "🌐 Web Search" to "Search the web for latest AI news"
    )

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(quickActions) { (label, prompt) ->
            AssistChip(
                onClick = { onActionSelected(prompt) },
                label = { Text(label, fontSize = 12.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.Medium) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = AppColors.CardBackground,
                    labelColor = AppColors.TextPrimary
                ),
                border = BorderStroke(1.dp, AppColors.CardBorder),
                shape = RoundedCornerShape(14.dp)
            )
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
            border = BorderStroke(1.dp, AppColors.Error),
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
                    .border(1.dp, AppColors.CardBorder, RoundedCornerShape(16.dp))
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
                    .background(AppColors.AssistantBubble, RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0x50E53935), RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(Color(0xFF211518), RoundedCornerShape(4.dp))
                            .border(1.dp, Color(0x60E53935), RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("MR", color = AppColors.PrimaryActionGlow, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                    }
                    Text(
                        text = "Mr-Red",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.PrimaryActionGlow
                    )
                }

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
                            .background(Color(0xFF1C2128), RoundedCornerShape(8.dp))
                            .border(1.dp, AppColors.CardBorder, RoundedCornerShape(8.dp))
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
        color = AppColors.Surface,
        shadowElevation = 4.dp
    ) {
        Column {
            HorizontalDivider(color = AppColors.CardBorder, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(AppColors.CardBackground, RoundedCornerShape(24.dp))
                        .border(1.dp, AppColors.CardBorder, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    if (text.isEmpty()) {
                        val placeholder = if (enabled) "Message Mr-Red or run phone tool…" else "Select a GGUF model to begin…"
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
                            .size(42.dp)
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
                            .size(42.dp)
                            .background(AppColors.PrimaryAction, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            tint = Color.White
                        )
                    }
                } else {
                    IconButton(
                        onClick = onOpenModelPicker,
                        modifier = Modifier
                            .size(42.dp)
                            .background(AppColors.PrimaryAction, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Load Model",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        markdown.lines().forEach { line ->
            when {
                line.startsWith("# ") -> Text(line.removePrefix("# "), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                line.startsWith("## ") -> Text(line.removePrefix("## "), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                line.startsWith("### ") -> Text(line.removePrefix("### "), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
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
