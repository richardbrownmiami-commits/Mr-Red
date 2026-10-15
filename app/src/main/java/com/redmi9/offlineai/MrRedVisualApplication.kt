package com.redmi9.offlineai

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView

/**
 * Mr-Red visual layer. It is deliberately implemented as a view skin rather than
 * replacing the functional chat screens: conversations, memory, persona editing,
 * model controls and agent actions keep their existing behavior.
 */
class MrRedVisualApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { skin(activity.window.decorView) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) { }
            override fun onActivityStarted(activity: Activity) { }
            override fun onActivityPaused(activity: Activity) { }
            override fun onActivityStopped(activity: Activity) { }
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) { }
            override fun onActivityDestroyed(activity: Activity) { }
        })
    }

    private fun skin(view: View) {
        view.post { apply(view) }
    }

    private fun apply(view: View) {
        when (view) {
            is ImageButton -> {
                view.background = rounded(EMBER, 14)
                view.setColorFilter(Color.WHITE)
            }
            is Button -> {
                view.setTextColor(Color.WHITE)
                view.background = rounded(EMBER, 12)
            }
            is EditText -> {
                view.setTextColor(TEXT)
                view.setHintTextColor(MUTED)
                view.background = rounded(SURFACE, 14, BORDER)
            }
            is TextView -> {
                if (view.textColors.defaultColor == Color.rgb(0, 255, 136) || view.currentTextColor == Color.rgb(0, 196, 106)) {
                    view.setTextColor(EMBER)
                } else if (view.currentTextColor == Color.rgb(8, 12, 16)) {
                    view.setTextColor(Color.WHITE)
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) apply(view.getChildAt(i))
        }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius * resources.displayMetrics.density
        stroke?.let { setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1), it) }
    }

    companion object {
        private const val EMBER = 0xFFFF3B30.toInt()
        private const val SURFACE = 0xFF0D1219.toInt()
        private const val BORDER = 0xFF273241.toInt()
        private const val TEXT = 0xFFF4F7FB.toInt()
        private const val MUTED = 0xFF8F9AA8.toInt()
    }
}
