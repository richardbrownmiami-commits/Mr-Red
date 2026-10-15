package com.redmi9.offlineai

import android.app.Activity
import android.os.Bundle
import android.graphics.Typeface
import android.widget.*

class AssistantConfigActivity : Activity() {
    private lateinit var prompt: EditText
    private lateinit var config: AiConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AiConfiguration(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14, 14, 14, 14) }
        root.addView(TextView(this).apply { text = "Assistant Configuration"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD })
        root.addView(TextView(this).apply { text = "Edit the system prompt used by the assistant. Saved text survives reopening the app."; setPadding(0, 8, 0, 12) })
        prompt = editor(root, "System Prompt", PromptStore.prompt(this, config.prompt))
        root.addView(Button(this).apply { text = "Save system prompt"; setOnClickListener { savePrompt() } })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun editor(root: LinearLayout, title: String, value: String): EditText {
        root.addView(TextView(this).apply { text = title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 10, 0, 4) })
        val e = EditText(this).apply { setText(value); gravity = android.view.Gravity.TOP; minLines = 8; maxLines = 16; setPadding(12, 10, 12, 10) }
        root.addView(e, LinearLayout.LayoutParams(-1, -2))
        return e
    }

    private fun savePrompt() {
        PromptStore.save(this, PromptStore.PROMPT_KEY, prompt.text.toString())
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }
}
