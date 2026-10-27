#include <jni.h>
#include <rwkv.h>
#include <algorithm>
#include <atomic>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
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
extern "C" JNIEXPORT jstring JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeGenerateChat(JNIEnv *env, jobject, jstring, jstring, jstring, jint) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const char *msg = g_ctx
      ? "RWKV.cpp model loaded. Chat generation is disabled until the matching RWKV tokenizer is integrated; no fake response was generated."
      : "No RWKV model loaded.";
    return env->NewStringUTF(msg);
}
extern "C" JNIEXPORT void JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeGenerateChatStreaming(JNIEnv *env, jobject self, jstring, jstring, jstring, jint) {
    jclass cls = env->GetObjectClass(self);
    if (!cls) return;
    jmethodID cb = env->GetMethodID(cls, "onNativeToken", "(Ljava/lang/String;)V");
    if (cb) {
        const char *msg = "RWKV.cpp is wired for model loading, but chat generation requires the matching RWKV tokenizer.\n";
        jstring part = env->NewStringUTF(msg);
        if (part) { env->CallVoidMethod(self, cb, part); env->DeleteLocalRef(part); }
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
