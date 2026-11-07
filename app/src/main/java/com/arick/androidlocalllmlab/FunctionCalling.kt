package com.arick.androidlocalllmlab

import android.app.ActivityManager
import android.app.NotificationManager
import android.text.Html
import android.os.SystemClock
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

data class ToolCall(
    val name: String,
    val arguments: JSONObject
)

data class SourceLink(
    val title: String,
    val url: String
)

sealed interface ToolResult {
    data class Success(
        val content: String,
        val sources: List<SourceLink> = emptyList()
    ) : ToolResult

    data class Failure(val message: String) : ToolResult
}

data class ToolParameterSchema(
    val name: String,
    val description: String,
    val allowedValues: List<String> = emptyList(),
    val maxLength: Int? = null
)

interface ToolDefinition {
    val name: String
    val description: String
    val parameterSchema: List<ToolParameterSchema>

    fun execute(arguments: JSONObject, onProgress: (String) -> Unit): ToolResult
}

class ToolRegistry(definitions: List<ToolDefinition>) {
    private val definitionsByName = definitions.associateBy(ToolDefinition::name)

    init {
        require(definitionsByName.size == definitions.size) { "Tool names must be unique" }
    }

    fun execute(call: ToolCall, onProgress: (String) -> Unit = {}): ToolResult =
        definitionsByName[call.name]?.execute(call.arguments, onProgress)
            ?: ToolResult.Failure("Tool not allowed: ${call.name}")

    fun modelInstruction(): String = definitionsByName.values.joinToString("\n") { tool ->
        val parameters = tool.parameterSchema.joinToString("; ") { parameter ->
            buildString {
                append("${parameter.name}: ${parameter.description}")
                parameter.allowedValues.takeIf { it.isNotEmpty() }?.let {
                    append(", allowed values: ${it.joinToString(" or ")}")
                }
                parameter.maxLength?.let { append(", max $it chars") }
            }
        }
        "- ${tool.name}: ${tool.description}. Arguments: $parameters"
    }
}

