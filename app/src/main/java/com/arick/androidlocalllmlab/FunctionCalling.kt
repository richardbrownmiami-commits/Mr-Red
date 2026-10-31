package com.arick.androidlocalllmlab

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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

    private fun openSettings(arguments: JSONObject): ToolResult {
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

    private fun openApp(arguments: JSONObject): ToolResult {
        val appName = arguments.optString("app_name").trim().lowercase()
        if (appName.isEmpty()) return ToolResult.Failure("app_name cannot be empty")

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

    private fun sendMessage(arguments: JSONObject): ToolResult {
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

    private fun makeCall(arguments: JSONObject): ToolResult {
        val phoneNumber = arguments.optString("phone_number").trim()
        if (phoneNumber.isEmpty()) return ToolResult.Failure("phone_number cannot be empty")

        return runCatching {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phoneNumber)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Dialer opened with phone number $phoneNumber.")
        }.getOrElse { error ->
            ToolResult.Failure("Failed to open dialer: ${error.message}")
        }
    }

    private fun setTimer(arguments: JSONObject): ToolResult {
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

    private fun getDeviceVitals(): ToolResult {
        return runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

            val stat = StatFs(Environment.getDataDirectory().path)
            val freeStorageMb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
            val freeStorageGb = String.format("%.2f GB", freeStorageMb / 1024.0)

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val availRamMb = memInfo.availMem / (1024 * 1024)
            val totalRamMb = memInfo.totalMem / (1024 * 1024)

            val summary = "Device Vitals: Battery: $batteryPct% | Free Storage: $freeStorageGb | Available RAM: ${availRamMb}MB / ${totalRamMb}MB"
            ToolResult.Success(summary)
        }.getOrElse { error ->
            ToolResult.Failure("Failed to read device vitals: ${error.message}")
        }
    }

    private fun getWeather(arguments: JSONObject, onProgress: (String) -> Unit): ToolResult {
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
        return runCatching {
            onProgress("Searching DuckDuckGo for: $query")
            val encoded = URLEncoder.encode(query, "UTF-8")
            val json = requestJson("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
            val heading = json.optString("Heading")
            val abstractText = json.optString("AbstractText")
            val abstractUrl = json.optString("AbstractURL")

            if (abstractText.isNotEmpty()) {
                val sources = if (abstractUrl.isNotEmpty()) listOf(SourceLink(heading.ifEmpty { "DuckDuckGo" }, abstractUrl)) else emptyList()
                ToolResult.Success("Search result: $abstractText", sources)
            } else {
                val related = json.optJSONArray("RelatedTopics")
                val firstTopic = related?.optJSONObject(0)?.optString("Text") ?: ""
                if (firstTopic.isNotEmpty()) {
                    ToolResult.Success("Search result: $firstTopic")
                } else {
                    ToolResult.Success("Web search completed for '$query', but no instant summary was found.")
                }
            }
        }.getOrElse { error ->
            ToolResult.Failure("Web search failed: ${error.message ?: "Network error"}")
        }
    }

    private fun requestJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
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
        You have access to Android device automation tools. If needed, call a tool by outputting JSON only:
        {"tool":"tool_name","arguments":{...}}
        If no tool is needed, respond directly to the user.
        Available tools:
        %s
    """.trimIndent()

    fun systemInstruction(toolInstruction: String): String =
        systemInstruction.format(toolInstruction)

    fun parseToolCall(modelText: String): ToolCall? {
        val rawText = modelText.substringAfterLast("</think>", modelText)
        val text = rawText
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return null
        }
        return runCatching {
            val json = JSONObject(text)
            val toolName = json.optString("tool").ifEmpty { json.optString("name") }
            if (toolName.isEmpty()) return null
            ToolCall(
                name = toolName,
                arguments = json.optJSONObject("arguments") ?: JSONObject()
            )
        }.getOrNull()
    }

    fun shouldDeferToolRendering(streamedText: String): Boolean {
        val text = streamedText
            .substringAfterLast("</think>", streamedText)
            .trimStart()
        val toolPrefix = "{\"tool"
        val namePrefix = "{\"name"
        return text.isEmpty() || toolPrefix.startsWith(text) || text.startsWith(toolPrefix) ||
                namePrefix.startsWith(text) || text.startsWith(namePrefix)
    }
}
