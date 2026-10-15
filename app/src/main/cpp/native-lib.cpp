#include <jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>
#include "llama.h"

namespace {
std::mutex g_mutex;
std::atomic<bool> g_abort{false};

static bool llamaAbortCallback(void *) {
    return g_abort.load(std::memory_order_relaxed);
}
llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
llama_sampler *g_sampler = nullptr;
std::string g_model_path;
int g_threads = 1;
uint32_t g_context = 1024;
std::vector<llama_token> g_cached_prompt_tokens;

int choose_threads(int requested) {
    unsigned c = std::thread::hardware_concurrency();
    int cap = std::min(4, c ? static_cast<int>(c) : 4);
    return requested <= 0 ? std::max(1, cap) : std::clamp(requested, 1, cap);
}

std::string js(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * c = env->GetStringUTFChars(value, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(value, c);
    return out;
}

size_t rss() {
    std::ifstream f("/proc/self/status");
    std::string line;
    while (std::getline(f, line)) {
        if (line.rfind("VmRSS:", 0) == 0) {
            std::istringstream in(line.substr(7));
            size_t kb = 0;
            in >> kb;
            return kb * 1024;
        }
    }
    return 0;
}

void clear_prompt_cache_locked() {
    g_cached_prompt_tokens.clear();
}

void unload_locked() {
    if (g_sampler) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_model_path.clear();
    clear_prompt_cache_locked();
}

bool reset_sampler_locked() {
    if (!g_model || !g_ctx) return false;
    if (g_sampler) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!g_sampler) return false;
    llama_sampler_chain_add(g_sampler, llama_sampler_init_greedy());
    return true;
}

bool recreate_context_locked() {
    if (!g_model) return false;
    if (g_sampler) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    clear_prompt_cache_locked();

    auto p = llama_context_default_params();
    p.n_ctx = g_context;
    p.n_batch = g_context;
    p.n_ubatch = std::min<uint32_t>(g_context, 32);
    p.abort_callback = [](void *) -> bool {
        return g_abort.load(std::memory_order_relaxed);
    };
    p.abort_callback_data = nullptr;
    p.n_threads = g_threads;
    p.n_threads_batch = g_threads;
    p.no_perf = true;

    g_ctx = llama_init_from_model(g_model, p);
    if (!g_ctx) return false;
    return reset_sampler_locked();
}

struct Turn {
    std::string role;
    std::string content;
};

std::vector<Turn> history(const std::string & encoded) {
    std::vector<Turn> out;
    if (encoded.empty()) return out;
    size_t start = 0;
    while (start < encoded.size()) {
        size_t end = encoded.find('\x1e', start);
        size_t stop = end == std::string::npos ? encoded.size() : end;
        size_t sep = encoded.find('\x1f', start);
        if (sep != std::string::npos && sep < stop) {
            std::string role = encoded.substr(start, sep - start);
            std::string content = encoded.substr(sep + 1, stop - sep - 1);
            if ((role == "user" || role == "assistant") && !content.empty()) {
                out.push_back({role, content});
            }
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return out;
}

bool format_chat(const std::string & system,
                 const std::string & encoded_history,
                 const std::string & user,
                 std::string & out) {
    if (!g_model || user.empty()) return false;

    std::vector<Turn> turns{{"system", system}};
    auto h = history(encoded_history);
    turns.insert(turns.end(), h.begin(), h.end());
    turns.push_back({"user", user});

    std::vector<llama_chat_message> messages;
    messages.reserve(turns.size());
    for (auto & t : turns) messages.push_back({t.role.c_str(), t.content.c_str()});

    const char * tmpl = llama_model_chat_template(g_model, nullptr);
    if (tmpl && *tmpl) {
        int32_t n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, nullptr, 0);
        if (n < 0) return false;
        std::vector<char> buffer(static_cast<size_t>(n) + 1, '\0');
        int32_t written = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true,
                                                     buffer.data(), static_cast<int32_t>(buffer.size()));
        if (written < 0) return false;
        out.assign(buffer.data(), static_cast<size_t>(written));
        return true;
    }

    std::ostringstream fallback;
    fallback << "[SYSTEM]\n" << system << "\n\n";
    for (auto & t : turns) {
        if (t.role != "system") fallback << "[" << t.role << "]\n" << t.content << "\n\n";
    }
    fallback << "[assistant]\n";
    out = fallback.str();
    return true;
}

void notify(JNIEnv * env, jobject self, const std::string & text) {
    jclass cls = env->GetObjectClass(self);
    if (!cls) return;
    jmethodID method = env->GetMethodID(cls, "onNativeToken", "(Ljava/lang/String;)V");
    if (method) {
        jstring value = env->NewStringUTF(text.c_str());
        if (value) {
            env->CallVoidMethod(self, method, value);
            env->DeleteLocalRef(value);
        }
    }
    env->DeleteLocalRef(cls);
}

bool format_chat_with_budget(const std::string & system,
                         const std::string & encoded_history,
                         const std::string & user,
                         int limit,
                         std::string & out,
                         int * prompt_tokens_out = nullptr) {
    auto turns = history(encoded_history);
    const int safety = std::max(4, limit + 2);

    while (true) {
        std::ostringstream encoded;
        for (size_t i = 0; i < turns.size(); ++i) {
            if (i) encoded << '\x1e';
            encoded << turns[i].role << '\x1f' << turns[i].content;
        }

        if (!format_chat(system, encoded.str(), user, out)) return false;

        auto * vocab = llama_model_get_vocab(g_model);
        if (!vocab) return false;
        int n_prompt = -llama_tokenize(vocab, out.c_str(), out.size(), nullptr, 0, true, true);
        if (n_prompt > 0 && static_cast<uint32_t>(n_prompt + safety) < g_context) {
            if (prompt_tokens_out) *prompt_tokens_out = n_prompt;
            return true;
        }

        if (turns.empty()) {
            return false;
        }

        turns.erase(turns.begin());
        if (!turns.empty() && turns.front().role == "assistant") {
            turns.erase(turns.begin());
        }
    }
}

std::string generate(const std::string & prompt, int limit, JNIEnv * env = nullptr, jobject callback = nullptr, int * generated_tokens_out = nullptr) {
    g_abort.store(false, std::memory_order_relaxed);
    if (!g_model || !g_ctx || !reset_sampler_locked()) return {};
    llama_set_abort_callback(g_ctx, llamaAbortCallback, nullptr);

    auto * vocab = llama_model_get_vocab(g_model);
    auto * memory = llama_get_memory(g_ctx);
    if (!vocab || !memory) return {};

    int n_prompt = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    if (n_prompt <= 0) return {};
    if (static_cast<uint32_t>(n_prompt + limit + 2) >= g_context) return {};

    std::vector<llama_token> tokens(static_cast<size_t>(n_prompt));
    int tokenized = llama_tokenize(vocab, prompt.c_str(), prompt.size(), tokens.data(), tokens.size(), true, true);
    if (tokenized < 0) return {};

    size_t common = 0;
    while (common < g_cached_prompt_tokens.size() &&
           common < tokens.size() &&
           g_cached_prompt_tokens[common] == tokens[common]) {
        ++common;
    }

    const bool first_context = llama_memory_seq_pos_max(memory, 0) < 0;
    if (first_context || g_cached_prompt_tokens.empty()) common = 0;

    if (common > 0) {
        if (!llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(common), -1)) return {};
    } else if (!first_context) {
        if (!llama_memory_seq_rm(memory, 0, 0, -1)) return {};
    }

