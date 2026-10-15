package com.redmi9.offlineai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.*
import com.redmi9.offlineai.skill.SkillDefinition
import com.redmi9.offlineai.skill.SkillStore

/** UI for importing and inspecting offline declarative Mr-Red skills. */
class SkillsActivity : Activity() {
    private lateinit var store: SkillStore
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SkillStore(this)
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(Color.rgb(8, 12, 16))
        }
        root.addView(TextView(this).apply {
            text = "Skill Lab"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Teach Mr-Red new repeatable workflows with JSON. Skills are declarative and allow-listed; arbitrary Kotlin/code is never executed."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 8, 0, 14)
        })
        root.addView(Button(this).apply { text = "Import skill JSON"; setOnClickListener { pickSkill() } })
        root.addView(Button(this).apply { text = "Create skill from template"; setOnClickListener { showTemplate() } })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refreshList()
    }

    private fun refreshList() {
        list.removeAllViews()
        val skills = store.list()
        if (skills.isEmpty()) {
            list.addView(TextView(this).apply { text = "No custom skills installed yet."; setTextColor(Color.GRAY); setPadding(0, 20, 0, 20) })
            return
        }
        skills.forEach { skill ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 12) }
            row.addView(TextView(this).apply { text = skill.name; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
            row.addView(TextView(this).apply { text = skill.description.ifBlank { "No description" }; setTextColor(Color.LTGRAY) })
            row.addView(TextView(this).apply { text = "Triggers: ${skill.triggers.joinToString(", ")}"; setTextColor(Color.GRAY); setPadding(0, 4, 0, 2) })
            row.addView(TextView(this).apply { text = "${skill.steps.size} steps"; setTextColor(Color.GRAY) })
            row.setOnLongClickListener { confirmDelete(skill); true }
            list.addView(row, ViewGroup.LayoutParams(-1, -2))
        }
    }

    private fun pickSkill() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/json"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, PICK_SKILL)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_SKILL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val json = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        if (json.isNullOrBlank()) { toast("Could not read that file"); return }
        runCatching { store.importJson(json) }
            .onSuccess { toast("Installed skill: ${it.name}"); refreshList() }
            .onFailure { toast("Skill rejected: ${it.message ?: "invalid JSON"}") }
    }

    private fun showTemplate() {
        val template = """{
  \"id\": \"my_workflow\",
  \"name\": \"My workflow\",
  \"description\": \"A repeatable Android workflow\",
  \"triggers\": [\"do my workflow\"],
  \"steps\": [
    {\"action\": \"launch_app\", \"package\": \"com.example.app\"},
    {\"action\": \"wait\", \"ms\": 1200},
    {\"action\": \"click_text_any\", \"texts\": [\"Create\", \"New\"]},
    {\"action\": \"type_text\", \"text\": \"${'$'}{input}\"},
    {\"action\": \"respond\", \"text\": \"Workflow reached its final safe step.\"}
  ]
}"""
        AlertDialog.Builder(this).setTitle("Skill template").setMessage(template).setPositiveButton("Copy") { _, _ ->
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Mr-Red skill", template))
            toast("Template copied")
        }.setNegativeButton("Close", null).show()
    }

    private fun confirmDelete(skill: SkillDefinition) {
        AlertDialog.Builder(this).setTitle("Remove skill?").setMessage(skill.name).setPositiveButton("Remove") { _, _ ->
            store.delete(skill.id); refreshList(); toast("Skill removed")
        }.setNegativeButton("Cancel", null).show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object { private const val PICK_SKILL = 7201 }
}
