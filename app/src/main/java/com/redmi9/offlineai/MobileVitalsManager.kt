package com.redmi9.offlineai

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BatteryVitals(
    val percentage: Int,
    val isCharging: Boolean,
    val plugType: String,
    val temperatureC: Float
)

data class StorageVitals(
    val totalGb: Double,
    val freeGb: Double,
    val usedGb: Double
)

data class RamVitals(
    val totalGb: Double,
    val availGb: Double,
    val isLowMemory: Boolean
)

object MobileVitalsManager {

    fun getBattery(context: Context): BatteryVitals {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val chargePlug = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val plugType = when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC wall charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB cable"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless charger"
            else -> if (isCharging) "charger" else "battery power"
        }
        val tempRaw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = if (tempRaw > 0) tempRaw / 10.0f else 0.0f
        return BatteryVitals(percent, isCharging, plugType, tempC)
    }

    fun getStorage(): StorageVitals {
        val path = Environment.getDataDirectory()
        val stat = StatFs(path.path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong
        val totalGb = (totalBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0)
        val freeGb = (availableBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0)
        val usedGb = (totalGb - freeGb).coerceAtLeast(0.0)
        return StorageVitals(totalGb, freeGb, usedGb)
    }

    fun getRam(context: Context): RamVitals {
        val act = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        act.getMemoryInfo(mem)
        val totalGb = mem.totalMem / (1024.0 * 1024.0 * 1024.0)
        val availGb = mem.availMem / (1024.0 * 1024.0 * 1024.0)
        return RamVitals(totalGb, availGb, mem.lowMemory)
    }

    fun getTimeAndDate(): String {
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
        val now = Date()
        val uptimeMin = SystemClock.elapsedRealtime() / (1000 * 60)
        val hours = uptimeMin / 60
        val mins = uptimeMin % 60
        val uptimeStr = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
        return "${timeFormat.format(now)} on ${dateFormat.format(now)} (awake for $uptimeStr)"
    }

    fun getNetworkStatus(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "Offline"
        val net = cm.activeNetwork ?: return "Offline"
        val caps = cm.getNetworkCapabilities(net) ?: return "Offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi connected"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular data active"
            else -> "Connected"
        }
    }

    fun getVitalsBrief(context: Context): String {
        val b = getBattery(context)
        val s = getStorage()
        val r = getRam(context)
        val timeStr = getTimeAndDate()
        val netStr = getNetworkStatus(context)
        return buildString {
            append("• Battery: ${if (b.percentage >= 0) "${b.percentage}%" else "Unknown"} (${if (b.isCharging) "charging via ${b.plugType}" else "on battery"})")
            if (b.temperatureC > 0) append(", temp ${String.format(Locale.US, "%.1f", b.temperatureC)}°C")
            append("\n• Storage: ${String.format(Locale.US, "%.1f", s.freeGb)} GB free of ${String.format(Locale.US, "%.1f", s.totalGb)} GB")
            append("\n• RAM: ${String.format(Locale.US, "%.1f", r.availGb)} GB available of ${String.format(Locale.US, "%.1f", r.totalGb)} GB")
            append("\n• Time: $timeStr")
            append("\n• Network: $netStr")
            append("\n• Device: Redmi 9, Android 11 (API 30), ARMv7 32-bit")
        }
    }

    fun formatBatterySpoken(context: Context): String {
        val b = getBattery(context)
        if (b.percentage < 0) return "I can't read my battery sensor right now."
        return buildString {
            append("🔋 My battery is at ${b.percentage}%. ")
            if (b.isCharging) {
                append("I'm happily plugged into a ${b.plugType} and charging up!")
            } else if (b.percentage <= 15) {
                append("I'm running quite low on power—please plug me into a charger soon!")
            } else if (b.percentage <= 30) {
                append("I have enough juice for now, but you might want to find a charger later.")
            } else {
                append("I'm feeling energized on battery power.")
            }
            if (b.temperatureC > 0) {
                append(" My internal temperature is ${String.format(Locale.US, "%.1f", b.temperatureC)}°C.")
            }
        }
    }

    fun formatStorageSpoken(): String {
        val s = getStorage()
        return "💾 I have ${String.format(Locale.US, "%.1f", s.freeGb)} GB of free space left inside me (out of ${String.format(Locale.US, "%.1f", s.totalGb)} GB total). That gives us plenty of room for files and models!"
    }

    fun formatRamSpoken(context: Context): String {
        val r = getRam(context)
        return "🧠 My RAM currently has ${String.format(Locale.US, "%.1f", r.availGb)} GB free out of ${String.format(Locale.US, "%.1f", r.totalGb)} GB total.${if (r.isLowMemory) " I'm running low on working memory, so closing background apps would help." else " My memory is running smoothly."}"
    }

    fun formatTimeSpoken(): String {
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
        val now = Date()
        return "🕒 It's currently ${timeFormat.format(now)} on ${dateFormat.format(now)}."
    }

    fun formatSelfOverview(context: Context): String {
        val b = getBattery(context)
        val s = getStorage()
        val r = getRam(context)
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        val now = Date()
        val netStr = getNetworkStatus(context)
        return """
            📱 I'm Mr-Red, your living Redmi 9! Here is how my hardware is doing right now:

            🔋 Battery: ${if (b.percentage >= 0) "${b.percentage}%" else "?"} (${if (b.isCharging) "charging via ${b.plugType}" else "on battery"}${if (b.temperatureC > 0) ", ${String.format(Locale.US, "%.1f", b.temperatureC)}°C" else ""})
            💾 Storage: ${String.format(Locale.US, "%.1f", s.freeGb)} GB free of ${String.format(Locale.US, "%.1f", s.totalGb)} GB
            🧠 RAM: ${String.format(Locale.US, "%.1f", r.availGb)} GB free of ${String.format(Locale.US, "%.1f", r.totalGb)} GB
            🕒 Clock: ${timeFormat.format(now)}, ${dateFormat.format(now)}
            📡 Network: $netStr
            🤖 System: Android 11 (API 30), ARMv7 32-bit
        """.trimIndent()
    }
}