class AndroidToolExecutor(private val context: Context) {
    private val registry = ToolRegistry(
        listOf(
            object : ToolDefinition {
                override val name = "open_settings"
                override val description = "Open Android device settings page"
                override val parameterSchema = listOf(
                    ToolParameterSchema("page", "Settings page to open", allowedValues = listOf("wifi", "bluetooth", "battery", "display", "system"))
                )

                override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = openSettings(arguments)
            },
            object : ToolDefinition {
                override val name = "open_app"
                override val description = "Launch an installed Android application by name"
                override val parameterSchema = listOf(
                    ToolParameterSchema("app_name", "Application name (e.g. whatsapp, youtube, chrome, camera, maps, settings)", maxLength = 60)
                )

                override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = openApp(arguments)
            },
            object : ToolDefinition {
                override val name = "send_message"
                override val description = "Draft or send a message via WhatsApp or SMS"
                override val parameterSchema = listOf(
                    ToolParameterSchema("platform", "Platform to use", allowedValues = listOf("whatsapp", "sms")),
                    ToolParameterSchema("recipient", "Phone number or contact name", maxLength = 60),
                    ToolParameterSchema("message", "Message text content", maxLength = 500)
                )

                override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = sendMessage(arguments)
            },
            object : ToolDefinition {
                override val name = "make_call"
                override val description = "Open phone dialer with a phone number"
                override val parameterSchema = listOf(
                    ToolParameterSchema("phone_number", "Phone number to dial", maxLength = 30)
                )

                override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = makeCall(arguments)
            },
            object : ToolDefinition {
                override val name = "set_timer"
                override val description = "Set a countdown timer on the phone"
                override val parameterSchema = listOf(
                    ToolParameterSchema("seconds", "Timer duration in seconds")
                )

                override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = setTimer(arguments)
            },
            object : ToolDefinition {
                override val name = "set_alarm"
                override val description = "Set an in-app alarm in Mr-Red with ringtone and notification"
                override val parameterSchema = listOf(
                    ToolParameterSchema("hour", "Hour in 24-hour time (0-23)"),
                    ToolParameterSchema("minute", "Minute (0-59)"),
                    ToolParameterSchema("message", "Optional alarm label", maxLength = 60)
                )
                override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = setAlarm(arguments)
            },
            object : ToolDefinition {
                override val name = "dismiss_alarm"
                override val description = "Dismiss and stop any actively ringing Mr-Red alarm"
                override val parameterSchema: List<ToolParameterSchema> = emptyList()
                override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = dismissAlarm()
            },
            object : ToolDefinition {
                override val name = "schedule_reminder"
                override val description = "Schedule a recurring or one-time reminder using WorkManager"
                override val parameterSchema = listOf(
                    ToolParameterSchema("message", "Reminder message text"),
                    ToolParameterSchema("interval_minutes", "Interval or delay in minutes"),
                    ToolParameterSchema("recurring", "True for repeating, false for one-time")
                )
                override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = scheduleReminder(arguments)
            },
            object : ToolDefinition {
                override val name = "cancel_reminders"
                override val description = "Cancel all scheduled background reminders"
                override val parameterSchema: List<ToolParameterSchema> = emptyList()
                override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = cancelReminders()
            },
            object : ToolDefinition {
                override val name = "list_scheduled_tasks"
                override val description = "List all active alarms and upcoming reminders with notes set by Mr-Red"
                override val parameterSchema: List<ToolParameterSchema> = emptyList()
                override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = listScheduledTasks()
            },
            object : ToolDefinition {
                override val name = "read_notifications"
                override val description = "Read recent notifications after the user grants Android notification access"
                override val parameterSchema: List<ToolParameterSchema> = emptyList()
                override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = readNotifications()
            },
            object : ToolDefinition {
                override val name = "open_notification_access"
                override val description = "Open Android settings so the user can explicitly grant notification access"
                override val parameterSchema: List<ToolParameterSchema> = emptyList()
                override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = openNotificationAccess()
            },
            object : ToolDefinition {
                override val name = "get_news"
                override val description = "Fetch current news headlines for a topic from free Google News RSS"
                override val parameterSchema = listOf(ToolParameterSchema("query", "News topic; leave blank for top stories", maxLength = 120))
                override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = getNews(arguments, onProgress)
            },
            object : ToolDefinition {
                override val name = "device_vitals"
                override val description = "Check current battery level, available storage, and free RAM"
                override val parameterSchema: List<ToolParameterSchema> = emptyList()

                override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = getDeviceVitals()
            },
            object : ToolDefinition {
                override val name = "get_weather"
                override val description = "Query current weather and 3-day forecast for a city"
                override val parameterSchema = listOf(
                    ToolParameterSchema("city", "City name", maxLength = 40)
                )

                override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = getWeather(arguments, onProgress)
            },
            object : ToolDefinition {
                override val name = "web_search"
                override val description = "Search the web for up-to-date information"
                override val parameterSchema = listOf(
                    ToolParameterSchema("query", "Concise search query", maxLength = 240)
                )

                override fun execute(arguments: JSONObject, onProgress: (String) -> Unit) = webSearch(arguments, onProgress)
            }
        )
    )

    fun execute(call: ToolCall, onProgress: (String) -> Unit = {}): ToolResult =
        registry.execute(call, onProgress)

    fun modelInstruction(): String = registry.modelInstruction()

