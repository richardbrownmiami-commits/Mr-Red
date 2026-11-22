package com.arick.androidlocalllmlab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arick.androidlocalllmlab.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class DatabaseSection(val label: String, val key: String) {
    Notifications("Notifications", "notifications"),
    Actions("Actions", "actions"),
    Activities("Activities", "activities"),
    Chat("Chat", "chat")
}

/** Read-only explorer for existing SQLite records; this screen never feeds records into chat. */
@Composable
fun DatabaseExplorerPage() {
    val context = LocalContext.current
    var selectedSection by remember { mutableStateOf(DatabaseSection.Notifications) }
    var searchText by remember { mutableStateOf("") }
    var refreshToken by remember { mutableIntStateOf(0) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var eventRows by remember { mutableStateOf<List<StoredEventRecord>>(emptyList()) }
    var chatRows by remember { mutableStateOf<List<StoredConversationRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedSection, searchText, refreshToken) {
        loading = true
        errorMessage = null
        try {
            val result = withContext(Dispatchers.IO) {
                val currentCounts = EventMemoryStore.databaseBrowserCounts(context)
                if (selectedSection == DatabaseSection.Chat) {
                    Triple(currentCounts, emptyList<StoredEventRecord>(), EventMemoryStore.browseConversations(context, searchText))
                } else {
                    Triple(currentCounts, EventMemoryStore.browseEvents(context, selectedSection.key, searchText), emptyList<StoredConversationRecord>())
                }
            }
            counts = result.first
            eventRows = result.second
            chatRows = result.third
        } catch (error: Exception) {
            errorMessage = error.message ?: error.javaClass.simpleName
            eventRows = emptyList()
            chatRows = emptyList()
        } finally {
            loading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Database Explorer", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                Text("Read-only view of records already stored on this device", fontSize = 12.sp, color = AppColors.TextSecondary)
            }
            IconButton(onClick = { refreshToken++ }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh database records", tint = AppColors.TextSecondary)
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DatabaseSection.values().toList()) { section ->
                DatabaseCountCard(
                    label = section.label,
                    count = counts[section.key] ?: 0,
                    selected = selectedSection == section,
                    onClick = { selectedSection = section }
                )
            }
        }

        TabRow(
            selectedTabIndex = DatabaseSection.values().indexOf(selectedSection),
            containerColor = AppColors.PageBackground,
            contentColor = AppColors.PrimaryActionGlow
        ) {
            DatabaseSection.values().forEach { section ->
                Tab(
                    selected = selectedSection == section,
                    onClick = { selectedSection = section },
                    text = {
                        Text(
                            section.label,
                            fontSize = 11.sp,
                            maxLines = 1,
                            fontWeight = if (selectedSection == section) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search stored records", color = AppColors.TextHint) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AppColors.TextSecondary) }
        )

        Text(
            text = when (selectedSection) {
                DatabaseSection.Notifications -> "Captured notification posts • original title and body"
                DatabaseSection.Actions -> "Device action lifecycle events recorded by Mr-Red"
                DatabaseSection.Activities -> "App, model, UI, permission, error and other event records"
                DatabaseSection.Chat -> "Completed question-and-answer pairs saved in conversation history"
            },
            fontSize = 11.sp,
            color = AppColors.TextTertiary
        )

        if (loading) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = AppColors.PrimaryActionGlow)
                Spacer(Modifier.height(8.dp))
                Text("Reading existing database…", color = AppColors.TextSecondary, fontSize = 12.sp)
            }
        } else if (errorMessage != null) {
            Text("Could not read database: $errorMessage", color = AppColors.Error, fontSize = 13.sp)
        } else if (selectedSection == DatabaseSection.Chat) {
            if (chatRows.isEmpty()) {
                EmptyDatabaseSection("No saved conversations match this search.", Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(chatRows, key = { it.id }) { record ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("QUESTION", fontSize = 10.sp, color = AppColors.PrimaryActionGlow, fontWeight = FontWeight.Bold)
                                Text(record.question, color = AppColors.TextPrimary, fontSize = 13.sp)
                                Text("MR-RED RESPONSE", fontSize = 10.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.Bold)
                                Text(record.answer, color = AppColors.TextPrimary, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        } else {
            if (eventRows.isEmpty()) {
                EmptyDatabaseSection("No stored records match this search.", Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(eventRows, key = { it.id }) { record ->
                        EventRecordCard(record = record, section = selectedSection)
                    }
                }
            }
        }
    }
}

@Composable
private fun DatabaseCountCard(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) AppColors.UserBubble else AppColors.CardBackground)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(count.toString(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Text(label, fontSize = 11.sp, color = AppColors.TextSecondary)
        }
    }
}

@Composable
private fun EventRecordCard(record: StoredEventRecord, section: DatabaseSection) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (section) {
                DatabaseSection.Notifications -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            record.source.ifBlank { record.packageName.ifBlank { "Unknown app" } },
                            modifier = Modifier.weight(1f),
                            fontSize = 12.sp,
                            color = AppColors.PrimaryActionGlow,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (record.isActive) "ACTIVE" else "DISMISSED",
                            fontSize = 9.sp,
                            color = if (record.isActive) AppColors.Success else AppColors.TextTertiary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (record.title.isNotBlank()) {
                        Text(record.title, color = AppColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (record.body.isNotBlank()) Text(record.body, color = AppColors.TextSecondary, fontSize = 13.sp)
                    if (record.importance.isNotBlank()) Text("Category: ${record.importance}", color = AppColors.TextTertiary, fontSize = 10.sp)
                    if (record.packageName.isNotBlank()) Text(record.packageName, color = AppColors.TextTertiary, fontSize = 10.sp)
                }
                DatabaseSection.Actions -> {
                    Text(record.action.replace('_', ' ').uppercase(), color = AppColors.PrimaryActionGlow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(record.detail.ifBlank { record.source }, color = AppColors.TextPrimary, fontSize = 13.sp)
                }
                DatabaseSection.Activities -> {
                    Text("${record.eventType}  /  ${record.action}", color = AppColors.PrimaryActionGlow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(record.detail.ifBlank { "No additional detail" }, color = AppColors.TextPrimary, fontSize = 13.sp)
                    if (record.source.isNotBlank()) Text("Source: ${record.source}", color = AppColors.TextTertiary, fontSize = 10.sp)
                }
                DatabaseSection.Chat -> Unit
            }
        }
    }
}

@Composable
private fun EmptyDatabaseSection(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message, color = AppColors.TextSecondary, fontSize = 13.sp)
    }
}
