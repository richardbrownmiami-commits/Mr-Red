package com.redmi9.offlineai

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import com.redmi9.offlineai.agent.AgentAccessibilityService
import com.redmi9.offlineai.skill.SkillStore
import java.util.Locale

/** Unique Mr-Red shell: signal-core dashboard with live hardware vitals and action routing. */
class MrRedDashboardActivity : Activity() {
    private val bg = Color.rgb(7, 8, 12)
    private val surface = Color.rgb(15, 17, 24)
    private val cardBorder = Color.rgb(35, 41, 54)
    private val red = Color.rgb(255, 52, 76)
    private val green = Color.rgb(46, 204, 113)
    private val amber = Color.rgb(241, 196, 15)
    private val white = Color.rgb(242, 244, 248)
    private val muted = Color.rgb(145, 151, 164)

    private lateinit var statusView: TextView
    private lateinit var batteryView: TextView
    private lateinit var storageView: TextView
    private lateinit var ramView: TextView
    private lateinit var systemSpecView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) refreshDashboard()
    }

    private fun render() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }

        // Header Title
        root.addView(TextView(this).apply {
            text = "MR-RED"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(white)
        })
        root.addView(TextView(this).apply {
            text = "LOCAL SIGNAL CORE  •  OFFLINE AGENT"
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(red)
            setPadding(0, dp(3), 0, dp(14))
        })

        // Core Status Banner
        val core = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(surface)
                setStroke(dp(1), cardBorder)
            }
        }
        core.addView(TextView(this).apply {
            text = "●"
            textSize = 34f
            setTextColor(red)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(29).toFloat()
                setColor(Color.rgb(38, 14, 22))
            }
        }, LinearLayout.LayoutParams(dp(54), dp(54)))

        val coreText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        coreText.addView(TextView(this).apply {
            text = "Living Device Companion"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
        })
        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(3), 0, 0)
        }
        coreText.addView(statusView)
        core.addView(coreText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(core)

        // Live Hardware Vitals Card
        root.addView(TextView(this).apply {
            text = "HARDWARE VITALS"
            textSize = 11f
            letterSpacing = 0.1f
            setTextColor(muted)
            setPadding(0, dp(18), 0, dp(8))
        })

        val vitalsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(surface)
                setStroke(dp(1), cardBorder)
            }
        }

        batteryView = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            setPadding(0, dp(2), 0, dp(6))
        }
        storageView = TextView(this).apply {
            textSize = 13f
            setTextColor(white)
            setPadding(0, dp(2), 0, dp(6))
        }
        ramView = TextView(this).apply {
            textSize = 13f
            setTextColor(white)
            setPadding(0, dp(2), 0, dp(6))
        }
        systemSpecView = TextView(this).apply {
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(2))
        }

        vitalsCard.addView(batteryView)
        vitalsCard.addView(storageView)
        vitalsCard.addView(ramView)
        vitalsCard.addView(systemSpecView)
        root.addView(vitalsCard)

        // Operate Grid
        root.addView(TextView(this).apply {
            text = "OPERATE & CONTROL"
            textSize = 11f
            letterSpacing = 0.1f
            setTextColor(muted)
            setPadding(0, dp(18), 0, dp(8))
        })

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addActionRow(grid, "TALK WITH MR-RED", "Conversations, device vitals, alarms, calls & math", red) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        addActionRow(grid, "AI AGENT TASKS", "Automate Android apps via Accessibility Service", white) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        addActionRow(grid, "SKILL LAB", "Import JSON actions and teach repeatable workflows", white) {
            startActivity(Intent(this, SkillsActivity::class.java))
        }
        addActionRow(grid, "PERSONA & PROMPT", "Customize identity, style, and behavioral boundaries", white) {
            startActivity(Intent(this, AssistantConfigActivity::class.java))
        }
        root.addView(grid)

        // Footer
        root.addView(TextView(this).apply {
            text = "SKILL FORMAT JSON  •  MODEL GGUF  •  TARGET ARMv7 / Android 11"
            textSize = 10f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(8))
        })

        scroll.addView(root)
        setContentView(scroll)
        refreshDashboard()
    }

    private fun addActionRow(parent: LinearLayout, title: String, subtitle: String, titleColor: Int, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(surface)
                setStroke(dp(1), cardBorder)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setTextColor(titleColor)
        })
        row.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(2), 0, 0)
        })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun refreshDashboard() {
        val skills = SkillStore(this).list().size
        val access = if (AgentAccessibilityService.isEnabledAndConnected()) "ACCESSIBILITY ON" else "ACCESSIBILITY OFF"
        statusView.text = "$access  •  $skills skills ready  •  pure offline"

        val b = MobileVitalsManager.getBattery(this)
        val s = MobileVitalsManager.getStorage()
        val r = MobileVitalsManager.getRam(this)

        val chargingBadge = if (b.isCharging) " (Charging via ${b.plugType})" else " (On battery)"
        val tempBadge = if (b.temperatureC > 0) " • ${String.format(Locale.US, "%.1f", b.temperatureC)}°C" else ""
        batteryView.text = "🔋 Battery: ${if (b.percentage >= 0) "${b.percentage}%" else "?"}$chargingBadge$tempBadge"

        storageView.text = "💾 Storage: ${String.format(Locale.US, "%.1f", s.freeGb)} GB free of ${String.format(Locale.US, "%.1f", s.totalGb)} GB total"
        ramView.text = "🧠 RAM: ${String.format(Locale.US, "%.1f", r.availGb)} GB free of ${String.format(Locale.US, "%.1f", r.totalGb)} GB total"
        systemSpecView.text = "📱 Redmi 9 (M2004J19G) • Android 11 (API 30) • armeabi-v7a 32-bit"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
