package com.arick.androidlocalllmlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScheduledTasksStore.init(applicationContext)
        ActivityLogger.record(applicationContext, "APP", "started", "Mr-Red device controls opened")
        setContent {
            com.arick.androidlocalllmlab.ui.theme.AndroidLocalLlmLabTheme {
                MrRedApp()
            }
        }
    }
}

@Composable
private fun MrRedApp() {
    var selectedTab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Text("Mr-Red", modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp), style = MaterialTheme.typography.headlineMedium)
        TabRow(selectedTabIndex = selectedTab) {
            listOf("Device controls", "Database", "Activity").forEachIndexed { index, label ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(label) })
            }
        }
        when (selectedTab) {
            0 -> DeviceControlsPage()
            1 -> DatabaseExplorerPage()
            else -> ActivityPage()
        }
    }
}

@Composable
private fun DeviceControlsPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val executor = remember(context) { AndroidToolExecutor(context.applicationContext) }
    var appName by remember { mutableStateOf("settings") }
    var phone by remember { mutableStateOf("") }
    var recipient by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf("60") }
    var hour by remember { mutableStateOf("7") }
    var minute by remember { mutableStateOf("0") }
    var interval by remember { mutableStateOf("30") }
    var result by remember { mutableStateOf("Choose a device action.") }
    var busy by remember { mutableStateOf(false) }

    fun runTool(name: String, args: JSONObject = JSONObject()) {
        if (busy) return
        busy = true
        result = "Running $name…"
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { executor.execute(ToolCall(name, args)) }
            result = when (outcome) {
                is ToolResult.Success -> outcome.content
                is ToolResult.Failure -> outcome.message
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("DEVICE STATUS", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runTool("device_vitals") }, enabled = !busy) { Text("Device vitals") }
            Button(onClick = { runTool("get_notifications") }, enabled = !busy) { Text("Notifications") }
        }
        Text("ANDROID SETTINGS", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("wifi", "bluetooth", "battery").forEach { page ->
                Button(onClick = { runTool("open_settings", JSONObject().put("page", page)) }, enabled = !busy) { Text(page.replaceFirstChar { it.uppercase() }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("display", "system").forEach { page ->
                Button(onClick = { runTool("open_settings", JSONObject().put("page", page)) }, enabled = !busy) { Text(page.replaceFirstChar { it.uppercase() }) }
            }
            Button(onClick = { runTool("open_notification_access") }, enabled = !busy) { Text("Notification access") }
        }
        Text("APPS AND COMMUNICATION", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(appName, { appName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("App name") }, singleLine = true)
        Button(onClick = { runTool("open_app", JSONObject().put("app_name", appName)) }, enabled = !busy) { Text("Open app") }
        OutlinedTextField(phone, { phone = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Phone number") }, singleLine = true)
        Button(onClick = { runTool("make_call", JSONObject().put("phone_number", phone)) }, enabled = !busy) { Text("Open dialer") }
        OutlinedTextField(recipient, { recipient = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SMS recipient") }, singleLine = true)
        OutlinedTextField(message, { message = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Message") })
        Button(onClick = { runTool("send_message", JSONObject().put("platform", "sms").put("recipient", recipient).put("message", message)) }, enabled = !busy) { Text("Draft SMS") }
        Text("TIMERS, ALARMS AND REMINDERS", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(seconds, { seconds = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Timer seconds") }, singleLine = true)
        Button(onClick = { runTool("set_timer", JSONObject().put("seconds", seconds.toIntOrNull() ?: 0)) }, enabled = !busy) { Text("Set timer") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(hour, { hour = it }, modifier = Modifier.weight(1f), label = { Text("Hour (0–23)") }, singleLine = true)
            OutlinedTextField(minute, { minute = it }, modifier = Modifier.weight(1f), label = { Text("Minute") }, singleLine = true)
        }
        Button(onClick = { runTool("set_alarm", JSONObject().put("hour", hour.toIntOrNull() ?: -1).put("minute", minute.toIntOrNull() ?: -1).put("message", "Mr-Red Alarm")) }, enabled = !busy) { Text("Set alarm") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runTool("dismiss_alarm") }, enabled = !busy) { Text("Dismiss alarm") }
            Button(onClick = { runTool("list_scheduled_tasks") }, enabled = !busy) { Text("List schedules") }
        }
        OutlinedTextField(message, { message = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Reminder text") })
        OutlinedTextField(interval, { interval = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Reminder delay / interval (minutes)") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runTool("schedule_reminder", JSONObject().put("message", message).put("interval_minutes", interval.toLongOrNull() ?: 0).put("recurring", false)) }, enabled = !busy) { Text("One-time reminder") }
            Button(onClick = { runTool("schedule_reminder", JSONObject().put("message", message).put("interval_minutes", interval.toLongOrNull() ?: 0).put("recurring", true)) }, enabled = !busy) { Text("Recurring") }
        }
        Button(onClick = { runTool("cancel_reminders") }, enabled = !busy) { Text("Cancel reminders") }
        Text("INTERNET TOOLS", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(city, { city = it }, modifier = Modifier.fillMaxWidth(), label = { Text("City for weather") }, singleLine = true)
        Button(onClick = { runTool("get_weather", JSONObject().put("city", city)) }, enabled = !busy) { Text("Get weather") }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("News topic / web search query") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runTool("get_news", JSONObject().put("query", query)) }, enabled = !busy) { Text("News") }
            Button(onClick = { runTool("web_search", JSONObject().put("query", query)) }, enabled = !busy) { Text("Web search") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (busy) "ACTION IN PROGRESS" else "RESULT", style = MaterialTheme.typography.labelLarge)
                if (busy) CircularProgressIndicator()
                Text(result, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ActivityPage() {
    val context = LocalContext.current
    var report by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Activity log", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { report = ActivityLogger.recentText(context) }) { Text("Refresh") }
            Button(onClick = { report = ActivityLogger.diagnosticReport(context) }) { Text("Diagnostics") }
            Button(onClick = { ActivityLogger.clear(context); report = "Activity history cleared." }) { Text("Clear") }
        }
        Text(report.ifBlank { ActivityLogger.summary(context) }, fontSize = 12.sp)
    }
}
