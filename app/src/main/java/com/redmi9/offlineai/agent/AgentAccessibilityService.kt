package com.redmi9.offlineai.agent

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * User-opt-in accessibility bridge for agent screen inspection and text-targeted taps.
 * Android only binds this service after the user explicitly enables it in Settings.
 */
class AgentAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Screen state is read on demand; do not collect or transmit event contents.
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    /** Returns visible, accessibility-exposed text for the active window, capped for LLM context. */
    fun readActiveWindow(maxChars: Int = DEFAULT_MAX_CHARS): String {
        val root = rootInActiveWindow ?: return "No active window is available."
        val out = StringBuilder()
        try {
            appendNodeText(root, out, maxChars.coerceIn(256, DEFAULT_MAX_CHARS))
        } finally {
            root.recycle()
        }
        return out.toString().ifBlank { "No readable text found in the active window." }
    }

    fun clickByText(label: String): Boolean {
        val query = label.trim()
        if (query.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        return try {
            findAndClick(root, query.lowercase())
        } finally {
            root.recycle()
        }
    }

    /** Types into the currently focused editable accessibility node. */
    fun typeText(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findEditable(root)
                ?: return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            root.recycle()
        }
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    private fun appendNodeText(node: AccessibilityNodeInfo, out: StringBuilder, limit: Int) {
        if (out.length >= limit) return
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        val value = when {
            text.isNotEmpty() -> text
            description.isNotEmpty() -> description
            else -> ""
        }
        if (value.isNotEmpty() && out.indexOf(value) < 0) {
            if (out.isNotEmpty()) out.append('\n')
            out.append(value.take(limit - out.length))
        }
        for (i in 0 until node.childCount) {
            if (out.length >= limit) break
            val child = node.getChild(i) ?: continue
            try {
                appendNodeText(child, out, limit)
            } finally {
                child.recycle()
            }
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.isEnabled && node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = try { findEditable(child) } finally { child.recycle() }
            if (found != null) return found
        }
        return null
    }

    private fun findAndClick(node: AccessibilityNodeInfo, query: String): Boolean {
        val text = node.text?.toString().orEmpty().lowercase()
        val description = node.contentDescription?.toString().orEmpty().lowercase()
        if (node.isVisibleToUser && node.isEnabled &&
            (text.contains(query) || description.contains(query))) {
            var target: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
            while (target != null) {
                if (target.isClickable && target.isEnabled) {
                    val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    target.recycle()
                    return clicked
                }
                val parent = target.parent
                target.recycle()
                target = parent
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val clicked = try {
                findAndClick(child, query)
            } finally {
                child.recycle()
            }
            if (clicked) return true
        }
        return false
    }

    companion object {
        private const val DEFAULT_MAX_CHARS = 3000
        @Volatile private var activeInstance: AgentAccessibilityService? = null

        fun isEnabledAndConnected(): Boolean = activeInstance != null
        fun current(): AgentAccessibilityService? = activeInstance
    }
}
