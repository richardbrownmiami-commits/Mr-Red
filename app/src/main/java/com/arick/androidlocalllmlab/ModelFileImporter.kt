package com.arick.androidlocalllmlab

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelFileImporter(
    private val context: Context
) {
    suspend fun importModel(uri: Uri): File = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, "models").apply {
            mkdirs()
        }

        check(modelDir.isDirectory) {
            "Cannot create model directory: ${modelDir.absolutePath}"
        }

        val targetFile = File(modelDir, "${UUID.randomUUID()}.gguf")
        var completed = false

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Cannot open selected model file")

            input.use { source ->
                targetFile.outputStream().buffered().use { destination ->
                    source.copyTo(destination)
                }
            }

            completed = true
            targetFile
        } finally {
            if (!completed) {
                targetFile.delete()
            }
        }
    }

    /**
     * Deletes orphan or unselected GGUF model files in the internal models directory
     * to prevent filling flash storage on re-imports.
     */
    fun cleanOrphanModels(exceptFile: File? = null) {
        val modelDir = File(context.filesDir, "models")
        if (modelDir.isDirectory) {
            modelDir.listFiles()?.forEach { file ->
                if (file.isFile && file != exceptFile && file.extension.equals("gguf", ignoreCase = true)) {
                    file.delete()
                }
            }
        }
    }
}
