package com.arick.androidlocalllmlab

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import android.widget.Toast
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
import android.content.ComponentName
import android.provider.Settings as AndroidSettings
import android.app.AlarmManager
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import java.text.SimpleDateFormat
import java.util.Date

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val enabledListeners = AndroidSettings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    val myComponent = ComponentName(context, MrRedNotificationListenerService::class.java).flattenToString()
    return enabledListeners?.contains(myComponent) == true || enabledListeners?.contains(context.packageName) == true
}

private fun canScheduleExactAlarms(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarmManager?.canScheduleExactAlarms() ?: true
    } else {
        true
    }
}

private fun isPostNotificationsGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

class MainActivity : ComponentActivity() {
    private val chatViewModel by viewModels<ChatViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityLogger.initialize(applicationContext)
        installCrashDiagnostics()
        ActivityLogger.record(applicationContext, "APP", "activity_created", "saved_state=${savedInstanceState != null} task_id=$taskId")
        ScheduledTasksStore.init(this)
        enableEdgeToEdge()
        setContent {
            AndroidLocalLlmLabTheme {
                MainAppScreen(viewModel = chatViewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ActivityLogger.record(applicationContext, "APP", "activity_started", "MainActivity visible")
    }

    override fun onResume() {
        super.onResume()
        ActivityLogger.record(applicationContext, "APP", "activity_resumed", "MainActivity interactive")
    }

    override fun onPause() {
        ActivityLogger.record(applicationContext, "APP", "activity_paused", "MainActivity losing foreground")
        super.onPause()
    }

    override fun onStop() {
        ActivityLogger.record(applicationContext, "APP", "activity_stopped", "MainActivity no longer visible")
        super.onStop()
    }

    override fun onDestroy() {
        ActivityLogger.record(applicationContext, "APP", "activity_destroyed", "changing_configurations=$isChangingConfigurations")
        super.onDestroy()
    }

    private fun installCrashDiagnostics() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            ActivityLogger.record(
                applicationContext, "FATAL", "uncaught_exception",
                "thread=${thread.name} type=${error.javaClass.name} message=${error.message}\n" +
                    error.stackTraceToString().take(12000)
            )
            previous?.uncaughtException(thread, error)
        }
    }
}

