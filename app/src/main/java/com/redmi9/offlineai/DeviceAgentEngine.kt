package com.redmi9.offlineai

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import java.util.Locale
import java.util.regex.Pattern

enum class PendingSlotType {
    WHATSAPP,
    SMS
}

data class PendingSlot(
    val type: PendingSlotType,
    val recipient: String
)

data class DeviceAgentResult(
    val handled: Boolean,
    val reply: String,
    val candidateOptions: List<String> = emptyList()
)

object DeviceAgentEngine {

    var lastCallIntent: Intent? = null
    var lastWhatsAppIntent: Intent? = null
    var lastSmsIntent: Intent? = null
    var lastLaunchIntent: Intent? = null
    var lastAlarmIntent: Intent? = null
    var lastTimerIntent: Intent? = null
    var lastSearchIntent: Intent? = null
    var lastMapIntent: Intent? = null
    var lastShareIntent: Intent? = null

    var pendingSlot: PendingSlot? = null

    fun clearPendingSlot() {
        pendingSlot = null
    }

    fun process(userInput: String, context: Context): DeviceAgentResult? {
        val trimmed = userInput.trim()
        if (trimmed.isEmpty()) return null

        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Cancel ongoing action
        if (lower == "cancel" || lower == "stop" || lower == "never mind" || lower == "abort" || lower == "clear") {
            if (pendingSlot != null) {
                clearPendingSlot()
                return DeviceAgentResult(
                    handled = true,
                    reply = "Action cancelled. What would you like me to do next?",
                    candidateOptions = listOf("Check battery", "Check storage", "Device overview")
                )
            }
        }

        // 2. Pending slot continuation (e.g. user typed recipient first, now providing body)
        val activeSlot = pendingSlot
        if (activeSlot != null) {
            clearPendingSlot()
            val messageText = trimmed
            return when (activeSlot.type) {
                PendingSlotType.WHATSAPP -> {
                    val intent = createWhatsAppIntent(activeSlot.recipient, messageText)
                    lastWhatsAppIntent = intent
                    DeviceAgentResult(
                        handled = true,
                        reply = "I've drafted your WhatsApp message for ${activeSlot.recipient}:\n\n“$messageText”\n\nTap below to open WhatsApp and send:",
                        candidateOptions = listOf("🟢 Open WhatsApp & Send", "Cancel")
                    )
                }
                PendingSlotType.SMS -> {
                    val intent = createSmsIntent(activeSlot.recipient, messageText)
                    lastSmsIntent = intent
                    DeviceAgentResult(
                        handled = true,
                        reply = "I've prepared your SMS text for ${activeSlot.recipient}:\n\n“$messageText”\n\nTap below to open your messaging app:",
                        candidateOptions = listOf("💬 Open SMS Draft", "Cancel")
                    )
                }
            }
        }

        // 3. Vitals & Living Phone Status
        if (isBatteryQuery(lower)) {
            val reply = MobileVitalsManager.formatBatterySpoken(context)
            return DeviceAgentResult(
                handled = true,
                reply = reply,
                candidateOptions = listOf("Check storage", "Check RAM", "Device overview")
            )
        }

        if (isStorageQuery(lower)) {
            val reply = MobileVitalsManager.formatStorageSpoken()
            return DeviceAgentResult(
                handled = true,
                reply = reply,
                candidateOptions = listOf("Check battery", "Check RAM", "Device overview")
            )
        }

        if (isRamQuery(lower)) {
            val reply = MobileVitalsManager.formatRamSpoken(context)
            return DeviceAgentResult(
                handled = true,
                reply = reply,
                candidateOptions = listOf("Check battery", "Check storage", "Device overview")
            )
        }

        if (isTimeQuery(lower)) {
            val reply = MobileVitalsManager.formatTimeSpoken()
            return DeviceAgentResult(
                handled = true,
                reply = reply,
                candidateOptions = listOf("Check battery", "Device overview")
            )
        }

        if (isOverviewQuery(lower)) {
            val reply = MobileVitalsManager.formatSelfOverview(context)
            return DeviceAgentResult(
                handled = true,
                reply = reply,
                candidateOptions = listOf("Check battery", "Check storage", "Check RAM")
            )
        }

        // 4. Mathematical Calculations
        val mathResult = tryEvaluateMath(trimmed)
        if (mathResult != null) {
            return DeviceAgentResult(
                handled = true,
                reply = mathResult,
                candidateOptions = emptyList()
            )
        }

        // 5. Alarms
        val alarmMatch = parseAlarmCommand(trimmed)
        if (alarmMatch != null) {
            val (hour, minute, label) = alarmMatch
            val intent = createAlarmIntent(hour, minute, label)
            lastAlarmIntent = intent
            val displayTime = formatTime12h(hour, minute)
            val labelNotice = if (label.isNotBlank()) " with label “$label”" else ""
            return DeviceAgentResult(
                handled = true,
                reply = "I've prepared an alarm for $displayTime$labelNotice. Tap below to confirm and set it in your Clock app:",
                candidateOptions = listOf("⏰ Set Alarm for $displayTime", "Cancel")
            )
        }

        // 6. Timers
        val timerMatch = parseTimerCommand(trimmed)
        if (timerMatch != null) {
            val (seconds, display) = timerMatch
            val intent = createTimerIntent(seconds)
            lastTimerIntent = intent
            return DeviceAgentResult(
                handled = true,
                reply = "I've prepared a $display timer. Tap below to launch it in your Clock app:",
                candidateOptions = listOf("⏳ Start Timer ($display)", "Cancel")
            )
        }

        // 7. Maps & Navigation
        val mapMatch = parseMapCommand(trimmed)
        if (mapMatch != null) {
            val intent = createMapIntent(mapMatch)
            lastMapIntent = intent
            return DeviceAgentResult(
                handled = true,
                reply = "I've located \"$mapMatch\". Tap below to open it in Google Maps:",
                candidateOptions = listOf("🗺️ View on Map: $mapMatch", "Cancel")
            )
        }

        // 8. Web Search
        val searchMatch = parseSearchCommand(trimmed)
        if (searchMatch != null) {
            val intent = createSearchIntent(searchMatch)
            lastSearchIntent = intent
            return DeviceAgentResult(
                handled = true,
                reply = "I've prepared a web search for \"$searchMatch\". Tap below to open in your browser:",
                candidateOptions = listOf("🔍 Search: $searchMatch", "Cancel")
            )
        }

        // 9. Share Text
        val shareMatch = parseShareCommand(trimmed)
        if (shareMatch != null) {
            val intent = createShareIntent(shareMatch)
            lastShareIntent = intent
            return DeviceAgentResult(
                handled = true,
                reply = "I've prepared to share your text:\n\n“$shareMatch”\n\nTap below to choose an app to share with:",
                candidateOptions = listOf("📤 Share Text", "Cancel")
            )
        }

        // 10. WhatsApp Messaging
        val waMatch = parseWhatsAppCommand(trimmed)
        if (waMatch != null) {
            val (recipient, body) = waMatch
            return if (body.isNotBlank()) {
                val intent = createWhatsAppIntent(recipient, body)
                lastWhatsAppIntent = intent
                DeviceAgentResult(
                    handled = true,
                    reply = "I've prepared your WhatsApp message for $recipient:\n\n“$body”\n\nTap below to open WhatsApp with this message ready:",
                    candidateOptions = listOf("🟢 Open WhatsApp & Send", "Cancel")
                )
            } else {
                pendingSlot = PendingSlot(PendingSlotType.WHATSAPP, recipient)
                DeviceAgentResult(
                    handled = true,
                    reply = "What message would you like me to send to $recipient on WhatsApp?",
                    candidateOptions = listOf("Cancel")
                )
            }
        }

        // 11. SMS Messaging
        val smsMatch = parseSmsCommand(trimmed)
        if (smsMatch != null) {
            val (recipient, body) = smsMatch
            return if (body.isNotBlank()) {
                val intent = createSmsIntent(recipient, body)
                lastSmsIntent = intent
                DeviceAgentResult(
                    handled = true,
                    reply = "Here is your SMS text draft for $recipient:\n\n“$body”\n\nTap below to send via your SMS app:",
                    candidateOptions = listOf("💬 Open SMS Draft", "Cancel")
                )
            } else {
                pendingSlot = PendingSlot(PendingSlotType.SMS, recipient)
                DeviceAgentResult(
                    handled = true,
                    reply = "What would you like me to text to $recipient?",
                    candidateOptions = listOf("Cancel")
                )
            }
        }

        // 12. Phone Calls
        val callMatch = parseCallCommand(trimmed)
        if (callMatch != null) {
            val recipient = callMatch
            val intent = createCallIntent(recipient)
            lastCallIntent = intent
            return DeviceAgentResult(
                handled = true,
                reply = "I'm ready to dial $recipient for you. Tap below to launch your phone dialer:",
                candidateOptions = listOf("📞 Call $recipient", "Cancel")
            )
        }

        // 13. Open / Launch App
        val appMatch = parseAppLaunchCommand(trimmed)
        if (appMatch != null) {
            val appCandidate = findAppLaunchIntent(context, appMatch)
            return if (appCandidate != null) {
                lastLaunchIntent = appCandidate.second
                DeviceAgentResult(
                    handled = true,
                    reply = "Opening ${appCandidate.first} for you...",
                    candidateOptions = listOf("🚀 Launch ${appCandidate.first}", "Cancel")
                )
            } else {
                DeviceAgentResult(
                    handled = true,
                    reply = "I looked for an app named \"$appMatch\" on my system, but couldn't find a matching installed app.",
                    candidateOptions = emptyList()
                )
            }
        }

        return null
    }

