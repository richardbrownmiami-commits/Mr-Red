package com.redmi9.offlineai

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import com.redmi9.offlineai.agent.AgentAccessibilityService
import com.redmi9.offlineai.agent.AgentRuntime
import kotlinx.coroutines.runBlocking

class MainActivity : Activity() {
    external fun nativeEngineInfo(): String
    external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): Boolean
    external fun nativeGenerateChat(systemContext: String, historyEncoded: String, userText: String, maxTokens: Int): String
    external fun nativeGenerateChatStreaming(systemContext: String, historyEncoded: String, userText: String, maxTokens: Int)
    external fun nativeCancelGeneration()
    external fun nativeBenchmark(systemContext: String, maxTokens: Int): String
    external fun nativeUnloadModel()
    external fun nativeModelInfo(): String
    external fun nativeMemoryBytes(): Long

    private data class ChatTurn(val role: String, val content: String)
    private data class Bubble(val sender: String, val text: String, val user: Boolean)

    private lateinit var config: AiConfiguration
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var modelLabel: TextView
    private lateinit var sendButton: ImageButton
    private lateinit var modelsDir: File

    private val conversation = mutableListOf<ChatTurn>()
    private val transcript = mutableListOf<Bubble>()

    private var cpuThreads = 4
    private var contextSize = 2048
    private var generating = false
    private var modelBusy = false
    private var importing = false
    private var forceStick = false

    private var pendingPrompt = ""
    private var streamBuffer = StringBuilder()
    private var streamBody: TextView? = null
    private var streamError: String? = null
    private var streamTokens = 0
    private var streamFirstTokenAt = 0L

    private val modelName: String
        get() = loadedModelFile?.name ?: NO_MODEL

    companion object {
        private const val PICK_GGUF = 4101
        private const val MAX_MODEL_BYTES = 900L * 1024L * 1024L
        private const val MAX_HISTORY_TURNS = 6
        private const val MAX_REPLY_TOKENS = 96
        private const val PREFS = "mekamaru_ui"
        private const val NO_MODEL = "No GGUF model loaded"
        private val CONTEXT_OPTIONS = listOf(512, 1024, 2048, 3072, 4096)
        private val ACCENT = Color.rgb(45, 105, 180)
        private val AI_BUBBLE = Color.rgb(236, 239, 244)

        private var loadedModelFile: File? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        System.loadLibrary("offlineai")
        config = AiConfiguration(this)
        modelsDir = File(filesDir, "models")
        modelsDir.mkdirs()
        loadSettings()
        buildChatUi()
        refreshHeader()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val detectedCores = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val defaultContext = recommendedContextSize()
        cpuThreads = prefs.getInt("cpu_threads", detectedCores).coerceIn(1, 4)
        contextSize = prefs.getInt("context_size", defaultContext)
        if (contextSize !in CONTEXT_OPTIONS) contextSize = defaultContext
    }

    private fun totalRamBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem
    }

    private fun recommendedContextSize(): Int {
        val total = totalRamBytes()
        return when {
            total <= 3L * 1024L * 1024L * 1024L -> 1024
            total <= 5L * 1024L * 1024L * 1024L -> 2048
            else -> 3072
        }
    }

    private fun saveSettings() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("cpu_threads", cpuThreads)
            .putInt("context_size", contextSize)
            .apply()
    }

    fun onNativeToken(token: String) {
        runOnUiThread { handleNativeToken(token) }
    }

    private fun handleNativeToken(token: String) {
        if (!generating) return
        if (token.startsWith("\u0001")) {
            val control = token.substring(1)
            if (control.startsWith("ERROR:")) {
                streamError = control.removePrefix("ERROR:").trim().ifEmpty { "unknown native error" }
                renderStream()
            } else if (control == "DONE") {
                finishStreaming()
            }
            return
        }
        streamBuffer.append(token)
        streamTokens += 1
        if (streamFirstTokenAt == 0L) streamFirstTokenAt = SystemClock.elapsedRealtime()
        renderStream()
        status.text = "CPU • $cpuThreads threads • Context $contextSize • Streaming $streamTokens tokens" + speedText()
    }

    private fun renderStream() {
        val body = streamBody ?: return
        val error = streamError
        val text = streamBuffer.toString()
        body.text = if (error == null) text else if (text.isEmpty()) "⚠ $error" else text + "\n\n⚠ " + error
    }

    private fun speedText(): String {
        if (streamTokens < 3 || streamFirstTokenAt == 0L) return ""
        val seconds = (SystemClock.elapsedRealtime() - streamFirstTokenAt) / 1000.0
        if (seconds <= 0.0) return ""
        return String.format(Locale.US, " • ~%.1f tok/s", (streamTokens - 1) / seconds)
    }

    private fun finishStreaming() {
        if (!generating) return
        generating = false
        val result = streamBuffer.toString()
        val error = streamError
        val failed = error != null
        renderStream()
        if (!failed && result.isBlank()) streamBody?.text = "(empty reply)"
        if (!failed && result.isNotBlank()) {
            conversation.add(ChatTurn("user", pendingPrompt))
            conversation.add(ChatTurn("assistant", result))
            while (conversation.size > MAX_HISTORY_TURNS) conversation.removeAt(0)
        }
        val shown = streamBody?.text?.toString() ?: result
        transcript.add(Bubble("Mr-Red", shown, false))
        streamBody = null
        updateSendState()
        status.text = modelStatus() + speedText()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun makeIconButton(iconRes: Int, description: String, action: () -> Unit): ImageButton = ImageButton(this).apply {
        contentDescription = description
        setImageResource(iconRes)
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(ACCENT) }
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { action() }
    }

    private fun buildChatUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); fitsSystemWindows = true }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
        header.addView(makeIconButton(R.drawable.ic_menu, "Menu") { showMenu() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        title.addView(TextView(this).apply { text = "Mr-Red"; textSize = 21f; typeface = Typeface.DEFAULT_BOLD })
        modelLabel = TextView(this).apply { text = modelName; textSize = 13f; setTextColor(Color.DKGRAY) }
        title.addView(modelLabel)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(makeIconButton(R.drawable.ic_settings, "Settings") { showSettings() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header)
        root.addView(View(this).apply { setBackgroundColor(Color.rgb(224, 224, 224)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        scroll = ScrollView(this)
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(14), dp(12), dp(14)) }
        scroll.addView(messages)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        messages.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val oldHeight = oldBottom - oldTop
            val grew = (bottom - top) > oldHeight
            val nearBottom = scroll.scrollY + scroll.height >= oldHeight - dp(120)
            if (grew && (forceStick || nearBottom)) { forceStick = false; scroll.scrollTo(0, bottom) }
        }
        addNotice(welcomeText())
        val composer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(4)) }
        input = EditText(this).apply { hint = "Message..."; maxLines = 4; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        composer.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sendButton = makeIconButton(R.drawable.ic_send, "Send") { if (generating) cancelGeneration() else sendMessage() }
        composer.addView(sendButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        root.addView(composer)
        status = TextView(this).apply { textSize = 12f; setTextColor(Color.DKGRAY); setPadding(dp(14), dp(2), dp(14), dp(8)) }
        root.addView(status)
        setContentView(root)
    }

    private fun welcomeText(): String {
        val file = loadedModelFile
        return if (file == null) "Import a .gguf model, load it, then send a message for local inference.\n\nTip: long-press any message to copy it." else "Model ready: ${file.name}\nSend a message to start.\n\nTip: long-press any message to copy it."
    }

    private fun refreshHeader() { modelLabel.text = modelName; status.text = modelStatus() }
    private fun restoreStatus() { if (!generating && !modelBusy) status.text = modelStatus() }
    private fun updateSendState() { sendButton.isEnabled = !modelBusy; sendButton.alpha = if (modelBusy) 0.4f else 1f; sendButton.contentDescription = if (generating) "Stop generation" else "Send" }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    private fun copyToClipboard(text: String) { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Mr-Red", text)); toast("Copied") }

    private fun addBubble(sender: String, content: String, user: Boolean): TextView {
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (user) ACCENT else AI_BUBBLE) }
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        val senderView = TextView(this).apply { text = sender; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (user) Color.argb(200, 255, 255, 255) else Color.GRAY) }
        val body = TextView(this).apply { text = content; textSize = 16f; setTextColor(if (user) Color.WHITE else Color.rgb(30, 30, 30)) }
        bubble.addView(senderView); bubble.addView(body)
        bubble.setOnLongClickListener { copyToClipboard(body.text.toString()); true }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = if (user) Gravity.END else Gravity.START; setMargins(if (user) dp(48) else 0, dp(5), if (user) 0 else dp(48), dp(5)) }
        messages.addView(bubble, params)
        return body
    }

    private fun addNotice(text: String) { addBubble("Mr-Red", text, false) }
    private fun encodeHistory(): String = conversation.takeLast(MAX_HISTORY_TURNS).joinToString("\u001e") { it.role + "\u001f" + it.content }

    private fun cancelGeneration() { if (!generating) return; status.text = "Stopping native generation..."; try { nativeCancelGeneration() } catch (_: Throwable) {} }

    private fun sendMessage() {
        val prompt = input.text.toString().trim()
        if (prompt.isEmpty() || generating || modelBusy) return
        if (loadedModelFile == null) { showModels("Load a GGUF model before sending a message."); return }
        val historyForRequest = encodeHistory(); val systemContext = config.buildSystemContext(modelName)
        generating = true; pendingPrompt = prompt; streamBuffer = StringBuilder(); streamError = null; streamTokens = 0; streamFirstTokenAt = 0L; forceStick = true
        addBubble("You", prompt, true); transcript.add(Bubble("You", prompt, true)); input.text.clear(); streamBody = addBubble("Mr-Red", "Waiting for first token...", false); updateSendState()
        status.text = "CPU • $cpuThreads threads • Context $contextSize • Starting native stream"
        Thread {
            try {
                nativeGenerateChatStreaming(systemContext, historyForRequest, prompt, MAX_REPLY_TOKENS)
            } catch (e: Throwable) {
                val reason = e.message ?: "native engine unavailable"
                runOnUiThread {
                    streamError = "Inference error: $reason"
                    finishStreaming()
                }
            } finally {
                // Native normally sends DONE after EOS/EOG or the safety token limit.
                // If a native/backend path returns without delivering that callback,
                // make sure the UI still leaves the generating state.
                runOnUiThread {
                    if (generating) finishStreaming()
                }
            }
        }.start()
    }

    private fun menuRow(textValue: String, iconRes: Int): TextView = TextView(this).apply {
        text = textValue; textSize = 17f; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(14))
        val icon = getDrawable(iconRes)?.mutate(); icon?.setTint(Color.DKGRAY); setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null); compoundDrawablePadding = dp(16)
    }

    private fun showMenu() {
        val labels = arrayOf("Models", "Agent task (accessibility)", "Model Tests", "Memory & History", "Settings", "Engine Info", "Assistant editor", "New chat", "Share chat")
        val icons = intArrayOf(R.drawable.ic_models, R.drawable.ic_debug, R.drawable.ic_debug, R.drawable.ic_memory, R.drawable.ic_settings, R.drawable.ic_debug, R.drawable.ic_edit, R.drawable.ic_edit, R.drawable.ic_send)
        val dialog = AlertDialog.Builder(this).setTitle("Mr-Red").create(); val list = ListView(this); list.dividerHeight = 1
        list.adapter = object : BaseAdapter() { override fun getCount() = labels.size; override fun getItem(position: Int): Any = labels[position]; override fun getItemId(position: Int) = position.toLong(); override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = menuRow(labels[position], icons[position]) }
        list.setOnItemClickListener { _, _, which, _ -> dialog.dismiss(); when (which) { 0 -> showModels(); 1 -> showAgentTask(); 2 -> showModelTests(); 3 -> showHistory(); 4 -> showSettings(); 5 -> showEngine(); 6 -> startActivity(Intent(this, AssistantConfigActivity::class.java)); 7 -> newChat(); 8 -> shareChat() } }
        dialog.setView(list); dialog.show()
    }

    private fun newChat() { if (generating || modelBusy) { toast("Wait for the current task to finish."); return }; conversation.clear(); transcript.clear(); messages.removeAllViews(); addNotice(welcomeText()); status.text = modelStatus() }
    private fun shareChat() { if (transcript.isEmpty()) { toast("Nothing to share yet."); return }; val text = transcript.joinToString("\n\n") { it.sender + ": " + it.text }; val send = Intent(Intent.ACTION_SEND); send.type = "text/plain"; send.putExtra(Intent.EXTRA_TEXT, text); startActivity(Intent.createChooser(send, "Share chat")) }

    private fun modelFiles(): List<File> { val found = modelsDir.listFiles { f -> f.isFile && f.name.lowercase(Locale.US).endsWith(".gguf") }; return found?.sortedBy { it.name.lowercase(Locale.US) } ?: emptyList() }


    private fun showAgentTask() {
        if (generating || modelBusy) {
            toast("Wait for the current model task to finish.")
            return
        }
        if (loadedModelFile == null) {
            showModels("Load a GGUF model before starting an agent task.")
            return
        }
        if (AgentAccessibilityService.current() == null) {
            AlertDialog.Builder(this)
                .setTitle("Enable screen access")
                .setMessage("Android requires you to enable AI Agent Screen Access manually in Accessibility settings. The agent will only read the visible screen and perform a single requested low-risk action per run.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val taskInput = EditText(this).apply {
            hint = "Describe one task for the agent"
            minLines = 2
            maxLines = 4
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Agent task")
            .setMessage("The local model will inspect visible screen text and may perform at most one low-risk action. Payment, send, delete, install, permission, and other sensitive actions are blocked.")
            .setView(taskInput)
            .setPositiveButton("Run task") { _, _ ->
                val task = taskInput.text.toString().trim()
                if (task.isEmpty()) {
                    toast("Enter a task first.")
                    return@setPositiveButton
                }
                modelBusy = true
                status.text = "Agent • planning locally"
                updateSendState()
                Thread {
                    val result = runCatching { runBlocking { AgentRuntime().executeTask(task) } }
                        .getOrElse { "Agent error: ${it.message ?: "unknown error"}" }
                    runOnUiThread {
                        modelBusy = false
                        addBubble("Agent", result, false)
                        transcript.add(Bubble("Agent", result, false))
                        status.text = modelStatus()
                        updateSendState()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showModels(message: String? = null) {
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        val files = modelFiles(); val loadedPath = loadedModelFile?.canonicalPath
        val labels = if (files.isEmpty()) arrayOf("No GGUF models imported") else files.map { f -> f.name + "  (" + formatBytes(f.length()) + ")" + if (loadedPath != null && loadedPath == f.canonicalPath) "  ✔ LOADED" else "" }.toTypedArray()
        val builder = AlertDialog.Builder(this).setTitle("Models"); builder.setItems(labels) { _, which -> if (files.isNotEmpty()) showModelActions(files[which]) }; builder.setNeutralButton("Import GGUF") { _, _ -> pickGguf() }; if (loadedModelFile != null) builder.setNegativeButton("Unload") { _, _ -> unloadModel() }; builder.show()
    }

    private fun showModelActions(file: File) { val loaded = loadedModelFile?.canonicalPath == file.canonicalPath; AlertDialog.Builder(this).setTitle(file.name).setMessage("Size: ${formatBytes(file.length())}\nCPU: $cpuThreads threads\nContext: $contextSize").setPositiveButton(if (loaded) "Unload model" else "Load model") { _, _ -> if (loaded) unloadModel() else loadModel(file) }.setNeutralButton("Delete") { _, _ -> confirmDelete(file) }.setNegativeButton("Close", null).show() }
    private fun confirmDelete(file: File) { if (generating || modelBusy) { toast("Wait for the current task to finish."); return }; if (loadedModelFile?.canonicalPath == file.canonicalPath) { showError("Delete model", "Unload this model before deleting it."); return }; AlertDialog.Builder(this).setTitle("Delete model?").setMessage(file.name + " (" + formatBytes(file.length()) + ") will be removed from this phone.").setPositiveButton("Delete") { _, _ -> toast(if (file.delete()) "Deleted" else "Could not delete the file") }.setNegativeButton("Cancel", null).show() }

    private fun loadModel(file: File) {
        if (generating || modelBusy) { toast("Wait for the current task to finish."); return }
        if (file.length() >= MAX_MODEL_BYTES) { showError("Model too large", "This model must be under 900 MB."); return }
        modelBusy = true; updateSendState(); val threads = cpuThreads; val ctxSize = contextSize; status.text = "Loading ${file.name} • CPU $threads • Context $ctxSize"
        Thread {
            val ok = try { nativeLoadModel(file.absolutePath, ctxSize, threads) } catch (_: Throwable) { false }
            runOnUiThread {
                modelBusy = false
                if (ok) { loadedModelFile = file; conversation.clear(); refreshHeader(); addNotice("Model loaded: ${file.name}\nContext: $ctxSize\nCPU threads: $threads.") }
                else { loadedModelFile = null; conversation.clear(); refreshHeader(); status.text = "CPU • Model load failed"; showError("Model load failed", "Native engine returned failure. Check the model file and try again.") }
                updateSendState()
            }
        }.start()
    }

    private fun unloadModel() {
        if (generating || modelBusy) { toast("Wait for the current task to finish."); return }
        modelBusy = true; updateSendState(); Thread { try { nativeUnloadModel() } catch (_: Throwable) {}; runOnUiThread { modelBusy = false; loadedModelFile = null; conversation.clear(); refreshHeader(); updateSendState() } }.start()
    }

    private fun showModelTests() {
        val files = modelFiles(); if (files.isEmpty()) { showError("Model Tests", "Import at least one .gguf model first."); return }; if (generating || modelBusy) { showError("Model Tests", "Wait for the current task to finish."); return }
        modelBusy = true; updateSendState(); val box = EditText(this).apply { minLines = 14; setText("Preparing model tests...\n"); isFocusable = false; setTextIsSelectable(true) }; val dialog = AlertDialog.Builder(this).setTitle("Model Tests").setView(box).setPositiveButton("Close", null).create(); dialog.show(); val original = loadedModelFile; val threads = cpuThreads; val ctxSize = contextSize
        Thread {
            for (file in files) {
                runOnUiThread { box.append("\nLoading ${file.name}...\n") }; val ok = try { nativeLoadModel(file.absolutePath, ctxSize, threads) } catch (_: Throwable) { false }; if (!ok) { runOnUiThread { box.append("LOAD FAILED\n") }; continue }; val result = try { nativeBenchmark(config.buildSystemContext(file.name), 48) } catch (e: Throwable) { "TEST FAILED: ${e.message ?: "native error"}" }; runOnUiThread { box.append(result + "\n") }; try { nativeUnloadModel() } catch (_: Throwable) {}
            }
            var restored: File? = null; if (original != null && original.exists()) { val back = try { nativeLoadModel(original.absolutePath, ctxSize, threads) } catch (_: Throwable) { false }; if (back) restored = original }; val finalModel = restored
            runOnUiThread { loadedModelFile = finalModel; modelBusy = false; refreshHeader(); updateSendState() }
        }.start()
    }

    private fun pickGguf() { val intent = Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.type = "*/*"; startActivityForResult(intent, PICK_GGUF) }

    @Deprecated("Legacy result API retained for Android 11 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); if (requestCode == PICK_GGUF && resultCode == RESULT_OK) data?.data?.let { importGguf(it) } }

    private fun importGguf(uri: Uri) {
        if (importing) { toast("An import is already running."); return }; importing = true
        Thread {
            var temp: File? = null
            try {
                val name = queryDisplayName(uri) ?: "imported_model.gguf"; if (!name.lowercase(Locale.US).endsWith(".gguf")) throw IllegalArgumentException("Please select a .gguf file"); val expected = contentLength(uri); if (expected >= MAX_MODEL_BYTES) throw IllegalArgumentException("Model must be under 900 MB"); val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_"); var dest = File(modelsDir, safe); if (dest.exists()) dest = File(modelsDir, System.currentTimeMillis().toString() + "_" + safe); val part = File(modelsDir, "." + dest.name + ".part"); temp = part; var total = 0L; var lastReport = 0L
                val stream = contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Could not read selected file"); stream.use { source -> FileOutputStream(part).use { output -> val buffer = ByteArray(64 * 1024); while (true) { val n = source.read(buffer); if (n < 0) break; total += n; if (total >= MAX_MODEL_BYTES) throw IllegalArgumentException("Model is 900 MB or larger"); output.write(buffer, 0, n); if (total - lastReport >= 8L * 1024L * 1024L) { lastReport = total; val done = total; runOnUiThread { if (!generating && !modelBusy) status.text = "Importing model... " + (if (expected > 0) formatBytes(done) + " / " + formatBytes(expected) else formatBytes(done)) } } } } }
                if (total <= 0) throw IllegalArgumentException("Selected file is empty"); if (!part.renameTo(dest)) throw IllegalStateException("Could not finish model import"); temp = null; val finalFile = dest; val finalBytes = total; runOnUiThread { importing = false; restoreStatus(); addNotice("Imported ${finalFile.name} (${formatBytes(finalBytes)})."); showModelActions(finalFile) }
            } catch (e: Throwable) { temp?.delete(); val reason = e.message ?: "Unknown import error"; runOnUiThread { importing = false; restoreStatus(); showError("GGUF import failed", reason) } }
        }.start()
    }

    private fun showSettings() { AlertDialog.Builder(this).setTitle("Settings").setMessage("CPU default: 4\nContext default: 2048\nCurrent CPU: $cpuThreads\nCurrent Context: $contextSize\n\nSettings are saved. Reload the model to apply changes.").setPositiveButton("CPU threads") { _, _ -> chooseCpuThreads() }.setNeutralButton("Context") { _, _ -> chooseContext() }.setNegativeButton("Assistant editor") { _, _ -> startActivity(Intent(this, AssistantConfigActivity::class.java)) }.show() }
    private fun chooseCpuThreads() { val values = arrayOf("1", "2", "3", "4"); AlertDialog.Builder(this).setTitle("CPU threads").setSingleChoiceItems(values, cpuThreads - 1) { dialog, which -> cpuThreads = which + 1; saveSettings(); dialog.dismiss(); showSettings() }.show() }
    private fun chooseContext() { val values = CONTEXT_OPTIONS.map { it.toString() }.toTypedArray(); val checked = CONTEXT_OPTIONS.indexOf(contextSize).coerceAtLeast(0); AlertDialog.Builder(this).setTitle("Context length").setSingleChoiceItems(values, checked) { dialog, which -> contextSize = CONTEXT_OPTIONS[which]; saveSettings(); dialog.dismiss(); showSettings() }.show() }
    private fun showHistory() { AlertDialog.Builder(this).setTitle("Memory & History").setMessage("The current chat keeps the latest $MAX_HISTORY_TURNS turns in the active conversation. Long-term memory is not used for inference yet.\n\nChats are not saved when the app closes.").setPositiveButton("OK", null).show() }

    private fun showEngine() {
        if (generating || modelBusy) toast("Engine info will appear when the current task finishes.")
        Thread { val info = try { nativeEngineInfo() + "\n\n" + nativeModelInfo() } catch (e: Throwable) { "Engine info unavailable: ${e.message ?: "native error"}" }; runOnUiThread { if (!isFinishing) AlertDialog.Builder(this).setTitle("Engine Info").setMessage(info).setPositiveButton("OK", null).show() } }.start()
    }

    private fun queryDisplayName(uri: Uri): String? { var cursor: Cursor? = null; return try { cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null); if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null } finally { cursor?.close() } }
    private fun contentLength(uri: Uri): Long { var cursor: Cursor? = null; return try { cursor = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null); if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L } finally { cursor?.close() } }
    private fun formatBytes(bytes: Long): String { if (bytes < 0) return "unknown size"; if (bytes < 1024L * 1024L) return (bytes / 1024L).toString() + " KB"; return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0)) }
    private fun showError(title: String, message: String) { if (!isFinishing) AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    private fun modelStatus(): String { val bytes = try { nativeMemoryBytes() } catch (_: Throwable) { 0L }; return "CPU • Threads: $cpuThreads • Context: $contextSize • RAM: " + if (bytes > 0) formatBytes(bytes) else "unknown" }
}