enum class AppPage(val title: String, val icon: ImageVector) {
    Chat("Chat", Icons.Default.Chat),
    Notifications("Alerts", Icons.Default.Notifications),
    Tasks("Tasks", Icons.Default.Alarm),
    Dashboard("Dashboard", Icons.Default.Dashboard),
    Settings("Settings", Icons.Default.Settings)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainAppScreen(viewModel: ChatViewModel) {
    val activityContext = LocalContext.current
    val activityScope = rememberCoroutineScope()
    var selectedPage by remember { mutableStateOf(AppPage.Chat) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        ActivityLogger.record(activityContext, "PERMISSION", "notification_permission_result", "granted=$granted api=${Build.VERSION.SDK_INT}")
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(activityContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    val modelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        ActivityLogger.record(activityContext, "UI", "model_picker_result", if (uri == null) "cancelled=true" else "selected=true scheme=${uri.scheme}")
        uri?.let { viewModel.loadModel(it) }
    }

    Scaffold(
        bottomBar = {
            if (selectedPage != AppPage.Chat || !WindowInsets.isImeVisible) NavigationBar(
                containerColor = AppColors.PageBackground,
                contentColor = AppColors.PrimaryAction
            ) {
                AppPage.values().forEach { page ->
                    NavigationBarItem(
                        selected = selectedPage == page,
                        onClick = {
                            ActivityLogger.record(activityContext, "UI", "navigation", "Opened ${page.title} screen")
                            selectedPage = page
                        },
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
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = if (selectedPage == AppPage.Chat && WindowInsets.isImeVisible) 0.dp else innerPadding.calculateBottomPadding()
                )
                .background(AppColors.PageBackground)
        ) {
            when (selectedPage) {
                AppPage.Chat -> ChatPage(
                    viewModel = viewModel,
                    onOpenModelPicker = { modelLauncher.launch(arrayOf("*/*")) },
                    onNavigateToSettings = { selectedPage = AppPage.Settings }
                )
                AppPage.Notifications -> NotificationsPage(
                    viewModel = viewModel,
                    onNavigateToChat = { prompt ->
                        selectedPage = AppPage.Chat
                        if (!prompt.isNullOrBlank()) {
                            viewModel.send(prompt)
                        }
                    }
                )
                AppPage.Tasks -> ScheduledTasksPage(
                    onNavigateToChat = { selectedPage = AppPage.Chat }
                )
                AppPage.Dashboard -> DashboardPage(
                    viewModel = viewModel,
                    onNavigateToChat = { selectedPage = AppPage.Chat },
                    onOpenModelPicker = { modelLauncher.launch(arrayOf("*/*")) }
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
    val activityContext = LocalContext.current
    val activityScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

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
            onOpenSettings = onNavigateToSettings,
            onCopyConversation = {
                val transcript = messages.filter { message ->
                    message.content.isNotBlank() || message.reasoningContent.isNotBlank() || message.agentTrace.isNotEmpty()
                }.joinToString("\n\n") { message ->
                    buildString {
                        append(if (message.role == MessageRole.User) "You: " else "Mr-Red: ")
                        append(message.content)
                        if (message.reasoningContent.isNotBlank()) append("\n\nThinking: \n").append(message.reasoningContent)
                        if (message.agentTrace.isNotEmpty()) append("\n\nActivity: ").append(message.agentTrace.joinToString("; ") { it.text })
                        if (message.sourceLinks.isNotEmpty()) append("\nSources: ").append(message.sourceLinks.joinToString("; ") { it.url })
                    }
                }
                if (transcript.isBlank()) {
                    Toast.makeText(activityContext, "There is no conversation to copy", Toast.LENGTH_SHORT).show()
                } else {
                    clipboardManager.setText(AnnotatedString(transcript))
                    Toast.makeText(activityContext, "Conversation copied", Toast.LENGTH_SHORT).show()
                }
            },
            onCopyLoggerSummary = {
                activityScope.launch {
                    val summary = withContext(Dispatchers.IO) { ActivityLogger.summary(activityContext) }
                    clipboardManager.setText(AnnotatedString(summary))
                    Toast.makeText(activityContext, "Activity logger summary copied", Toast.LENGTH_SHORT).show()
                }
            }
        )

        HorizontalDivider(color = AppColors.CardBorder, thickness = 1.dp)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (val state = uiState) {
                is ChatUiState.Idle -> {
                    if (messages.isEmpty()) {
                        EmptyState(
                            title = "Select a GGUF model to start",
                            subtitle = "Device status, Android actions, weather, news, and web search work without a model. Load a model for general answers.",
                            actionText = "Select Model",
                            onAction = onOpenModelPicker
                        )
                    } else {
                        ChatMessagesList(
                            messages = messages,
                            isGenerating = isGenerating,
                            isStopRequested = isStopRequested,
                            generationProgress = generationProgress,
                            errorMessage = generationError,
                            onToggleReasoning = viewModel::toggleReasoning,
                            onToggleAgentTrace = viewModel::toggleAgentTrace,
                            onToggleModelInput = viewModel::toggleModelInput
                        )
                    }
                }
                is ChatUiState.Preparing -> {
                    CenterMessage(message = state.message)
                }
                is ChatUiState.Error -> {
                    if (messages.isEmpty()) {
                        EmptyState(
                            title = "Model load failed",
                            subtitle = state.message,
                            actionText = "Retry / Select Model",
                            onAction = onOpenModelPicker
                        )
                    } else {
                        ChatMessagesList(
                            messages = messages,
                            isGenerating = isGenerating,
                            isStopRequested = isStopRequested,
                            generationProgress = generationProgress,
                            errorMessage = generationError,
                            onToggleReasoning = viewModel::toggleReasoning,
                            onToggleAgentTrace = viewModel::toggleAgentTrace,
                            onToggleModelInput = viewModel::toggleModelInput
                        )
                    }
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
                            onToggleAgentTrace = viewModel::toggleAgentTrace,
                            onToggleModelInput = viewModel::toggleModelInput
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
            enabled = uiState !is ChatUiState.Preparing,
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
    onOpenSettings: () -> Unit,
    onCopyConversation: () -> Unit,
    onCopyLoggerSummary: () -> Unit
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
            IconButton(onClick = onCopyConversation, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy whole conversation", tint = AppColors.TextSecondary)
            }
            IconButton(onClick = onCopyLoggerSummary, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Description, contentDescription = "Copy activity logger summary", tint = AppColors.TextSecondary)
            }
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
        "💬 Draft message" to "Draft a message saying Hello from Mr-Red",
        "📞 Open Dialer" to "Open phone dialer",
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
    onToggleAgentTrace: (Int) -> Unit,
    onToggleModelInput: (Int) -> Unit
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
                onToggleAgentTrace = { onToggleAgentTrace(index) },
                onToggleModelInput = { onToggleModelInput(index) }
            )
        }

        if (isGenerating || isStopRequested || !generationProgress.isNullOrBlank()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = AppColors.PrimaryAction
                    )
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
    onToggleAgentTrace: () -> Unit,
    onToggleModelInput: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
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
                // Exact formatted input captured from llama.cpp immediately before inference.
                if (message.modelInputPrompt.isNotBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .background(Color(0xFF17212B), RoundedCornerShape(8.dp))
                            .border(1.dp, AppColors.CardBorder, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Exact model input · ${message.modelInputTokenCount} tokens",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.TextSecondary,
                                modifier = Modifier.weight(1f).clickable { onToggleModelInput() }
                            )
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(message.modelInputPrompt))
                                    Toast.makeText(context, "Model input copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy exact model input", tint = AppColors.TextSecondary)
                            }
                            IconButton(onClick = onToggleModelInput, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = if (message.isModelInputExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Toggle exact model input",
                                    tint = AppColors.TextSecondary
                                )
                            }
                        }
                        if (message.isModelInputExpanded) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = message.modelInputPrompt,
                                fontSize = 10.sp,
                                color = AppColors.TextTertiary,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 13.sp
                            )
                        }
                    }
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

                // Per-answer generation telemetry: full formatted input and actual sampled output.
                if (message.generationMetrics != null) {
                    val metrics = message.generationMetrics
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = Color(0xFF141C26),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Color(0xFF223042))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(text = "⚡", fontSize = 11.sp)
                            Text(
                                text = "Gen ${formatDuration(metrics.totalMillis)}  ·  In: ${metrics.promptTokenCount}t  ·  Out: ${metrics.generatedTokenCount}t  ·  ${formatTokensPerSecond(metrics.decodeTokensPerSecond)}",
                                fontSize = 11.sp,
                                color = AppColors.TextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                } else if (message.agentDurationMillis != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = Color(0xFF141C26),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Color(0xFF223042))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(text = "⚙️", fontSize = 11.sp)
                            Text(
                                text = "Executed in ${formatDuration(message.agentDurationMillis)}  ·  Kotlin Local Engine",
                                fontSize = 11.sp,
                                color = AppColors.TextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
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
                .padding(horizontal = 12.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(AppColors.CardBackground, RoundedCornerShape(24.dp))
                    .border(1.dp, AppColors.CardBorder, RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
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
                    cursorBrush = SolidColor(AppColors.PrimaryActionGlow),
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
 * 2. NOTIFICATIONS HUB PAGE
 * ========================================================================================= */

@Composable
private fun NotificationsPage(
    viewModel: ChatViewModel,
    onNavigateToChat: (String?) -> Unit
) {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var isConnected by remember { mutableStateOf(MrRedNotificationListenerService.isConnected()) }
    var notifications by remember { mutableStateOf(MrRedNotificationListenerService.currentNotifications(context)) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        isEnabled = isNotificationListenerEnabled(context)
        isConnected = MrRedNotificationListenerService.isConnected()
        notifications = MrRedNotificationListenerService.currentNotifications(context)
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Notification Hub",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
                Text(
                    text = "Live status bar alerts captured for on-device reasoning",
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary
                )
            }
            IconButton(onClick = {
                MrRedNotificationListenerService.requestRebindService(context)
                refresh()
                statusMessage = "Refreshed and rebound listener service"
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AppColors.TextSecondary)
            }
        }

        statusMessage?.let { msg ->
            Text(
                text = msg,
                fontSize = 12.sp,
                color = AppColors.PrimaryActionGlow
            )
        }

        // Connection / Permission Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isEnabled && isConnected) Color(0xFF14241B) else Color(0xFF2B1C1C)
            ),
            border = BorderStroke(1.dp, if (isEnabled && isConnected) Color(0xFF254B33) else Color(0xFF5E2B2B))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = if (isEnabled && isConnected) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isEnabled && isConnected) Color(0xFF4CAF50) else Color(0xFFFF6B6B),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = when {
                            !isEnabled -> "Notification Access Not Granted"
                            !isConnected -> "Listener Service Rebinding"
                            else -> "Notification Listener Active"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.TextPrimary
                    )
                }

                Text(
                    text = when {
                        !isEnabled -> "Android requires explicit permission for Mr-Red to read status bar alerts. Enable Mr-Red in Android Settings to let the model see incoming alerts."
                        !isConnected -> "Listener is enabled in Android Settings, but Android has not bound the background service yet. Tap Rebind or toggle access in Settings if needed."
                        else -> "Mr-Red is capturing incoming status bar notifications into private local memory for verified on-device reasoning."
                    },
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary,
                    lineHeight = 16.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isEnabled) {
                        Button(
                            onClick = {
                                runCatching {
                                    val intent = Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAction)
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Grant Access in Settings", fontSize = 12.sp)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                MrRedNotificationListenerService.requestRebindService(context)
                                refresh()
                            }
                        ) {
                            Text("Rebind Service", fontSize = 12.sp, color = AppColors.TextPrimary)
                        }
                    }
                }
            }
        }

        // Quick AI Action
        Button(
            onClick = {
                onNavigateToChat("What notifications do I have right now? Summarize any important alerts.")
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAction)
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Ask Mr-Red to Summarize Alerts in Chat", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        // Active Notifications Section
        Text(
            text = "Active Notifications (${notifications.size})",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
            modifier = Modifier.padding(top = 4.dp)
        )

        if (notifications.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                border = BorderStroke(1.dp, AppColors.CardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = if (!isEnabled) "No notifications accessible" else "No active notifications",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.TextPrimary
                    )
                    Text(
                        text = if (!isEnabled) "Enable Notification Access above to see alerts here." else "You're all caught up! Incoming notifications from your apps will appear here in real time.",
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                        lineHeight = 16.sp
                    )
                }
            }
        } else {
            notifications.forEach { notif ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                    border = BorderStroke(1.dp, AppColors.CardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = AppColors.PrimaryAction.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = notif.app.ifBlank { notif.packageName },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppColors.PrimaryActionGlow,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(notif.postedAtMillis))
                            Text(text = timeStr, fontSize = 11.sp, color = AppColors.TextSecondary)
                        }

                        if (notif.title.isNotBlank()) {
                            Text(
                                text = notif.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.TextPrimary
                            )
                        }

                        if (notif.text.isNotBlank()) {
                            Text(
                                text = notif.text,
                                fontSize = 12.sp,
                                color = AppColors.TextSecondary,
                                lineHeight = 16.sp
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                onClick = {
                                    onNavigateToChat("Tell me about this notification from ${notif.app}: '${notif.title}' - ${notif.text}")
                                }
                            ) {
                                Text("Ask Mr-Red", fontSize = 11.sp, color = AppColors.PrimaryActionGlow)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* =========================================================================================
 * 3. MEMORY & DIAGNOSTICS HUB COMPOSABLE
 * ========================================================================================= */

@Composable
private fun MemoryHubContent(
    context: Context
) {
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var facts by remember { mutableStateOf<List<String>>(emptyList()) }
    var activityLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf("All") }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    fun refreshMemory() {
        scope.launch {
            counts = withContext(Dispatchers.IO) { EventMemoryStore.counts(context) }
            facts = withContext(Dispatchers.IO) { EventMemoryStore.recentFacts(context) }
            val rawLogs = withContext(Dispatchers.IO) { ActivityLogger.recentText(context) }
            activityLogs = rawLogs.lines().filter { it.isNotBlank() }
        }
    }

    LaunchedEffect(Unit) {
        refreshMemory()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Event Memory & Knowledge",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
                Text(
                    text = "Private on-device SQLite database and diagnostic journal",
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary
                )
            }
            IconButton(onClick = {
                refreshMemory()
                feedbackMessage = "Refreshed memory database"
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AppColors.TextSecondary)
            }
        }

        feedbackMessage?.let { msg ->
            Text(text = msg, fontSize = 12.sp, color = AppColors.PrimaryActionGlow)
        }

        // Database Statistics Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                border = BorderStroke(1.dp, AppColors.CardBorder),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = "Total Events", fontSize = 11.sp, color = AppColors.TextSecondary)
                    Text(
                        text = counts["events"]?.toString() ?: "0",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary
                    )
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                border = BorderStroke(1.dp, AppColors.CardBorder),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = "Learned Facts", fontSize = 11.sp, color = AppColors.TextSecondary)
                    Text(
                        text = counts["facts"]?.toString() ?: "0",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.PrimaryActionGlow
                    )
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                border = BorderStroke(1.dp, AppColors.CardBorder),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = "Alerts Stored", fontSize = 11.sp, color = AppColors.TextSecondary)
                    Text(
                        text = counts["notifications"]?.toString() ?: "0",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary
                    )
                }
            }
        }

        // Verified Long-Term Facts Card
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
            border = BorderStroke(1.dp, AppColors.CardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Verified Long-Term Facts (${facts.size})",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.TextPrimary
                    )
                    Icon(Icons.Default.Storage, contentDescription = null, tint = AppColors.PrimaryActionGlow, modifier = Modifier.size(16.dp))
                }

                if (facts.isEmpty()) {
                    Text(
                        text = "No explicit facts stored yet. Say 'Remember that I prefer dark mode' or 'My name is Richard' in Chat to record facts.",
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                        lineHeight = 16.sp
                    )
                } else {
                    facts.forEach { fact ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(text = "•", fontSize = 12.sp, color = AppColors.PrimaryActionGlow)
                            Text(
                                text = fact,
                                fontSize = 12.sp,
                                color = AppColors.TextPrimary,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }

        // Activity Logger Diagnostics Card
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
            border = BorderStroke(1.dp, AppColors.CardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Activity Logger · Diagnostic Journal",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary
                )

                // Filter chips
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val filters = listOf("All", "INFERENCE", "NOTIFICATION", "DEVICE", "ERROR")
                    items(filters) { f ->
                        AssistChip(
                            onClick = { selectedFilter = f },
                            label = { Text(f, fontSize = 11.sp) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (selectedFilter == f) AppColors.PrimaryAction.copy(alpha = 0.3f) else Color.Transparent,
                                labelColor = if (selectedFilter == f) AppColors.PrimaryActionGlow else AppColors.TextSecondary
                            )
                        )
                    }
                }

                val filteredLogs = if (selectedFilter == "All") {
                    activityLogs
                } else {
                    activityLogs.filter { it.contains(selectedFilter, ignoreCase = true) }
                }

                Surface(
                    color = Color(0xFF10141D),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E2633)),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                ) {
                    if (filteredLogs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text(text = "No log entries matching filter", fontSize = 11.sp, color = AppColors.TextSecondary)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(filteredLogs.takeLast(50).reversed()) { line ->
                                Text(
                                    text = line,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (line.contains("ERROR", ignoreCase = true)) Color(0xFFFF6B6B) else AppColors.TextSecondary,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val report = withContext(Dispatchers.IO) { ActivityLogger.diagnosticReport(context) }
                                clipboard.setText(AnnotatedString(report))
                                Toast.makeText(context, "Full diagnostic report copied", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy Report", fontSize = 11.sp)
                    }

                    Button(
                        onClick = { showClearConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A1E1E)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFFF6B6B))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear DB", fontSize = 11.sp, color = Color(0xFFFF6B6B))
                    }
                }
            }
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear Event Database?", fontSize = 16.sp) },
            text = {
                Text(
                    text = "This deletes all recorded event history and notification rows from SQLite. Learned long-term facts will be kept.",
                    fontSize = 13.sp,
                    color = AppColors.TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { EventMemoryStore.clearEventHistory(context) }
                            refreshMemory()
                            showClearConfirmDialog = false
                            Toast.makeText(context, "Event database cleared", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBA1A1A))
                ) {
                    Text("Clear", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            }
        )
    }
}