    private fun isBatteryQuery(lower: String): Boolean =
        lower.contains("battery") || lower.contains("how much charge") || lower.contains("my charge")

    private fun isStorageQuery(lower: String): Boolean =
        lower.contains("storage") || lower.contains("space left") || lower.contains("disk space") || lower.contains("free space")

    private fun isRamQuery(lower: String): Boolean =
        lower.contains("my ram") || lower.contains("ram left") || lower.contains("memory left") || lower == "ram" || lower == "memory"

    private fun isTimeQuery(lower: String): Boolean =
        lower.contains("what time") || lower.contains("tell me time") || lower.contains("current time") ||
            lower.contains("today's date") || lower.contains("what date") || lower == "time" || lower == "clock"

    private fun isOverviewQuery(lower: String): Boolean =
        lower.contains("who are you") || lower.contains("how are you") || lower.contains("device status") ||
            lower.contains("phone status") || lower == "vitals" || lower == "status" || lower.contains("mr red")

    private fun parseAlarmCommand(text: String): Triple<Int, Int, String>? {
        val p = Pattern.compile("^(?:set\\s+)?(?:an\\s+)?alarm(?:\\s+for)?\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?(?:\\s+(?:saying|labeled|label|called|for)\\s+(.+))?$", Pattern.CASE_INSENSITIVE)
        val m = p.matcher(text.trim())
        if (m.matches()) {
            var h = m.group(1)?.toIntOrNull() ?: return null
            val min = m.group(2)?.toIntOrNull() ?: 0
            val ampm = (m.group(3) ?: "").lowercase(Locale.ROOT)
            val label = (m.group(4) ?: "").trim()
            if (ampm == "pm" && h < 12) h += 12
            else if (ampm == "am" && h == 12) h = 0
            if (h in 0..23 && min in 0..59) {
                return Triple(h, min, label)
            }
        }
        return null
    }