    if (common == tokens.size() && common > 0) {
        const size_t keep = common - 1;
        if (!llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(keep), -1)) return {};
        llama_token last = tokens[keep];
        auto batch = llama_batch_get_one(&last, 1);
        if (llama_decode(g_ctx, batch) != 0) return {};
    } else {
        const size_t suffix = common;
        const size_t count = tokens.size() - suffix;
        constexpr size_t kPromptChunk = 32;
        for (size_t offset = 0; offset < count; offset += kPromptChunk) {
            if (g_abort.load(std::memory_order_relaxed)) return {};
            const size_t chunk = std::min(kPromptChunk, count - offset);
            auto batch = llama_batch_get_one(tokens.data() + suffix + offset, static_cast<int32_t>(chunk));
            if (llama_decode(g_ctx, batch) != 0) return {};
        }
    }

    g_cached_prompt_tokens = tokens;

    std::string result;
    result.reserve(static_cast<size_t>(limit) * 4);
    int generated_tokens = 0;
    for (int i = 0; i < limit; ++i) {
        if (g_abort.load(std::memory_order_relaxed)) break;

        llama_token token = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (token < 0 || llama_vocab_is_eog(vocab, token)) break;

        llama_sampler_accept(g_sampler, token);
        ++generated_tokens;

        char piece_buffer[512];
        int n = llama_token_to_piece(vocab, token, piece_buffer, sizeof(piece_buffer), 0, true);
        std::string piece = n > 0 ? std::string(piece_buffer, n) : std::string();
        result += piece;

        if (env && callback && !piece.empty()) notify(env, callback, piece);

        if (static_cast<uint32_t>(tokens.size() + i + 2) >= g_context) break;

        llama_token next = token;
        auto batch = llama_batch_get_one(&next, 1);
        if (llama_decode(g_ctx, batch) != 0) break;
    }
    if (generated_tokens_out) *generated_tokens_out = generated_tokens;
    return result;
}