/* =========================================================================================
 * 4. DASHBOARD PAGE (WITH TABS FOR VITALS, MEMORY & SAMPLER)
 * ========================================================================================= */

@Composable
private fun DashboardPage(
    viewModel: ChatViewModel,
    onNavigateToChat: () -> Unit,
    onOpenModelPicker: () -> Unit = {}
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Vitals & Hardware", "Memory & Knowledge", "Prompt Sampler")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = AppColors.CardBackground,
            contentColor = AppColors.PrimaryActionGlow,
            divider = { HorizontalDivider(color = AppColors.CardBorder) }
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                )
            }
        }

        when (selectedTab) {
            0 -> DashboardVitalsContent(viewModel, onNavigateToChat)
            1 -> MemoryHubContent(context)
            2 -> TemplateSamplerPage(viewModel, onOpenModelPicker)
        }
    }
}

@Composable
private fun DashboardVitalsContent(
    viewModel: ChatViewModel,
    onNavigateToChat: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val cpuThreads by viewModel.cpuThreads.collectAsState()
    val useMmap by viewModel.useMmap.collectAsState()
    val useMlock by viewModel.useMlock.collectAsState()
    val generationMetrics by viewModel.generationMetrics.collectAsState()
    val memorySnapshots by viewModel.memorySnapshots.collectAsState()

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

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = AppColors.CardBorder)

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

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = AppColors.CardBorder)

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

                val memoryModeText = buildString {
                    if (useMmap) append("mmap (demand-paged)") else append("Direct RAM (no mmap)")
                    if (useMlock) append(" · mlock pinned")
                }
                DashboardMetricRow("Memory Mode", memoryModeText)

                val promptFillText = generationMetrics?.let {
                    "${it.promptTokenCount} / ${it.contextWindowTokens} tokens (${String.format(Locale.US, "%.1f", it.contextFillPercent)}%)"
                } ?: "No runs yet"
                DashboardMetricRow("Prompt / Context Fill", promptFillText)

                val cacheReuseText = generationMetrics?.let {
                    "${it.cachedPromptTokens} reused · ${it.newlyProcessedPromptTokens} newly processed"
                } ?: "No runs yet"
                DashboardMetricRow("KV Cache Reuse", cacheReuseText)

                val prefillSpeed = generationMetrics?.let {
                    if (it.newlyProcessedPromptTokens == 0) "No new prefill needed (full prefix reuse)"
                    else "${formatTokensPerSecond(it.prefillTokensPerSecond)} · ${it.prefillMillis} ms"
                } ?: "No runs yet"
                DashboardMetricRow("Prefill Speed / Time", prefillSpeed)

                val firstToken = generationMetrics?.firstTokenMillis?.let { "$it ms" } ?: "No runs yet"
                DashboardMetricRow("Time to First Token", firstToken)

                val speedText = generationMetrics?.decodeTokensPerSecond?.let { formatTokensPerSecond(it) } ?: "No runs yet"
                DashboardMetricRow("Generation Speed", speedText)

                val responseTime = generationMetrics?.totalMillis?.let { "$it ms" } ?: "No runs yet"
                DashboardMetricRow("Total Response Time", responseTime)

                val outputTokens = generationMetrics?.generatedTokenCount?.let { "$it tokens" } ?: "No runs yet"
                DashboardMetricRow("Generated Answer Tokens", outputTokens)

                val nativeAlloc = memorySnapshots.current?.nativeAllocatedBytes?.let(::formatBytes) ?: "None"
                DashboardMetricRow("Native Memory", nativeAlloc)

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
 * 3. SETTINGS & MODEL PAGE
 * ========================================================================================= */

