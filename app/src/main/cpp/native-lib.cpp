#include <jni.h>
#include <rwkv.h>
#include <algorithm>
#include <atomic>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>
#include <unistd.h>

namespace {
std::mutex g_mutex;
rwkv_context *g_ctx = nullptr;
std::string g_model_path;
std::atomic<bool> g_cancel{false};
uint32_t g_threads = 1;

std::string jstr(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *p = env->GetStringUTFChars(value, nullptr);
    std::string out = p ? p : "";
    if (p) env->ReleaseStringUTFChars(value, p);
    return out;
}
size_t rss_bytes() {
    std::ifstream f("/proc/self/status");
    std::string line;
    while (std::getline(f, line)) {
        if (line.rfind("VmRSS:", 0) == 0) {
            std::istringstream in(line.substr(6));
            size_t kb = 0; in >> kb; return kb * 1024;
        }
    }
    return 0;
}
std::string model_info_locked() {
    if (!g_ctx) return "No RWKV model loaded";
    std::ostringstream out;
    out << "Engine: RWKV.cpp"
        << "\nModel: " << g_model_path
        << "\nVocabulary: " << rwkv_get_n_vocab(g_ctx)
        << "\nLayers: " << rwkv_get_n_layer(g_ctx)
        << "\nEmbedding: " << rwkv_get_n_embed(g_ctx)
        << "\nThreads: " << g_threads
        << "\nRSS: " << rss_bytes() << " bytes";
    return out.str();
}
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeEngineInfo(JNIEnv *env, jobject) {
    std::ostringstream out;
    out << "RWKV.cpp CPU engine"
        << "\nABI: armeabi-v7a"
        << "\nRWKV runtime: " << rwkv_get_system_info_string()
        << "\nCPU threads: " << std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
    return env->NewStringUTF(out.str().c_str());
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeLoadModel(JNIEnv *env, jobject, jstring path, jint, jint threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) { rwkv_free(g_ctx); g_ctx = nullptr; }
    g_model_path = jstr(env, path);
    g_threads = static_cast<uint32_t>(std::max(1, std::min(4, threads > 0 ? threads : 2)));
    g_ctx = rwkv_init_from_file(g_model_path.c_str(), g_threads, 0);
    return g_ctx ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeUnloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) { rwkv_free(g_ctx); g_ctx = nullptr; }
    g_model_path.clear();
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeModelInfo(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(model_info_locked().c_str());
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeMemoryBytes(JNIEnv *, jobject) {
    return static_cast<jlong>(rss_bytes());
}
extern "C" JNIEXPORT void JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeCancelGeneration(JNIEnv *, jobject) {
    g_cancel.store(true);
}
namespace {
std::vector<uint32_t> byte_encode(const std::string &s) {
    std::vector<uint32_t> tokens;
    tokens.reserve(s.size());
    // RWKV World vocabulary reserves IDs 1..256 for individual bytes.
    for (unsigned char ch : s) tokens.push_back(static_cast<uint32_t>(ch) + 1u);
    return tokens;
}
std::string byte_decode(uint32_t token) {
    if (token >= 1 && token <= 256) return std::string(1, static_cast<char>(token - 1));
    return {};
}
std::string generate_locked(const std::string &system, const std::string &history,
                            const std::string &user, int max_tokens) {
    if (!g_ctx) return "ERROR:No RWKV model loaded.";
    const size_t vocab = rwkv_get_n_vocab(g_ctx);
    if (vocab < 257) return "ERROR:This model vocabulary is incompatible with RWKV byte-token fallback.";
    std::string prompt;
    if (!system.empty()) prompt += system + "\n\n";
    prompt += history;
    prompt += "User: " + user + "\n\nAssistant:";
    auto tokens = byte_encode(prompt);
    if (tokens.empty()) tokens.push_back(1);
    const size_t state_len = rwkv_get_state_len(g_ctx);
    const size_t logits_len = rwkv_get_logits_len(g_ctx);
    std::vector<float> state(state_len), next_state(state_len), logits(logits_len);
    rwkv_init_state(g_ctx, state.data());
    g_cancel.store(false);
    // Process the prompt sequentially so the recurrent state is retained.
    for (uint32_t token : tokens) {
        if (g_cancel.load()) return "ERROR:Generation cancelled.";
        if (!rwkv_eval(g_ctx, token, state.data(), next_state.data(), logits.data()))
            return "ERROR:RWKV prompt evaluation failed.";
        state.swap(next_state);
    }
    std::string output;
    for (int i = 0; i < std::max(1, std::min(256, max_tokens)); ++i) {
        if (g_cancel.load()) break;
        // Greedy decoding is deterministic and avoids adding an RNG dependency.
        uint32_t best = 0;
        float best_value = -1.0e30f;
        for (uint32_t j = 0; j < logits_len; ++j) {
            if (logits[j] > best_value) { best_value = logits[j]; best = j; }
        }
        if (best == 0) break;
        const std::string piece = byte_decode(best);
        output += piece;
        if (!rwkv_eval(g_ctx, best, state.data(), next_state.data(), logits.data()))
            return output + "\n[RWKV generation stopped: evaluation failed]";
        state.swap(next_state);
        if (output.size() >= 4 && output.find("\n\nUser:") != std::string::npos) {
            output.resize(output.find("\n\nUser:"));
            break;
        }
    }
    return output.empty() ? "[RWKV generated no printable byte tokens. Use the matching RWKV tokenizer for this model.]" : output;
}
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeGenerateChat(JNIEnv *env, jobject, jstring system, jstring history, jstring user, jint max_tokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(generate_locked(jstr(env, system), jstr(env, history), jstr(env, user), max_tokens).c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeGenerateChatStreaming(JNIEnv *env, jobject self, jstring system, jstring history, jstring user, jint max_tokens) {
    jclass cls = env->GetObjectClass(self);
    if (!cls) return;
    jmethodID cb = env->GetMethodID(cls, "onNativeToken", "(Ljava/lang/String;)V");
    if (cb) {
        std::string output;
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            output = generate_locked(jstr(env, system), jstr(env, history), jstr(env, user), max_tokens);
        }
        jstring part = env->NewStringUTF(output.c_str());
        if (part) { env->CallVoidMethod(self, cb, part); env->DeleteLocalRef(part); }
        jstring done = env->NewStringUTF("\001DONE");
        if (done) { env->CallVoidMethod(self, cb, done); env->DeleteLocalRef(done); }
    }
    env->DeleteLocalRef(cls);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeBenchmark(JNIEnv *env, jobject, jstring, jint) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const std::string out = g_ctx
      ? "RWKV model load test passed. Generation benchmark not run: tokenizer integration is pending."
      : "RWKV benchmark failed: no RWKV model loaded.";
    return env->NewStringUTF(out.c_str());
}


// JNI bridge for the agent/provider facade. Keep these symbols aligned with
// RwkvNativeEngine.kt; MainActivity and the agent share the same process-wide
// RWKV context above.
extern "C" JNIEXPORT jstring JNICALL
Java_com_redmi9_offlineai_RwkvNativeEngine_nativeEngineInfo(JNIEnv *env, jobject) {
    return Java_com_redmi9_offlineai_MainActivity_nativeEngineInfo(env, nullptr);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_redmi9_offlineai_RwkvNativeEngine_nativeLoadModel(JNIEnv *env, jobject, jstring path, jint context_size, jint threads) {
    return Java_com_redmi9_offlineai_MainActivity_nativeLoadModel(env, nullptr, path, context_size, threads);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_redmi9_offlineai_RwkvNativeEngine_nativeGenerateChat(JNIEnv *env, jobject, jstring system, jstring history, jstring user, jint max_tokens) {
    return Java_com_redmi9_offlineai_MainActivity_nativeGenerateChat(env, nullptr, system, history, user, max_tokens);
}
extern "C" JNIEXPORT void JNICALL
Java_com_redmi9_offlineai_RwkvNativeEngine_nativeGenerateChatStreaming(JNIEnv *env, jobject self, jstring, jstring, jstring, jint) {
    jclass cls = env->GetObjectClass(self);
    if (!cls) return;
    jmethodID cb = env->GetMethodID(cls, "onNativeToken", "(Ljava/lang/String;)V");
    if (cb) {
        jstring part = env->NewStringUTF("\001ERROR:RWKV tokenizer and token-by-token decoding are not integrated yet.");
        if (part) { env->CallVoidMethod(self, cb, part); env->DeleteLocalRef(part); }
    }
    env->DeleteLocalRef(cls);
}
extern "C" JNIEXPORT void JNICALL
Java_com_redmi9_offlineai_RwkvNativeEngine_nativeCancelGeneration(JNIEnv *, jobject) {
    g_cancel.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_com_redmi9_offlineai_RwkvNativeEngine_nativeUnloadModel(JNIEnv *env, jobject self) {
    Java_com_redmi9_offlineai_MainActivity_nativeUnloadModel(env, self);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_redmi9_offlineai_RwkvNativeEngine_nativeModelInfo(JNIEnv *env, jobject) {
    return Java_com_redmi9_offlineai_MainActivity_nativeModelInfo(env, nullptr);
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_redmi9_offlineai_RwkvNativeEngine_nativeMemoryBytes(JNIEnv *env, jobject) {
    return Java_com_redmi9_offlineai_MainActivity_nativeMemoryBytes(env, nullptr);
}