jstring result(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}

std::string model_summary_locked() {
    if (!g_model) return "No GGUF model loaded";
    auto * vocab = llama_model_get_vocab(g_model);
    const char * tmpl = llama_model_chat_template(g_model, nullptr);
    std::ostringstream out;
    out << "Model: " << g_model_path
        << "\nModel size: " << llama_model_size(g_model)
        << " bytes"
        << "\nParameters: " << llama_model_n_params(g_model)
        << "\nContext: " << (g_ctx ? llama_n_ctx(g_ctx) : g_context)
        << "\nCPU threads: " << (g_ctx ? llama_n_threads(g_ctx) : g_threads)
        << "\nVocab: " << (vocab ? llama_vocab_n_tokens(vocab) : 0)
        << "\nChat template: " << (tmpl && *tmpl ? "embedded" : "fallback")
        << "\nPrompt KV cache: " << g_cached_prompt_tokens.size() << " prompt tokens cached"
        << "\nRSS: " << rss() << " bytes";
    return out.str();
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeEngineInfo(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    llama_backend_init();
    std::ostringstream out;
    out << "llama.cpp: " << (llama_version() ? llama_version() : "unknown")
        << "\nABI: armeabi-v7a"
        << "\nCPU threads: adaptive 1-" << choose_threads(0)
        << "\nPrompt KV cache: common prompt prefix reused while model/context stay loaded"
        << "\nThink Twice: disabled";
    return result(env, out.str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeLoadModel(JNIEnv * env, jobject, jstring path, jint ctx, jint threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    llama_backend_init();

    std::string model_path = js(env, path);
    if (model_path.empty()) return JNI_FALSE;

    const int requested_threads = choose_threads(threads);
    const uint32_t requested_context = static_cast<uint32_t>(std::clamp(ctx, 256, 4096));
    if (g_model && g_ctx && g_model_path == model_path &&
        g_threads == requested_threads && g_context == requested_context) {
        g_abort.store(false, std::memory_order_relaxed);
        return JNI_TRUE;
    }

    unload_locked();

    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    params.use_extra_bufts = false;
    g_model = llama_model_load_from_file(model_path.c_str(), params);
    if (!g_model) return JNI_FALSE;

    g_threads = choose_threads(threads);
    g_context = static_cast<uint32_t>(std::clamp(ctx, 256, 4096));
    if (!recreate_context_locked()) {
        unload_locked();
        return JNI_FALSE;
    }

    g_model_path = model_path;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeGenerateChat(JNIEnv * env, jobject,
                                                          jstring system_context,
                                                          jstring encoded_history,
                                                          jstring user_text,
                                                          jint max_tokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx) return result(env, "ERROR: no GGUF model loaded");

    std::string system = js(env, system_context);
    std::string history_encoded = js(env, encoded_history);
    std::string user = js(env, user_text);
    if (user.empty()) return result(env, "");
    
    std::string formatted;
    if (!format_chat_with_budget(system, history_encoded, user, std::clamp(static_cast<int>(max_tokens), 1, 96), formatted)) {
        return result(env, "ERROR: prompt exceeds context");
    }

    std::string generated = generate(formatted, std::clamp(static_cast<int>(max_tokens), 1, 96));
    return generated.empty() ? result(env, "ERROR: generation failed") : result(env, generated);
}

extern "C" JNIEXPORT void JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeCancelGeneration(JNIEnv *, jobject) {
    g_abort.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeGenerateChatStreaming(JNIEnv * env, jobject self,
                                                                   jstring system_context,
                                                                   jstring encoded_history,
                                                                   jstring user_text,
                                                                   jint max_tokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx) {
        notify(env, self, "\x01ERROR: no GGUF model loaded");
        notify(env, self, "\x01DONE");
        return;
    }

    std::string system = js(env, system_context);
    std::string history_encoded = js(env, encoded_history);
    std::string user = js(env, user_text);
    if (user.empty()) {
        notify(env, self, "\x01DONE");
        return;
    }

    std::string formatted;
    if (!format_chat_with_budget(system, history_encoded, user, std::clamp(static_cast<int>(max_tokens), 1, 96), formatted)) {
        notify(env, self, "\x01ERROR: prompt exceeds context");
        notify(env, self, "\x01DONE");
        return;
    }

    std::string generated = generate(formatted, std::clamp(static_cast<int>(max_tokens), 1, 96), env, self);
    const bool cancelled = g_abort.load(std::memory_order_relaxed);
    if (generated.empty() && !cancelled) notify(env, self, "\x01ERROR: generation failed");
    notify(env, self, "\x01DONE");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeBenchmark(JNIEnv * env, jobject,
                                                       jstring system_context, jint max_tokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx) return result(env, "Load a GGUF model before running the model test.");

    std::string system = js(env, system_context);
    std::vector<std::string> tests = {
        "Say hello in one short sentence.",
        "What is 2 + 2? Answer briefly.",
        "Explain in one short sentence what an offline AI assistant does."
    };

    std::ostringstream out;
    out << "Model test: " << g_model_path << "\n";
    out << "Threads: " << g_threads << "  Context: " << g_context << "\n";

    for (size_t i = 0; i < tests.size(); ++i) {
        std::string formatted;
        int prompt_tokens = 0;
        const int limit = std::clamp(static_cast<int>(max_tokens), 1, 48);
        if (!format_chat_with_budget(system, "", tests[i], limit, formatted, &prompt_tokens)) {
            out << "\nTest " << i + 1 << ": prompt does not fit context\n";
            continue;
        }

        int generated_tokens = 0;
        const auto started = std::chrono::steady_clock::now();
        std::string generated = generate(formatted, limit, nullptr, nullptr, &generated_tokens);
        const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started).count();
        const double tok_s = elapsed_ms > 0 ? (generated_tokens * 1000.0 / elapsed_ms) : 0.0;

        out << "\nTest " << i + 1
            << ": prompt=" << prompt_tokens << " tokens"
            << ", generated=" << generated_tokens << " tokens"
            << ", time=" << elapsed_ms << " ms"
            << ", generation=" << tok_s << " tok/s\n"
            << generated << "\n";
    }

    out << "\nPrompt KV cache: enabled";
    return result(env, out.str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeUnloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    unload_locked();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeModelInfo(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return result(env, model_summary_locked());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeMemoryBytes(JNIEnv *, jobject) {
    return static_cast<jlong>(rss());
}

// Stable JNI facade for agent/provider code. These forward to the existing
// MainActivity JNI entry points so both UI and agent share one model/context.
extern "C" JNIEXPORT jstring JNICALL
Java_com_redmi9_offlineai_LlamaNativeEngine_nativeEngineInfo(JNIEnv * env, jobject self) {
    return Java_com_redmi9_offlineai_MainActivity_nativeEngineInfo(env, self);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_redmi9_offlineai_LlamaNativeEngine_nativeLoadModel(JNIEnv * env, jobject self, jstring path, jint ctx, jint threads) {
    return Java_com_redmi9_offlineai_MainActivity_nativeLoadModel(env, self, path, ctx, threads);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_redmi9_offlineai_LlamaNativeEngine_nativeGenerateChat(JNIEnv * env, jobject self, jstring system, jstring history, jstring user, jint max_tokens) {
    return Java_com_redmi9_offlineai_MainActivity_nativeGenerateChat(env, self, system, history, user, max_tokens);
}
extern "C" JNIEXPORT void JNICALL
Java_com_redmi9_offlineai_LlamaNativeEngine_nativeCancelGeneration(JNIEnv * env, jobject self) {
    Java_com_redmi9_offlineai_MainActivity_nativeCancelGeneration(env, self);
}
extern "C" JNIEXPORT void JNICALL
Java_com_redmi9_offlineai_LlamaNativeEngine_nativeUnloadModel(JNIEnv * env, jobject self) {
    Java_com_redmi9_offlineai_MainActivity_nativeUnloadModel(env, self);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_redmi9_offlineai_LlamaNativeEngine_nativeModelInfo(JNIEnv * env, jobject self) {
    return Java_com_redmi9_offlineai_MainActivity_nativeModelInfo(env, self);
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_redmi9_offlineai_LlamaNativeEngine_nativeMemoryBytes(JNIEnv * env, jobject self) {
    return Java_com_redmi9_offlineai_MainActivity_nativeMemoryBytes(env, self);
}
extern "C" JNIEXPORT void JNICALL
Java_com_redmi9_offlineai_LlamaNativeEngine_nativeGenerateChatStreaming(JNIEnv * env, jobject self, jstring system, jstring history, jstring user, jint max_tokens) {
    Java_com_redmi9_offlineai_MainActivity_nativeGenerateChatStreaming(env, self, system, history, user, max_tokens);
}