    fun openSettings(arguments: JSONObject): ToolResult {
        val page = arguments.optString("page", "system").lowercase()
        val action = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

        return runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult.Success("Opened $page settings page.")
        }.getOrElse { error ->
            ToolResult.Failure("Failed to open settings: ${error.message ?: error::class.java.simpleName}")
        }
    }

    fun openApp(arguments: JSONObject): ToolResult {
        val appName = arguments.optString("app_name").trim().lowercase()
        if (appName.isEmpty()) return ToolResult.Failure("app_name cannot be empty")

        if (appName in setOf("dialer", "phone dialer", "phone", "keypad", "call", "telephone")) {
            return makeCall(JSONObject().put("phone_number", ""))
        }
        if (appName in setOf("battery", "battery settings", "device battery settings")) {
            return openSettings(JSONObject().put("page", "battery"))
        }
        if (appName in setOf("settings", "device settings", "system settings")) {
            return openSettings(JSONObject().put("page", "system"))
        }
        if (appName in setOf("wifi", "wifi settings")) {
            return openSettings(JSONObject().put("page", "wifi"))
        }
        if (appName in setOf("bluetooth", "bluetooth settings")) {
            return openSettings(JSONObject().put("page", "bluetooth"))
        }
        if (appName in setOf("display", "display settings")) {
            return openSettings(JSONObject().put("page", "display"))
        }
        if (appName in setOf("clock", "alarm", "alarms", "timer")) {
            return runCatching {
                val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolResult.Success("Opened clock.")
            }.getOrElse {
                ToolResult.Failure("Could not open clock: ${it.message ?: it::class.java.simpleName}")
            }
        }

        val knownPackages = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "camera" to "com.android.camera",
            "maps" to "com.google.android.apps.maps",
            "settings" to "com.android.settings",
            "telegram" to "org.telegram.messenger",
            "gmail" to "com.google.android.gm"
        )

        val targetPkg = knownPackages[appName]

        val pm = context.packageManager
        val intent = targetPkg?.let { pm.getLaunchIntentForPackage(it) }
            ?: run {
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val matched = apps.firstOrNull { app ->
                    val label = pm.getApplicationLabel(app).toString().lowercase()
                    label.contains(appName) || app.packageName.lowercase().contains(appName)
                }
                matched?.let { pm.getLaunchIntentForPackage(it.packageName) }
            }

        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult.Success("Successfully launched $appName.")
        } else {
            ToolResult.Failure("App '$appName' not found on device.")
        }
    }

    fun sendMessage(arguments: JSONObject): ToolResult {
        val platform = arguments.optString("platform", "whatsapp").lowercase()
        val recipient = arguments.optString("recipient").trim()
        val message = arguments.optString("message").trim()

        if (message.isEmpty()) return ToolResult.Failure("Message text cannot be empty")

        return runCatching {
            if (platform == "whatsapp") {
                val cleanNumber = recipient.replace(Regex("[^0-9]"), "")
                val uri = if (cleanNumber.isNotEmpty()) {
                    Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${URLEncoder.encode(message, "UTF-8")}")
                } else {
                    Uri.parse("https://api.whatsapp.com/send?text=${URLEncoder.encode(message, "UTF-8")}")
                }
                val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ToolResult.Success("Opened WhatsApp with message draft for $recipient.")
            } else {
                val uri = Uri.parse("smsto:${Uri.encode(recipient)}")
                val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                    putExtra("sms_body", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolResult.Success("Opened SMS composer with draft for $recipient.")
            }
        }.getOrElse { error ->
            ToolResult.Failure("Failed to draft message: ${error.message}")
        }
    }

    fun makeCall(arguments: JSONObject): ToolResult {
        val phoneNumber = arguments.optString("phone_number").trim()
        val uri = if (phoneNumber.isNotEmpty()) Uri.parse("tel:${Uri.encode(phoneNumber)}") else null

        return runCatching {
            val intent = (if (uri != null) Intent(Intent.ACTION_DIAL, uri) else Intent(Intent.ACTION_DIAL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            if (phoneNumber.isNotEmpty()) {
                ToolResult.Success("Dialer opened with phone number $phoneNumber.")
            } else {
                ToolResult.Success("Opened phone dialer.")
            }
        }.getOrElse { error ->
            ToolResult.Failure("Failed to open dialer: ${error.message ?: error::class.java.simpleName}")
        }
    }

    fun setTimer(arguments: JSONObject): ToolResult {
        val seconds = arguments.optInt("seconds", 0)
        if (seconds <= 0) return ToolResult.Failure("seconds must be greater than 0")

        return runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Timer set for $seconds seconds.")
        }.getOrElse { error ->
            ToolResult.Failure("Failed to set timer: ${error.message}")
        }
    }

    fun setAlarm(arguments: JSONObject): ToolResult {
        val hour = arguments.optInt("hour", -1)
        val minute = arguments.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolResult.Failure("Use an alarm time with hour 0-23 and minute 0-59.")
        }
        val message = arguments.optString("message", "Mr-Red alarm").take(60)
        return AlarmScheduler.setInAppAlarm(context, hour, minute, message)
    }

    fun dismissAlarm(): ToolResult = AlarmScheduler.dismissAlarm(context)

    fun scheduleReminder(arguments: JSONObject): ToolResult {
        val message = arguments.optString("message", "").trim()
        val intervalMinutes = arguments.optLong("interval_minutes", 60L)
        val recurring = arguments.optBoolean("recurring", true)
        return AlarmScheduler.scheduleReminder(context, message, intervalMinutes, recurring)
    }

    fun cancelReminders(): ToolResult = AlarmScheduler.cancelReminders(context)

    fun listScheduledTasks(): ToolResult {
        val tasks = ScheduledTasksStore.tasks.value
        if (tasks.isEmpty()) {
            return ToolResult.Success("No active alarms or reminders currently scheduled. You can view or manage them anytime on the Tasks tab.")
        }
        val alarms = tasks.filter { it.type == "alarm" }
        val reminders = tasks.filter { it.type == "reminder" }
        val sb = StringBuilder("Scheduled Tasks in Mr-Red:\n")
        if (alarms.isNotEmpty()) {
            sb.append("\n⏰ Active Alarms:\n")
            alarms.forEach { sb.append("• ").append(it.title).append(" - Note: ").append(it.note).append("\n") }
        }
        if (reminders.isNotEmpty()) {
            sb.append("\n🔔 Upcoming Reminders:\n")
            reminders.forEach {
                val rep = if (it.isRecurring) "Repeating" else "One-time"
                sb.append("• ").append(it.scheduleDisplay).append(": ").append(it.note).append(" [").append(rep).append("]\n")
            }
        }
        sb.append("\nYou can view and manage all of them on the Tasks tab.")
        return ToolResult.Success(sb.toString().trim())
    }

    private fun openNotificationAccess(): ToolResult = runCatching {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        ToolResult.Success("Opened Notification access settings. Choose Mr-Red and grant access if you want it to read notifications.")
    }.getOrElse { ToolResult.Failure("Could not open notification access settings: ${it.message ?: "not available"}") }

    private fun readNotifications(): ToolResult {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )?.split(":")?.any { component ->
            runCatching { android.content.ComponentName.unflattenFromString(component)?.packageName == context.packageName }.getOrDefault(false)
        } == true
        if (!enabled) {
            return ToolResult.Failure("Notification access is off. Ask me to enable notification access, then grant Mr-Red access in Android settings.")
        }
        val records = MrRedNotificationListenerService.recentNotifications()
        if (records.isEmpty()) return ToolResult.Success("Notification access is enabled, but there are no recent notifications to show.")
        return ToolResult.Success("Recent notifications (stored in memory only):\n" + records.joinToString("\n") { "• ${it.app}: ${it.title}${it.text.takeIf(String::isNotBlank)?.let { text -> ": $text" }.orEmpty()}" })
    }

    private fun getNews(arguments: JSONObject, onProgress: (String) -> Unit): ToolResult {
        val query = arguments.optString("query").trim().take(120)
        return runCatching {
            onProgress("Fetching current headlines…")
            val encoded = URLEncoder.encode(query.ifBlank { "top news" }, "UTF-8")
            val xml = requestText("https://news.google.com/rss/search?q=$encoded&hl=en-US&gl=US&ceid=US:en")
            val items = parseRssItems(xml)
            val sources = mutableListOf<SourceLink>()
            val headlines = buildString {
                for (item in items.take(5)) {
                    val title = item.title
                    val link = item.link
                    val date = item.date
                    if (title.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("• ").append(title)
                        if (date.isNotBlank()) append(" (").append(date).append(")")
                        if (link.isNotBlank()) sources.add(SourceLink(title, link))
                    }
                }
            }
            if (headlines.isBlank()) ToolResult.Success("No headlines found for ${query.ifBlank { "top news" }}, try a different topic.")
            else ToolResult.Success("Current headlines${query.takeIf(String::isNotBlank)?.let { " about $it" }.orEmpty()} (Google News RSS):\n$headlines", sources)
        }.getOrElse { ToolResult.Failure("News lookup failed: ${it.message ?: "network error"}") }
    }

    companion object {
        internal data class RssItem(val title: String, val link: String, val date: String)

        internal fun parseRssItems(xml: String): List<RssItem> {
            val items = mutableListOf<RssItem>()
            // 1. Safe DocumentBuilderFactory (ignores unsupported features on Android runtime)
            val docParsed = runCatching {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                    runCatching { isXIncludeAware = false }
                    runCatching { isExpandEntityReferences = false }
                }
                val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
                val nodes = document.getElementsByTagName("item")
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i)
                    val children = node.childNodes
                    fun value(tag: String): String = (0 until children.length)
                        .map { children.item(it) }
                        .firstOrNull { it.nodeName.equals(tag, ignoreCase = true) }
                        ?.textContent?.trim().orEmpty()
                    val title = unescapeXml(value("title"))
                    val link = value("link")
                    val pubDate = value("pubDate")
                    if (title.isNotBlank()) {
                        items.add(RssItem(title, link, pubDate))
                    }
                }
                items.isNotEmpty()
            }.getOrDefault(false)

            if (docParsed && items.isNotEmpty()) return items

            // 2. Resilient Regex Fallback
            val itemRegex = Regex("<item[ >](.*?)</item>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val titleRegex = Regex("<title>(?:<!\\[CDATA\\[(.*?)\\]\\]>|(.*?))</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val linkRegex = Regex("<link>(?:<!\\[CDATA\\[(.*?)\\]\\]>|(.*?))</link>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val pubDateRegex = Regex("<pubDate>(?:<!\\[CDATA\\[(.*?)\\]\\]>|(.*?))</pubDate>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

            itemRegex.findAll(xml).forEach { match ->
                val content = match.groupValues[1]
                val title = titleRegex.find(content)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }?.trim().orEmpty()
                val link = linkRegex.find(content)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }?.trim().orEmpty()
                val pubDate = pubDateRegex.find(content)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }?.trim().orEmpty()
                if (title.isNotBlank()) {
                    items.add(RssItem(unescapeXml(title), link, pubDate))
                }
            }
            return items
        }

        internal fun unescapeXml(text: String): String {
            return text.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    private fun requestText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    fun getDeviceVitals(): ToolResult {
        return runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

            val stat = StatFs(Environment.getDataDirectory().path)
            val freeStorageBytes = stat.availableBlocksLong * stat.blockSizeLong
            val totalStorageBytes = stat.blockCountLong * stat.blockSizeLong
            val usedStorageBytes = (totalStorageBytes - freeStorageBytes).coerceAtLeast(0)
            val freeStorageGb = String.format("%.2f GB", freeStorageBytes / (1024.0 * 1024.0 * 1024.0))
            val usedStorageGb = String.format("%.2f GB", usedStorageBytes / (1024.0 * 1024.0 * 1024.0))
            val totalStorageGb = String.format("%.2f GB", totalStorageBytes / (1024.0 * 1024.0 * 1024.0))
            val uptimeSeconds = SystemClock.elapsedRealtime() / 1000
            val uptime = "${uptimeSeconds / 86400}d ${(uptimeSeconds % 86400) / 3600}h ${(uptimeSeconds % 3600) / 60}m"

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val availRamMb = memInfo.availMem / (1024 * 1024)
            val totalRamMb = memInfo.totalMem / (1024 * 1024)

            val summary = "Device status: Battery: ${if (batteryPct >= 0) "$batteryPct%" else "unavailable"} | RAM available: ${availRamMb}MB / ${totalRamMb}MB | Storage used: $usedStorageGb / $totalStorageGb (free: $freeStorageGb) | Uptime: $uptime"
            ToolResult.Success(summary)
        }.getOrElse { error ->
            ToolResult.Failure("Failed to read device vitals: ${error.message}")
        }
    }

    fun getWeather(arguments: JSONObject, onProgress: (String) -> Unit): ToolResult {
        val city = arguments.optString("city").trim()
        if (city.isEmpty() || city.length > 40) {
            return ToolResult.Failure("city must be between 1 and 40 characters")
        }
        return runCatching {
            onProgress("Querying location for $city...")
            val encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8.name())
            val locationJson = requestJson(
                "https://geocoding-api.open-meteo.com/v1/search?name=$encodedCity&count=1&language=en&format=json"
            )
            val location = locationJson.optJSONArray("results")?.optJSONObject(0)
                ?: return ToolResult.Failure("City '$city' not found")
            val latitude = location.getDouble("latitude")
            val longitude = location.getDouble("longitude")
            val locationName = listOfNotNull(
                location.optString("name").takeIf(String::isNotBlank),
                location.optString("admin1").takeIf(String::isNotBlank),
                location.optString("country").takeIf(String::isNotBlank)
            ).distinct().joinToString(", ")

            onProgress("Fetching weather for $locationName...")
            val weather = requestJson(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$latitude&longitude=$longitude" +
                    "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=3"
            )
            val current = weather.getJSONObject("current")
            val daily = weather.getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val highs = daily.getJSONArray("temperature_2m_max")
            val lows = daily.getJSONArray("temperature_2m_min")
            val precipitationProbabilities = daily.getJSONArray("precipitation_probability_max")
            val forecast = buildString {
                for (index in 0 until dates.length()) {
                    if (index > 0) append("; ")
                    append(dates.getString(index))
                    append(' ')
                    append(weatherDescription(codes.getInt(index)))
                    append(", ")
                    append(formatTemperature(lows.getDouble(index)))
                    append(" - ")
                    append(formatTemperature(highs.getDouble(index)))
                    append(", Rain: ")
                    append(precipitationProbabilities.getInt(index))
                    append('%')
                }
            }
            ToolResult.Success(
                "Weather in $locationName: Current: ${formatTemperature(current.getDouble("temperature_2m"))}" +
                    " (Feels like ${formatTemperature(current.getDouble("apparent_temperature"))}), " +
                    "${weatherDescription(current.getInt("weather_code"))}, " +
                    "Wind: ${current.getDouble("wind_speed_10m").toInt()} km/h. " +
                    "3-Day Forecast: $forecast."
            )
        }.getOrElse { error ->
            ToolResult.Failure("Weather lookup failed: ${error.message ?: "Network error"}")
        }
    }

    private fun webSearch(arguments: JSONObject, onProgress: (String) -> Unit): ToolResult {
        val query = arguments.optString("query").trim()
        if (query.isEmpty() || query.length > 240) {
            return ToolResult.Failure("query must be between 1 and 240 characters")
        }
        val apiKey = BuildConfig.TAVILY_API_KEY.trim()

        return if (apiKey.isNotEmpty()) {
            runCatching {
                onProgress("Searching web: $query")
                val response = postJson(
                    url = "https://api.tavily.com/search",
                    body = JSONObject()
                        .put("query", query)
                        .put("search_depth", "basic")
                        .put("max_results", 3)
                        .put("include_answer", false)
                        .toString(),
                    bearerToken = apiKey
                )
                val results = response.optJSONArray("results")
                if (results == null || results.length() == 0) {
                    return ToolResult.Success("No search results found.")
                }
                val resultCount = minOf(results.length(), 3)
                val sources = buildList {
                    for (index in 0 until resultCount) {
                        val result = results.optJSONObject(index) ?: continue
                        val url = result.optString("url").trim()
                        if (url.isNotEmpty()) {
                            add(SourceLink(result.optString("title", "Result ${index + 1}"), url))
                        }
                    }
                }
                val compactResults = buildString {
                    for (index in 0 until resultCount) {
                        val result = results.optJSONObject(index) ?: continue
                        if (isNotEmpty()) append("\n\n")
                        append("[${index + 1}] ")
                        append(result.optString("title", "No title"))
                        append("\nSummary: ")
                        append(result.optString("content").replace(Regex("\\s+"), " ").take(240))
                    }
                }
                ToolResult.Success(
                    content = "Web search results for '$query':\n$compactResults",
                    sources = sources
                )
            }.getOrElse { error ->
                fallbackDuckDuckGoSearch(query, onProgress)
            }
        } else {
            fallbackDuckDuckGoSearch(query, onProgress)
        }
    }

    private fun fallbackDuckDuckGoSearch(query: String, onProgress: (String) -> Unit): ToolResult {
        onProgress("Searching the web for: $query")
        val instantAnswer = runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val json = requestJson("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
            val heading = json.optString("Heading")
            val abstractText = json.optString("AbstractText")
            val abstractUrl = json.optString("AbstractURL")
            if (abstractText.isNotBlank()) {
                ToolResult.Success("Search result: $abstractText", if (abstractUrl.isNotBlank()) listOf(SourceLink(heading.ifBlank { "DuckDuckGo" }, abstractUrl)) else emptyList())
            } else null
        }.getOrNull()
        if (instantAnswer != null) return instantAnswer

        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val html = requestText("https://html.duckduckgo.com/html/?q=$encoded")
            val links = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.IGNORE_CASE)
                .findAll(html).take(5).toList()
            val results = links.mapNotNull { match ->
                val href = match.groupValues[1].replace("&amp;", "&")
                val title = Html.fromHtml(match.groupValues[2], Html.FROM_HTML_MODE_LEGACY).toString().trim()
                val decodedUrl = runCatching {
                    val uri = Uri.parse(if (href.startsWith("//")) "https:$href" else href)
                    uri.getQueryParameter("uddg") ?: uri.toString()
                }.getOrDefault(href)
                if (title.isBlank() || !decodedUrl.startsWith("http")) null else title to decodedUrl
            }
            if (results.isEmpty()) return@runCatching ToolResult.Success("No web results were returned for '$query'.")
            val excerpts = Regex("<(?:a|div)[^>]*class=\"result__snippet\"[^>]*>(.*?)</(?:a|div)>", RegexOption.IGNORE_CASE)
                .findAll(html).take(5).map { Html.fromHtml(it.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim() }.toList()
            val sources = results.map { (title, url) -> SourceLink(title, url) }
            val body = results.mapIndexed { index, (title, _) ->
                "[${index + 1}] $title" + excerpts.getOrNull(index)?.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
            }.joinToString("\n\n")
            ToolResult.Success("Web search results for '$query':\n$body", sources)
        }.getOrElse { error -> ToolResult.Failure("Web search failed: ${error.message ?: "Network error"}") }
    }

    private fun requestJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; MrRed)")
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(url: String, body: String, bearerToken: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; MrRed)")
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write(body)
            }
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun formatTemperature(value: Double): String = "${value.toInt()}°C"

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "Clear sky"
        1, 2 -> "Mainly clear / partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        61, 63, 65 -> "Rain"
        71, 73, 75 -> "Snow"
        80, 81, 82 -> "Rain showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Weather code $code"
    }
}

