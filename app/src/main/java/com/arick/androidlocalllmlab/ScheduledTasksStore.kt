package com.arick.androidlocalllmlab

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ScheduledTaskItem(
    val id: String,
    val type: String, // "alarm" or "reminder"
    val title: String,
    val note: String,
    val scheduleDisplay: String,
    val isRecurring: Boolean,
    val targetTimeMillis: Long = 0L,
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("title", title)
        put("note", note)
        put("scheduleDisplay", scheduleDisplay)
        put("isRecurring", isRecurring)
        put("targetTimeMillis", targetTimeMillis)
        put("createdAtMillis", createdAtMillis)
    }

    companion object {
        fun fromJson(obj: JSONObject): ScheduledTaskItem = ScheduledTaskItem(
            id = obj.optString("id"),
            type = obj.optString("type", "reminder"),
            title = obj.optString("title"),
            note = obj.optString("note"),
            scheduleDisplay = obj.optString("scheduleDisplay"),
            isRecurring = obj.optBoolean("isRecurring", false),
            targetTimeMillis = obj.optLong("targetTimeMillis", 0L),
            createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis())
        )
    }
}

object ScheduledTasksStore {
    private const val PREFS_NAME = "mr_red_scheduled_tasks"
    private const val KEY_TASKS = "tasks_json"

    private val _tasks = MutableStateFlow<List<ScheduledTaskItem>>(emptyList())
    val tasks: StateFlow<List<ScheduledTaskItem>> = _tasks.asStateFlow()

    fun init(context: Context) {
        val loaded = load(context)
        _tasks.value = loaded
    }

    private fun load(context: Context): List<ScheduledTaskItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(jsonStr)
            List(array.length()) { i ->
                ScheduledTaskItem.fromJson(array.getJSONObject(i))
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(context: Context, list: List<ScheduledTaskItem>) {
        _tasks.value = list
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_TASKS, array.toString()).apply()
    }

    @Synchronized
    fun recordAlarm(context: Context, hour: Int, minute: Int, message: String, triggerMillis: Long) {
        val timeFormatted = String.format("%02d:%02d", hour, minute)
        val item = ScheduledTaskItem(
            id = "alarm_active",
            type = "alarm",
            title = "In-App Alarm at $timeFormatted",
            note = message,
            scheduleDisplay = "Fires at $timeFormatted",
            isRecurring = false,
            targetTimeMillis = triggerMillis
        )
        val current = load(context).filter { it.type != "alarm" }
        persist(context, listOf(item) + current)
    }

    @Synchronized
    fun clearAlarms(context: Context) {
        val current = load(context).filter { it.type != "alarm" }
        persist(context, current)
    }

    @Synchronized
    fun recordReminder(
        context: Context,
        id: String,
        message: String,
        scheduleDisplay: String,
        recurring: Boolean,
        targetMillis: Long
    ) {
        val item = ScheduledTaskItem(
            id = id,
            type = "reminder",
            title = if (recurring) "Recurring Reminder" else "Scheduled Reminder",
            note = message,
            scheduleDisplay = scheduleDisplay,
            isRecurring = recurring,
            targetTimeMillis = targetMillis
        )
        val current = load(context).filter { it.id != id }
        persist(context, listOf(item) + current)
    }

    @Synchronized
    fun removeReminder(context: Context, id: String) {
        val current = load(context).filter { it.id != id }
        persist(context, current)
    }

    @Synchronized
    fun clearReminders(context: Context) {
        val current = load(context).filter { it.type != "reminder" }
        persist(context, current)
    }
}
