package com.redmi9.offlineai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.widget.*

class AssistantConfigActivity : Activity() {
    private lateinit var name: EditText
    private lateinit var persona: EditText
    private lateinit var style: EditText
    private lateinit var rules: EditText
    private lateinit var prompt: EditText
    private lateinit var config: AiConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AiConfiguration(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14, 14, 14, 14) }
        root.addView(TextView(this).apply { text = "Assistant Persona"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD })
        root.addView(TextView(this).apply {
            text = "Persona, behavior, memory and skills stay on this device. Skills are JSON workflows; arbitrary code is not executed."
            setPadding(0, 8, 0, 12)
        })
        root.addView(Button(this).apply {
            text = "Skill Lab • teach a new workflow"
            setOnClickListener { startActivity(Intent(this@AssistantConfigActivity, SkillsActivity::class.java)) }
        })
        name = editor(root, "Assistant name", PromptStore.get(this, PromptStore.PERSONA_NAME_KEY, "Mr-Red"), 1)
        persona = editor(root, "Personality and role", PromptStore.get(this, PromptStore.PERSONA_KEY, "A capable, honest, privacy-conscious offline Android assistant."), 4)
        style = editor(root, "Response style", PromptStore.get(this, PromptStore.STYLE_KEY, "Clear, direct, practical; explain uncertainty instead of guessing."), 3)
        rules = editor(root, "Behavior rules", PromptStore.get(this, PromptStore.RULES_KEY, "Ask before irreversible actions. Never claim an action succeeded without evidence."), 4)
        prompt = editor(root, "Advanced system instructions", PromptStore.prompt(this, config.prompt), 8)
        root.addView(Button(this).apply { text = "Save persona and instructions"; setOnClickListener { savePersona() } })
        root.addView(Button(this).apply { text = "Reset persona defaults"; setOnClickListener {
            name.setText("Mr-Red")
            persona.setText("A capable, honest, privacy-conscious offline Android assistant.")
            style.setText("Clear, direct, practical; explain uncertainty instead of guessing.")
            rules.setText("Ask before irreversible actions. Never claim an action succeeded without evidence.")
        } })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun editor(root: LinearLayout, title: String, value: String, lines: Int): EditText {
        root.addView(TextView(this).apply { text = title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 10, 0, 4) })
        val e = EditText(this).apply {
            setText(value); gravity = Gravity.TOP; minLines = lines; maxLines = maxOf(lines, 12); setPadding(12, 10, 12, 10)
        }
        root.addView(e, LinearLayout.LayoutParams(-1, -2))
        return e
    }

    private fun savePersona() {
        PromptStore.save(this, PromptStore.PERSONA_NAME_KEY, name.text.toString().trim().take(60))
        PromptStore.save(this, PromptStore.PERSONA_KEY, persona.text.toString().trim().take(4000))
        PromptStore.save(this, PromptStore.STYLE_KEY, style.text.toString().trim().take(2000))
        PromptStore.save(this, PromptStore.RULES_KEY, rules.text.toString().trim().take(4000))
        PromptStore.save(this, PromptStore.PROMPT_KEY, prompt.text.toString().trim().take(12000))
        Toast.makeText(this, "Persona saved and will apply to new requests", Toast.LENGTH_SHORT).show()
    }
}
