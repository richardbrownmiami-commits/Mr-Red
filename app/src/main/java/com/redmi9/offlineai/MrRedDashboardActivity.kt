package com.redmi9.offlineai

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.redmi9.offlineai.agent.AgentAccessibilityService
import com.redmi9.offlineai.skill.SkillStore

/** Unique Mr-Red shell: signal-core dashboard around the existing local chat/agent screens. */
class MrRedDashboardActivity : Activity() {
    private val bg = Color.rgb(7, 8, 12)
    private val surface = Color.rgb(15, 17, 24)
    private val red = Color.rgb(255, 52, 76)
    private val cyan = Color.rgb(57, 226, 210)
    private val white = Color.rgb(242, 244, 248)
    private val muted = Color.rgb(145, 151, 164)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) refreshStatus()
    }

    private lateinit var statusView: TextView

    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(18), dp(22), dp(18), dp(16)) }
        root.addView(TextView(this).apply {
            text = "MR-RED"
            textSize = 30f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.12f; setTextColor(white)
        })
        root.addView(TextView(this).apply {
            text = "LOCAL SIGNAL CORE  •  OFFLINE AGENT"
            textSize = 11f; letterSpacing = 0.08f; setTextColor(red); setPadding(0, dp(3), 0, dp(16))
        })

        val core = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); setBackgroundColor(surface) }
        val orb = TextView(this).apply {
            text = "●"
            textSize = 34f; setTextColor(red); gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(31, 14, 20))
        }
        core.addView(orb, LinearLayout.LayoutParams(dp(58), dp(58)))
        val coreText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        coreText.addView(TextView(this).apply { text = "Signal core"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(white) })
        statusView = TextView(this).apply { textSize = 12f; setTextColor(muted); setPadding(0, dp(4), 0, 0) }
        coreText.addView(statusView)
        core.addView(coreText, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(core)

        root.addView(TextView(this).apply { text = "OPERATE"; textSize = 11f; letterSpacing = 0.1f; setTextColor(muted); setPadding(0, dp(22), 0, dp(8)) })
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addActionRow(grid, "CHAT", "Local conversation + persistent history") { startActivity(Intent(this, MainActivity::class.java)) }
        addActionRow(grid, "AGENT", "Accessibility actions and installed skills") { startActivity(Intent(this, MainActivity::class.java).putExtra("open_agent", true)) }
        addActionRow(grid, "SKILL LAB", "Import JSON workflows and teach repeatable tasks") { startActivity(Intent(this, SkillsActivity::class.java)) }
        addActionRow(grid, "PERSONA", "Identity, behavior, response style and rules") { startActivity(Intent(this, AssistantConfigActivity::class.java)) }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(TextView(this).apply { text = "SKILL FORMAT  JSON  •  MODEL  GGUF  •  TARGET  ARMv7 / Android 11"; textSize = 10f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) })
        setContentView(root)
        refreshStatus()
    }

    private fun addActionRow(parent: LinearLayout, title: String, subtitle: String, action: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(surface); isClickable = true; setOnClickListener { action() } }
        row.addView(TextView(this).apply { text = title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (title == "AGENT") red else white) })
        row.addView(TextView(this).apply { text = subtitle; textSize = 12f; setTextColor(muted); setPadding(0, dp(3), 0, 0) })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }

    private fun refreshStatus() {
        val skills = SkillStore(this).list().size
        val access = if (AgentAccessibilityService.isEnabledAndConnected()) "ACCESS ON" else "ACCESS OFF"
        statusView.text = "$access  •  $skills skills installed  •  data stays on-device"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