    private fun parseTimerCommand(text: String): Pair<Int, String>? {
        val p = Pattern.compile("^(?:set\\s+)?(?:a\\s+)?timer(?:\\s+for)?\\s+(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)$", Pattern.CASE_INSENSITIVE)
        val m = p.matcher(text.trim())
        if (m.matches()) {
            val amount = m.group(1)?.toIntOrNull() ?: return null
            val unit = (m.group(2) ?: "").lowercase(Locale.ROOT)
            val seconds = when {
                unit.contains("sec") -> amount
                unit.contains("min") -> amount * 60
                unit.contains("hour") || unit.contains("hr") -> amount * 3600
                else -> return null
            }
            val display = when {
                unit.contains("sec") -> "${amount}s"
                unit.contains("min") -> "${amount}m"
                else -> "${amount}h"
            }
            return Pair(seconds, display)
        }
        return null
    }

    private fun parseMapCommand(text: String): String? {
        val p = Pattern.compile("^(?:map|navigate\\s+to|directions\\s+to|where\\s+is)\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        val m = p.matcher(text.trim())
        if (m.matches()) {
            return (m.group(1) ?: "").trim()
        }
        return null
    }

    private fun parseSearchCommand(text: String): String? {
        val p = Pattern.compile("^(?:search(?:\\s+for|\\s+web)?|google)\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        val m = p.matcher(text.trim())
        if (m.matches()) {
            return (m.group(1) ?: "").trim()
        }
        return null
    }

    private fun parseShareCommand(text: String): String? {
        val p = Pattern.compile("^(?:share|send\\s+text)\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        val m = p.matcher(text.trim())
        if (m.matches()) {
            return (m.group(1) ?: "").trim()
        }
        return null
    }

    private fun parseWhatsAppCommand(text: String): Pair<String, String>? {
        val p1 = Pattern.compile("^(?:send\\s+)?whatsapp(?:\\s+to)?\\s+([^\\s:]+)(?:\\s*[:]\\s*|\\s+(?:saying|that|with\\s+message|msg)\\s+)(.+)$", Pattern.CASE_INSENSITIVE)
        val m1 = p1.matcher(text)
        if (m1.matches()) {
            return Pair(m1.group(1) ?: "", (m1.group(2) ?: "").trim())
        }
        val p2 = Pattern.compile("^(?:send\\s+)?whatsapp(?:\\s+to)?\\s+([^\\s:]+)$", Pattern.CASE_INSENSITIVE)
        val m2 = p2.matcher(text)
        if (m2.matches()) {
            return Pair(m2.group(1) ?: "", "")
        }
        return null
    }

    private fun parseSmsCommand(text: String): Pair<String, String>? {
        val p1 = Pattern.compile("^(?:send\\s+)?(?:sms|text)(?:\\s+to)?\\s+([^\\s:]+)(?:\\s*[:]\\s*|\\s+(?:saying|that|with\\s+message|msg)\\s+)(.+)$", Pattern.CASE_INSENSITIVE)
        val m1 = p1.matcher(text)
        if (m1.matches()) {
            return Pair(m1.group(1) ?: "", (m1.group(2) ?: "").trim())
        }
        val p2 = Pattern.compile("^(?:send\\s+)?(?:sms|text)(?:\\s+to)?\\s+([^\\s:]+)$", Pattern.CASE_INSENSITIVE)
        val m2 = p2.matcher(text)
        if (m2.matches()) {
            return Pair(m2.group(1) ?: "", "")
        }
        return null
    }

    private fun parseCallCommand(text: String): String? {
        val p = Pattern.compile("^(?:call|dial|phone)\\s+([^\\s:]+)$", Pattern.CASE_INSENSITIVE)
        val m = p.matcher(text)
        if (m.matches()) {
            return m.group(1) ?: ""
        }
        return null
    }

    private fun parseAppLaunchCommand(text: String): String? {
        val p = Pattern.compile("^(?:open|launch)(?:\\s+the)?\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        val m = p.matcher(text)
        if (m.matches()) {
            val candidate = (m.group(1) ?: "").trim()
            if (candidate.isNotEmpty() && !candidate.startsWith("whatsapp") && !candidate.startsWith("sms")) {
                return candidate
            }
        }
        return null
    }

    private fun createAlarmIntent(hour: Int, minute: Int, message: String): Intent {
        return Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message.ifBlank { "Mr-Red Alarm" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun createTimerIntent(seconds: Int): Intent {
        return Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Mr-Red Timer")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun createMapIntent(place: String): Intent {
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(place))
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun createSearchIntent(query: String): Intent {
        return Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun createShareIntent(text: String): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return Intent.createChooser(sendIntent, "Share with").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun createWhatsAppIntent(target: String, message: String): Intent {
        val cleanDigits = target.replace(Regex("[^0-9]"), "")
        return if (cleanDigits.length >= 7) {
            val url = "https://api.whatsapp.com/send?phone=" + cleanDigits + "&text=" + Uri.encode(message)
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_TEXT, message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    private fun createSmsIntent(target: String, message: String): Intent {
        val cleanTarget = target.replace(Regex("[^0-9+]"), "")
        val uri = if (cleanTarget.isNotEmpty()) Uri.parse("smsto:$cleanTarget") else Uri.parse("smsto:")
        return Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun createCallIntent(target: String): Intent {
        val cleanTarget = target.replace(Regex("[^0-9+]"), "")
        val uri = if (cleanTarget.isNotEmpty()) Uri.parse("tel:$cleanTarget") else Uri.parse("tel:")
        return Intent(Intent.ACTION_DIAL, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun formatTime12h(hour: Int, minute: Int): String {
        val period = if (hour >= 12) "PM" else "AM"
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return String.format(Locale.US, "%02d:%02d %s", h12, minute, period)
    }

    private fun findAppLaunchIntent(context: Context, appNameQuery: String): Pair<String, Intent>? {
        val pm = context.packageManager
        val queryLower = appNameQuery.trim().lowercase(Locale.ROOT)

        val wellKnown = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "camera" to "com.android.camera",
            "settings" to "com.android.settings",
            "clock" to "com.google.android.deskclock",
            "calculator" to "com.miui.calculator",
            "maps" to "com.google.android.apps.maps",
            "play store" to "com.android.vending",
            "playstore" to "com.android.vending",
            "gmail" to "com.google.android.gm"
        )

        wellKnown[queryLower]?.let { pkg ->
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return Pair(queryLower.replaceFirstChar { it.uppercase() }, intent)
            }
        }

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = pm.queryIntentActivities(mainIntent, 0)
        for (info in resolveList) {
            val label = info.loadLabel(pm).toString()
            if (label.lowercase(Locale.ROOT).contains(queryLower) || queryLower.contains(label.lowercase(Locale.ROOT))) {
                val intent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    return Pair(label, intent)
                }
            }
        }
        return null
    }

    private fun tryEvaluateMath(text: String): String? {
        val t = text.trim()
        val calcPattern = Pattern.compile("^(?:calculate|calc|what\\s+is|solve|evaluate)\\s+(.+)$", Pattern.CASE_INSENSITIVE)
        val mCalc = calcPattern.matcher(t)
        val candidate = if (mCalc.matches()) (mCalc.group(1) ?: "").trim() else t

        val pctPattern = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*%\\s*(?:of|\\*)\\s*(\\d+(?:\\.\\d+)?)$", Pattern.CASE_INSENSITIVE)
        val mPct = pctPattern.matcher(candidate)
        if (mPct.matches()) {
            val p = mPct.group(1)?.toDoubleOrNull() ?: return null
            val base = mPct.group(2)?.toDoubleOrNull() ?: return null
            val res = (p / 100.0) * base
            val resStr = if (res % 1.0 == 0.0) res.toLong().toString() else String.format(Locale.US, "%.2f", res)
            return "🔢 $p% of $base is $resStr."
        }

        val clean = candidate.replace("x", "*", ignoreCase = true)
            .replace("X", "*")
            .replace("×", "*")
            .replace("÷", "/")

        if (!clean.matches(Regex("^[\\d\\s\\+\\-\\*\\/\\(\\)\\.\\^]+$"))) return null
        if (!clean.any { it in "+-*/^" }) return null

        val result = evaluateArithmetic(clean) ?: return null
        val formatted = if (result % 1.0 == 0.0) result.toLong().toString() else String.format(Locale.US, "%.2f", result)
        return "🔢 The result of $candidate is $formatted."
    }

    private fun evaluateArithmetic(expr: String): Double? {
        return runCatching {
            val tokens = mutableListOf<String>()
            val matcher = Pattern.compile("\\d+(?:\\.\\d+)?|[+\\-*/()^]").matcher(expr)
            while (matcher.find()) {
                tokens.add(matcher.group())
            }
            if (tokens.isEmpty()) return null

            var pos = 0
            fun peek(): String? = if (pos < tokens.size) tokens[pos] else null
            fun consume(): String? = if (pos < tokens.size) tokens[pos++] else null

            fun parseExpr(): Double {
                var value = 0.0
                fun parseFactor(): Double {
                    val token = peek() ?: throw IllegalArgumentException()
                    if (token == "+") {
                        consume()
                        return parseFactor()
                    }
                    if (token == "-") {
                        consume()
                        return -parseFactor()
                    }
                    if (token == "(") {
                        consume()
                        val sub = parseExpr()
                        if (consume() != ")") throw IllegalArgumentException()
                        return sub
                    }
                    consume()
                    return token.toDouble()
                }

                fun parseTerm(): Double {
                    var v = parseFactor()
                    while (true) {
                        val next = peek()
                        if (next == "*" || next == "/" || next == "^") {
                            val op = consume()
                            val rhs = parseFactor()
                            v = when (op) {
                                "*" -> v * rhs
                                "/" -> if (rhs == 0.0) throw ArithmeticException("Divide by zero") else v / rhs
                                "^" -> Math.pow(v, rhs)
                                else -> v
                            }
                        } else {
                            break
                        }
                    }
                    return v
                }

                value = parseTerm()
                while (true) {
                    val next = peek()
                    if (next == "+" || next == "-") {
                        val op = consume()
                        val rhs = parseTerm()
                        value = if (op == "+") value + rhs else value - rhs
                    } else {
                        break
                    }
                }
                return value
            }

            val ans = parseExpr()
            if (pos != tokens.size) return null
            ans
        }.getOrNull()
    }
}