@Composable
private fun SettingsPage(
    viewModel: ChatViewModel,
    onOpenModelPicker: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val promptTokenCount by viewModel.promptTokenCount.collectAsState()
    val requestedNctx by viewModel.requestedNctx.collectAsState()
    val cpuThreads by viewModel.cpuThreads.collectAsState()
    val useMmap by viewModel.useMmap.collectAsState()
    val useMlock by viewModel.useMlock.collectAsState()
    val inferenceConfig by viewModel.inferenceConfig.collectAsState()
    val generationMetrics by viewModel.generationMetrics.collectAsState()
    val modelPreparationMetrics by viewModel.modelPreparationMetrics.collectAsState()
    val memorySnapshots by viewModel.memorySnapshots.collectAsState()
    val settingsContext = LocalContext.current
    val settingsClipboard = LocalClipboardManager.current
    val settingsScope = rememberCoroutineScope()
    var activitySummary by remember(settingsContext) { mutableStateOf("Loading activity summary…") }
    LaunchedEffect(settingsContext) {
        activitySummary = withContext(Dispatchers.IO) { ActivityLogger.summary(settingsContext) }
    }

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
                title = "Memory Mapping (mmap)",
                subtitle = if (useMmap) "Enabled · Pages weights on demand to conserve RAM" else "Disabled · Direct RAM load (faster answers, requires free RAM)",
                checked = useMmap,
                onCheckedChange = viewModel::updateUseMmap
            )
            SettingsSwitchRow(
                title = "Lock Memory in RAM (mlock)",
                subtitle = if (useMlock) "Enabled · Pins model in RAM to prevent disk paging" else "Disabled · System can reclaim inactive pages when memory is low",
                checked = useMlock,
                onCheckedChange = viewModel::updateUseMlock
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
                title = "Reset KV Cache / Context",
                summary = "Clear cached tokens",
                onClick = { showResetConfirm = true }
            )
        }

        // System Permissions & Access Section
        SettingsSection(title = "System Permissions & Access") {
            val isListenerGranted = isNotificationListenerEnabled(settingsContext)
            val isExactAlarmGranted = canScheduleExactAlarms(settingsContext)
            val isPostGranted = isPostNotificationsGranted(settingsContext)

            SettingsRow(
                title = "Notification Listener Access",
                summary = if (isListenerGranted) "Active · Mr-Red can read alerts for reasoning" else "Disabled · Tap to open Notification Access settings",
                onClick = {
                    runCatching {
                        val intent = Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        settingsContext.startActivity(intent)
                    }
                }
            )

            SettingsRow(
                title = "Exact Alarm Scheduling",
                summary = if (isExactAlarmGranted) "Allowed · Alarms fire reliably" else "Restricted · Tap to grant exact alarm scheduling",
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching {
                            val intent = Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${settingsContext.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            settingsContext.startActivity(intent)
                        }
                    } else {
                        Toast.makeText(settingsContext, "Exact alarms are allowed on this Android version", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            SettingsRow(
                title = "Post Notifications Permission",
                summary = if (isPostGranted) "Granted · Status bar alerts & reminders active" else "Denied · Tap to open App Notification Settings",
                onClick = {
                    runCatching {
                        val intent = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(AndroidSettings.EXTRA_APP_PACKAGE, settingsContext.packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        settingsContext.startActivity(intent)
                    }
                }
            )
        }

        SettingsSection(title = "Activity Logger · On this device") {
            Text(
                text = activitySummary,
                fontSize = 13.sp,
                color = AppColors.TextSecondary,
                lineHeight = 19.sp
            )
            SettingsRow(
                title = "Copy complete diagnostic report",
                summary = "Device, Android, app activity, conversation and llama.cpp logs",
                onClick = {
                    settingsScope.launch {
                        val report = withContext(Dispatchers.IO) { ActivityLogger.diagnosticReport(settingsContext) }
                        settingsClipboard.setText(AnnotatedString(report))
                        Toast.makeText(settingsContext, "Complete diagnostic report copied", Toast.LENGTH_LONG).show()
                    }
                }
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "The on-device activity log stores questions and generated answers to create conversation summaries, along with app navigation, model/runtime events, device actions, settings changes, and errors. The chat transcript is also retained privately on this device (up to 40 completed turns). Nothing is sent to a Mr-Red server.",
                fontSize = 11.sp,
                color = AppColors.TextSecondary,
                lineHeight = 16.sp
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

    // Context Window Selector Dialog
    if (showContextWindowDialog) {
        val options = listOf(512, 1048, 2048, 4096)
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
private fun ScheduledTasksPage(
    onNavigateToChat: () -> Unit
) {
    val context = LocalContext.current
    val tasks by ScheduledTasksStore.tasks.collectAsState()
    val alarms = tasks.filter { it.type == "alarm" }
    val reminders = tasks.filter { it.type == "reminder" }

    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var showAlarmDialog by remember { mutableStateOf(false) }
    var showReminderDialog by remember { mutableStateOf(false) }

    var alarmHourInput by remember { mutableStateOf("") }
    var alarmMinuteInput by remember { mutableStateOf("00") }
    var alarmNoteInput by remember { mutableStateOf("Alarm") }

    var reminderNoteInput by remember { mutableStateOf("") }
    var reminderIntervalInput by remember { mutableStateOf("30") }
    var reminderIsHours by remember { mutableStateOf(false) }
    var reminderIsRecurring by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Scheduled Tasks",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
                Text(
                    text = "Set any alarm or reminder you choose",
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary
                )
            }
            IconButton(onClick = {
                ScheduledTasksStore.init(context)
                actionFeedback = "Refreshed list"
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AppColors.TextSecondary)
            }
        }

        if (actionFeedback != null) {
            Text(
                text = actionFeedback!!,
                fontSize = 12.sp,
                color = AppColors.PrimaryActionGlow,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }

        val hasExactAlarmPermission = canScheduleExactAlarms(context)
        if (!hasExactAlarmPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2B1C1C)),
                border = BorderStroke(1.dp, Color(0xFF5E2B2B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Exact Alarms Restricted", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                        Text(
                            "Android requires permission for alarms and reminders to fire on time.",
                            fontSize = 11.sp,
                            color = AppColors.TextSecondary
                        )
                    }
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                runCatching {
                                    val intent = Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAction)
                    ) {
                        Text("Allow", fontSize = 11.sp)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { showAlarmDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAction)
            ) {
                Text("+ Set Alarm", fontSize = 12.sp, color = Color.White)
            }

            Button(
                onClick = { showReminderDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.CardBackground)
            ) {
                Text("+ Add Reminder", fontSize = 12.sp, color = AppColors.TextPrimary)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val res = AlarmScheduler.dismissAlarm(context)
                    actionFeedback = if (res is ToolResult.Success) res.content else "Dismissed"
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.PrimaryAction)
            ) {
                Text("Dismiss Sound", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = {
                    val res = AlarmScheduler.cancelReminders(context)
                    actionFeedback = if (res is ToolResult.Success) res.content else "Reminders cleared"
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.TextSecondary)
            ) {
                Text("Clear Reminders", fontSize = 12.sp)
            }
        }

        Text(
            text = "ACTIVE ALARMS",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.PrimaryActionGlow,
            letterSpacing = 1.sp
        )

        if (alarms.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "No active alarm",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Use + Set Alarm above or ask Mr-Red in Chat with any time you want.",
                        fontSize = 11.sp,
                        color = AppColors.TextSecondary
                    )
                }
            }
        } else {
            alarms.forEach { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Alarm,
                                    contentDescription = null,
                                    tint = AppColors.PrimaryAction,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = item.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppColors.TextPrimary
                                )
                            }
                            IconButton(onClick = {
                                AlarmScheduler.dismissAlarm(context)
                                actionFeedback = "Alarm cancelled"
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel Alarm", tint = AppColors.TextSecondary)
                            }
                        }
                        if (item.note.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Note: " + item.note,
                                fontSize = 12.sp,
                                color = AppColors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.scheduleDisplay,
                            fontSize = 11.sp,
                            color = AppColors.PrimaryActionGlow
                        )
                    }
                }
            }
        }

        Text(
            text = "UPCOMING REMINDERS",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.PrimaryActionGlow,
            letterSpacing = 1.sp
        )

        if (reminders.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "No upcoming reminders",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Use + Add Reminder above or ask Mr-Red in Chat with any interval and note.",
                        fontSize = 11.sp,
                        color = AppColors.TextSecondary
                    )
                }
            }
        } else {
            reminders.forEach { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.TextPrimary
                            )
                            IconButton(onClick = {
                                val res = AlarmScheduler.cancelSingleReminder(context, item.id)
                                actionFeedback = if (res is ToolResult.Success) res.content else "Cancelled"
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel Reminder", tint = AppColors.TextSecondary)
                            }
                        }
                        if (item.note.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Note: " + item.note,
                                fontSize = 12.sp,
                                color = AppColors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.scheduleDisplay,
                            fontSize = 11.sp,
                            color = AppColors.PrimaryActionGlow
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Need help setting tasks?",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Mr-Red accepts any hour, minute, interval number, or note you choose. You can also chat directly.",
                    fontSize = 11.sp,
                    color = AppColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onNavigateToChat,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.PrimaryActionGlow)
                ) {
                    Text("Go to Chat", fontSize = 12.sp)
                }
            }
        }
    }

    if (showAlarmDialog) {
        AlertDialog(
            onDismissRequest = { showAlarmDialog = false },
            title = { Text("Set Custom Alarm", color = AppColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hour (0-23):", fontSize = 12.sp, color = AppColors.TextSecondary)
                    OutlinedTextField(
                        value = alarmHourInput,
                        onValueChange = { alarmHourInput = it },
                        placeholder = { Text("e.g. 7, 8, 14, 21") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Minute (0-59):", fontSize = 12.sp, color = AppColors.TextSecondary)
                    OutlinedTextField(
                        value = alarmMinuteInput,
                        onValueChange = { alarmMinuteInput = it },
                        placeholder = { Text("0-59") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Label / Note:", fontSize = 12.sp, color = AppColors.TextSecondary)
                    OutlinedTextField(
                        value = alarmNoteInput,
                        onValueChange = { alarmNoteInput = it },
                        placeholder = { Text("e.g. Wake up, Meeting") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val h = alarmHourInput.toIntOrNull()
                        val m = alarmMinuteInput.toIntOrNull() ?: 0
                        if (h != null && h in 0..23 && m in 0..59) {
                            val res = AlarmScheduler.setInAppAlarm(context, h, m, alarmNoteInput.ifBlank { "Alarm" })
                            actionFeedback = if (res is ToolResult.Success) res.content else "Alarm set"
                            showAlarmDialog = false
                        } else {
                            actionFeedback = "Please enter a valid hour (0-23) and minute (0-59)."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAction)
                ) {
                    Text("Save Alarm", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAlarmDialog = false }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.CardBackground
        )
    }

    if (showReminderDialog) {
        AlertDialog(
            onDismissRequest = { showReminderDialog = false },
            title = { Text("Set Custom Reminder", color = AppColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What to remind you:", fontSize = 12.sp, color = AppColors.TextSecondary)
                    OutlinedTextField(
                        value = reminderNoteInput,
                        onValueChange = { reminderNoteInput = it },
                        placeholder = { Text("e.g. Drink water, Call doctor") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Interval number:", fontSize = 12.sp, color = AppColors.TextSecondary)
                    OutlinedTextField(
                        value = reminderIntervalInput,
                        onValueChange = { reminderIntervalInput = it },
                        placeholder = { Text("e.g. 15, 30, 45, 60, 2") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (reminderIsHours) "Unit: Hours" else "Unit: Minutes", fontSize = 12.sp, color = AppColors.TextPrimary)
                        Switch(
                            checked = reminderIsHours,
                            onCheckedChange = { reminderIsHours = it }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (reminderIsRecurring) "Repeats periodically" else "One-time reminder", fontSize = 12.sp, color = AppColors.TextPrimary)
                        Switch(
                            checked = reminderIsRecurring,
                            onCheckedChange = { reminderIsRecurring = it }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val num = reminderIntervalInput.toLongOrNull()
                        val note = reminderNoteInput.trim()
                        if (note.isNotBlank() && num != null && num > 0) {
                            val totalMins = if (reminderIsHours) num * 60L else num
                            val res = AlarmScheduler.scheduleReminder(context, note, totalMins, reminderIsRecurring)
                            actionFeedback = if (res is ToolResult.Success) res.content else "Reminder set"
                            showReminderDialog = false
                        } else {
                            actionFeedback = "Please enter a note and positive interval number."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAction)
                ) {
                    Text("Save Reminder", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReminderDialog = false }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.CardBackground
        )
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

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis.coerceAtLeast(0L) + 500L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun formatKb(kb: Int): String = formatBytes(kb.toLong() * 1024L)

private fun formatTokensPerSecond(value: Double): String = String.format(Locale.US, "%.1f t/s", value)


/* =========================================================================================
 * TEMPLATE SAMPLER — read-only Kotlin retrieval + real GGUF prompt test
 * ========================================================================================= */

@Composable
private fun TemplateSamplerPage(
    viewModel: ChatViewModel,
    onOpenModelPicker: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val messages by viewModel.samplerMessages.collectAsState()
    val isGenerating by viewModel.samplerGenerating.collectAsState()
    val status by viewModel.samplerStatus.collectAsState()
    val retrievedMemory by viewModel.samplerRetrievedMemory.collectAsState()
    val formattedPrompt by viewModel.samplerFormattedPrompt.collectAsState()
    val promptTokenCount by viewModel.samplerPromptTokenCount.collectAsState()
    val clipboard = LocalClipboardManager.current
    var showRetrievedMemory by remember { mutableStateOf(false) }
    var showFormattedPrompt by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Template Sampler", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                Text("Test Kotlin retrieval and the exact prompt sent to GGUF.", fontSize = 12.sp, color = AppColors.TextSecondary)
            }
            TextButton(
                onClick = { viewModel.clearSamplerSession(); showRetrievedMemory = false; showFormattedPrompt = false },
                enabled = !isGenerating
            ) { Text("Clear") }
        }

        Text(
            text = when (uiState) {
                is ChatUiState.Ready -> "Model: ${(uiState as ChatUiState.Ready).modelName}"
                is ChatUiState.Preparing -> "Loading model…"
                is ChatUiState.Error -> "Model error"
                ChatUiState.Idle -> "No model loaded"
            },
            fontSize = 12.sp,
            color = AppColors.PrimaryActionGlow,
            modifier = Modifier.padding(top = 4.dp, bottom = 3.dp)
        )
        Text(status, fontSize = 11.sp, color = AppColors.TextSecondary)
        HorizontalDivider(color = AppColors.CardBorder, modifier = Modifier.padding(vertical = 8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("Ask a database-dependent question", fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "Examples: “What notifications did I receive in the last hour?”, “What happened yesterday?”, or “What do you remember about my preferences?”",
                                fontSize = 13.sp,
                                color = AppColors.TextSecondary
                            )
                            Text(
                                "The sampler reads existing memory records. Test questions and answers are not saved to normal chat history or the memory database.",
                                fontSize = 12.sp,
                                color = AppColors.TextSecondary,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
            items(messages) { message ->
                val isUser = message.role == MessageRole.User
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.CardBorder)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            if (isUser) "TEST QUESTION" else "MODEL RESPONSE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.PrimaryActionGlow
                        )
                        Text(
                            message.content.ifBlank { if (isGenerating && !isUser) "Generating…" else " " },
                            fontSize = 14.sp,
                            color = AppColors.TextPrimary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (!isUser && message.reasoningContent.isNotBlank()) {
                            Text("Reasoning: ${message.reasoningContent}", fontSize = 11.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))
                        }
                        message.generationMetrics?.let { metrics ->
                            Text(
                                "Prompt ${metrics.promptTokenCount} tokens · cached ${metrics.cachedPromptTokens} · prefilled ${metrics.newlyProcessedPromptTokens} · output ${metrics.generatedTokenCount}",
                                fontSize = 10.sp,
                                color = AppColors.TextSecondary,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.CardBorder)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("KOTLIN → MODEL TRACE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AppColors.PrimaryActionGlow, modifier = Modifier.weight(1f))
                    Text("${promptTokenCount} prompt tokens", fontSize = 10.sp, color = AppColors.TextSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showRetrievedMemory = !showRetrievedMemory; showFormattedPrompt = false }) {
                        Text(if (showRetrievedMemory) "Hide retrieved data" else "Retrieved data")
                    }
                    TextButton(onClick = { showFormattedPrompt = !showFormattedPrompt; showRetrievedMemory = false }) {
                        Text(if (showFormattedPrompt) "Hide model prompt" else "Exact model prompt")
                    }
                    if (formattedPrompt.isNotBlank()) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(formattedPrompt)) }) { Text("Copy") }
                    }
                }
                if (showRetrievedMemory) {
                    Text(
                        retrievedMemory.ifBlank { "(Kotlin selected no matching database records for this question.)" },
                        fontSize = 11.sp,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())
                    )
                }
                if (showFormattedPrompt) {
                    Text(
                        formattedPrompt.ifBlank { "The final formatted prompt appears here when inference starts." },
                        fontSize = 11.sp,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp).verticalScroll(rememberScrollState())
                    )
                }
            }
        }

        ChatComposer(
            enabled = uiState is ChatUiState.Ready,
            isGenerating = isGenerating,
            isStopRequested = false,
            onSend = viewModel::sendSamplerQuestion,
            onStop = viewModel::stopSamplerGeneration,
            onOpenModelPicker = onOpenModelPicker
        )
    }
}