object FunctionCalling {
    const val maxToolCalls = 3

    val systemInstruction = """
        You are Mr-Red, an offline phone assistant. Use tools when needed by replying ONLY with JSON (no extra text):
        {"tool":"tool_name","arguments":{...}}
        If no tool is needed, respond directly to the user.
        Available tools:
        %s
    """.trimIndent()

    fun systemInstruction(toolInstruction: String): String =
        systemInstruction.format(toolInstruction)

    fun parseToolCall(modelText: String): ToolCall? {
        val rawText = modelText.substringAfterLast("</think>", modelText).trim()
        val textWithoutMarkdown = rawText
            .replace("```json", "")
            .replace("```", "")
            .trim()

        val candidates = extractJsonCandidates(textWithoutMarkdown)
        for (candidate in candidates) {
            val toolCall = runCatching {
                if (candidate.startsWith("[")) {
                    val arr = JSONArray(candidate)
                    if (arr.length() > 0) {
                        val first = arr.getJSONObject(0)
                        val toolName = first.optString("name").ifEmpty { first.optString("tool") }
                        if (toolName.isNotEmpty()) {
                            val args = first.optJSONObject("arguments") ?: JSONObject()
                            ToolCall(toolName, args)
                        } else null
                    } else null
                } else if (candidate.startsWith("{")) {
                    val obj = JSONObject(candidate)
                    val toolName = obj.optString("name").ifEmpty { obj.optString("tool") }
                    if (toolName.isNotEmpty()) {
                        val args = obj.optJSONObject("arguments") ?: JSONObject()
                        ToolCall(toolName, args)
                    } else null
                } else null
            }.getOrNull()

            if (toolCall != null) {
                return toolCall
            }
        }
        return null
    }

    private fun extractJsonCandidates(text: String): List<String> {
        val candidates = mutableListOf<String>()
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '{' || ch == '[') {
                val openCh = ch
                val closeCh = if (ch == '{') '}' else ']'
                var depth = 0
                var inString = false
                var escape = false
                for (j in i until text.length) {
                    val c = text[j]
                    if (escape) {
                        escape = false
                        continue
                    }
                    if (c == '\\') {
                        escape = true
                        continue
                    }
                    if (c == '"') {
                        inString = !inString
                        continue
                    }
                    if (!inString) {
                        if (c == openCh) {
                            depth++
                        } else if (c == closeCh) {
                            depth--
                            if (depth == 0) {
                                candidates.add(text.substring(i, j + 1))
                                break
                            }
                        }
                    }
                }
            }
        }
        return candidates
    }

    fun shouldDeferToolRendering(streamedText: String): Boolean {
        val text = streamedText
            .substringAfterLast("</think>", streamedText)
            .trimStart()
        if (text.isEmpty()) return true
        if (text.startsWith("[") || text.startsWith("{") || text.startsWith("`")) {
            return true
        }
        return false
    }
}
