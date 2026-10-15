from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
main = ROOT / "app/src/main/java/com/redmi9/offlineai/MainActivity.kt"
native = ROOT / "app/src/main/cpp/native-lib.cpp"
manifest = ROOT / "app/src/main/AndroidManifest.xml"
config = ROOT / "app/src/main/java/com/redmi9/offlineai/AiConfiguration.kt"
store = ROOT / "app/src/main/java/com/redmi9/offlineai/PromptStore.kt"
assistant_config = ROOT / "app/src/main/java/com/redmi9/offlineai/AssistantConfigActivity.kt"
prompt = ROOT / "app/src/main/assets/prompt.md"
workflow = ROOT / ".github/workflows/android.yml"

for required in (main, native, manifest, config, store, assistant_config, prompt, workflow):
    if not required.exists():
        raise SystemExit(f"AUDIT FAIL: missing required file: {required}")

main_text = main.read_text()
native_text = native.read_text()
manifest_text = manifest.read_text()
config_text = config.read_text()
store_text = store.read_text()
assistant_text = assistant_config.read_text()
prompt_text = prompt.read_text().lower()
workflow_text = workflow.read_text()

methods = re.findall(r'external fun (native\w+)\(', main_text)
for method in methods:
    symbol = "Java_com_redmi9_offlineai_MainActivity_" + method
    if symbol not in native_text:
        raise SystemExit(f"AUDIT FAIL: missing JNI implementation for {method}")

if "DiagnosticLogActivity" in main_text or "LogStore" in main_text:
    raise SystemExit("AUDIT FAIL: persistent diagnostic logger remains in MainActivity")
if "DiagnosticLogActivity" in manifest_text or "DiagnosticsApplication" in manifest_text:
    raise SystemExit("AUDIT FAIL: diagnostic components remain in AndroidManifest.xml")
for obsolete in (
    "app/src/main/java/com/redmi9/offlineai/LogStore.kt",
    "app/src/main/java/com/redmi9/offlineai/DiagnosticLogActivity.kt",
    "app/src/main/java/com/redmi9/offlineai/DiagnosticsApplication.kt",
    "app/src/main/cpp/native-watchdog.cpp",
):
    if (ROOT / obsolete).exists():
        raise SystemExit(f"AUDIT FAIL: obsolete diagnostic file remains: {obsolete}")
if "native-watchdog.cpp" in (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text():
    raise SystemExit("AUDIT FAIL: native watchdog remains in CMake")

if "soul.md" in config_text or "identity.md" in config_text:
    raise SystemExit("AUDIT FAIL: obsolete Soul/Identity asset references remain")
if "SOUL" in store_text or "IDENTITY" in store_text:
    raise SystemExit("AUDIT FAIL: obsolete Soul/Identity prompt storage remains")
if "private lateinit var soul" in assistant_text or "private lateinit var identity" in assistant_text:
    raise SystemExit("AUDIT FAIL: obsolete Soul/Identity editors remain")

for required in ("latest user request", "do not invent", "second-pass", "end-of-generation token"):
    if required not in prompt_text:
        raise SystemExit(f"AUDIT FAIL: default system prompt missing: {required}")

if "llama_vocab_is_eog" not in native_text:
    raise SystemExit("AUDIT FAIL: native EOS/EOG stop check missing")
if "g_cached_prompt_tokens" not in native_text or "llama_memory_seq_rm" not in native_text:
    raise SystemExit("AUDIT FAIL: reusable prompt KV cache is missing")
if "format_chat_with_budget" not in native_text:
    raise SystemExit("AUDIT FAIL: automatic context fitting is missing")
if "generated_tokens_out" not in native_text or "generation=" not in native_text:
    raise SystemExit("AUDIT FAIL: benchmark tokens/sec measurement is missing")
if "requested_context" not in native_text or "g_model_path == model_path" not in native_text:
    raise SystemExit("AUDIT FAIL: clean same-model lifecycle reuse is missing")
if "recreate_context_locked()" not in native_text or "generate(formatted" not in native_text:
    raise SystemExit("AUDIT FAIL: native one-context generation path missing")
if "nativeGenerateChatStreaming" not in native_text:
    raise SystemExit("AUDIT FAIL: native streaming generation missing")

# Conversation history must use the same record separator on the Kotlin->JNI path
# and when the native budget fitter rebuilds the encoded history.
if "encoded << turns[i].role << '\\x1f' << turns[i].content" not in native_text:
    raise SystemExit("AUDIT FAIL: native history record separator is not the expected 0x1f delimiter")
if "encoded.find('\\x1f'" not in native_text:
    raise SystemExit("AUDIT FAIL: native history parser delimiter missing")

if "nativeBenchmark" not in main_text or "nativeBenchmark" not in native_text:
    raise SystemExit("AUDIT FAIL: benchmark JNI path missing")
if "benchmark" not in native_text.lower():
    raise SystemExit("AUDIT FAIL: native benchmark path missing")

if "assembleRelease" not in workflow_text or "assembleDebug" in workflow_text:
    raise SystemExit("AUDIT FAIL: workflow is not release-only")
if "app/build/outputs/apk/release/app-release.apk" not in workflow_text:
    raise SystemExit("AUDIT FAIL: release APK path missing")

print("AUDIT/PREPARE PASS: JNI, logger removal, prompt-only assistant config, EOS/EOG stopping, prompt/KV reuse, automatic context fitting, conversation history encoding, benchmark tokens/sec, model lifecycle and release APK workflow verified.")
