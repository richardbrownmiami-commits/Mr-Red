package com.arick.androidlocalllmlab

import android.content.Context
import java.io.File

class SelectedModelStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "local_llm_preferences",
        Context.MODE_PRIVATE
    )

    fun save(modelFile: File) {
        // Stores the real path imported to filesDir, not the temporary content:// URI.
        preferences.edit()
            .putString(KEY_SELECTED_MODEL_PATH, modelFile.absolutePath)
            .apply()
    }

    fun selectedModelFile(): File? {
        val path = preferences.getString(KEY_SELECTED_MODEL_PATH, null)
            ?: return null
        return File(path)
    }

    fun clear() {
        preferences.edit()
            .remove(KEY_SELECTED_MODEL_PATH)
            .apply()
    }

    fun getSummaryLines(): Int {
        return preferences.getInt(KEY_SUMMARY_LINES, DEFAULT_SUMMARY_LINES)
    }

    fun setSummaryLines(lines: Int) {
        preferences.edit()
            .putInt(KEY_SUMMARY_LINES, lines)
            .apply()
    }

    companion object {
        const val DEFAULT_SUMMARY_LINES = 4
        private const val KEY_SELECTED_MODEL_PATH = "selected_model_path"
        private const val KEY_SUMMARY_LINES = "summary_lines"
    }
}
