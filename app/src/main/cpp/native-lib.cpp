#include <jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
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
std::atomic<int> g_pending{0};   // requests issued but not yet finished (incl. waiting on g_mutex)
std::once_flag g_backend_once;

// FIX 3: cancel race. The abort flag is no longer reset inside generate().
// It is only cleared when the last outstanding request finishes, so a cancel
// that arrives while a request is still waiting on g_mutex is honoured.
struct RequestGuard {
    RequestGuard() { g_pending.fetch_add(1, std::memory_order_acq_rel); }
    ~RequestGuard() {
        if (g_pending.fetch_sub(1, std::memory_order_acq_rel) == 1)
            g_abort.store(false, std::memory_order_relaxed);
    }
};

static bool llamaAbortCallback(void *) {
    return g_abort.load(std::memory_order_relaxed);
}

llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
llama_sampler *g_sampler = nullptr;
std::string g_model_path;
int g_threads = 1;
uint32_t g_context = 1024;
uint32_t g_requested_context = 0;   // what the app asked for (g_context may be auto-lowered)
constexpr int kMaxReply = 256;      // was 96
std::vector<llama_token> g_cached_prompt_tokens;

void ensure_backend() {
    std::call_once(g_backend_once, [] { llama_backend_init(); });
}

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

// ---------------------------------------------------------------------------
// FIX 2: UTF-8 safety. Token pieces can split multi-byte characters, and
// NewStringUTF() wants *modified* UTF-8 (no 4-byte sequences). We buffer
// incomplete tails and convert to UTF-16 ourselves, then use NewString().
// ---------------------------------------------------------------------------
size_t utf8_complete_prefix(const std::string & s) {
    const size_t n = s.size();
    for (size_t back = 1; back <= 3 && back <= n; ++back) {
        unsigned char c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue;  // continuation byte, keep looking back
        size_t need = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
        return need > back ? n - back : n;
    }
    return n;
}

std::u16string utf8_to_utf16(const std::string & s) {
    std::u16string out;
    out.reserve(s.size());
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        uint32_t cp;
        size_t len;
        if (c < 0x80)              { cp = c;        len = 1; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; len = 2; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; len = 3; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; len = 4; }
        else { out.push_back(0xFFFD); ++i; continue; }
        if (i + len > n) { out.push_back(0xFFFD); break; }
        bool ok = true;
        for (size_t k = 1; k < len; ++k) {
            unsigned char cc = static_cast<unsigned char>(s[i + k]);
            if ((cc & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!ok) { out.push_back(0xFFFD); ++i; continue; }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<char16_t>(cp));
        }
        i += len;
    }
    return out;
}

jstring to_jstring(JNIEnv * env, const std::string & s) {
    std::u16string u = utf8_to_utf16(s);
    return env->NewString(reinterpret_cast<const jchar *>(u.data()), static_cast<jsize>(u.size()));
}

jstring result(JNIEnv * env, const std::string & value) {
    return to_jstring(env, value);
}

// FIX (smaller): jmethodID looked up once per call, exceptions checked.
struct Notifier {
    JNIEnv * env = nullptr;
    jobject self = nullptr;
    jmethodID method = nullptr;

    Notifier(JNIEnv * e, jobject s) : env(e), self(s) {
        jclass cls = env->GetObjectClass(self);
        if (cls) {
            method = env->GetMethodID(cls, "onNativeToken", "(Ljava/lang/String;)V");
            env->DeleteLocalRef(cls);
        }
        if (env->ExceptionCheck()) { env->ExceptionClear(); method = nullptr; }
    }

    // Returns false if the callback threw, so callers can stop generating.
    bool operator()(const std::string & text) const {
        if (!method) return true;
        jstring v = to_jstring(env, text);
        if (!v) { if (env->ExceptionCheck()) env->ExceptionClear(); return false; }
        env->CallVoidMethod(self, method, v);
        env->DeleteLocalRef(v);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return true;
    }
};

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
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);             g_ctx = nullptr; }
    if (g_model)   { llama_model_free(g_model);     g_model = nullptr; }
    g_model_path.clear();
    clear_prompt_cache_locked();
}

bool reset_sampler_locked(bool greedy = false) {
    if (!g_model || !g_ctx) return false;
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!g_sampler) return false;
    if (greedy) {
        llama_sampler_chain_add(g_sampler, llama_sampler_init_greedy());
    } else {
        // MAGIC 1: natural answers. Penalise repeats, sample from the likely
        // words instead of always the single top one.
        llama_sampler_chain_add(g_sampler, llama_sampler_init_penalties(64, 1.10f, 0.0f, 0.0f));
        llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.90f, 1));
        llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.70f));
        const uint32_t seed = static_cast<uint32_t>(
            std::chrono::steady_clock::now().time_since_epoch().count());
        llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(seed));
    }
    return true;
}

bool recreate_context_locked() {
    if (!g_model) return false;
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);             g_ctx = nullptr; }
    clear_prompt_cache_locked();

    auto p = llama_context_default_params();
    p.n_ctx = g_context;
    // Bound temporary prompt-evaluation buffers on 32-bit devices while leaving
    // the context/KV capacity independently configurable.
    p.n_batch = std::min<uint32_t>(g_context, 64);
    p.n_ubatch = std::min<uint32_t>(g_context, 32);
    p.abort_callback = llamaAbortCallback;
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

// Takes parsed turns directly: no re-encode/re-parse round trip through the
// \x1e / \x1f separators when the budget loop trims history.
bool format_chat(const std::string & system,
                 const std::vector<Turn> & hist,
                 const std::string & user,
                 std::string & out) {
    if (!g_model || user.empty()) return false;

    std::vector<Turn> turns{{"system", system}};
    turns.insert(turns.end(), hist.begin(), hist.end());
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

// FIX 4: double BOS. If the templated prompt already starts with the BOS text,
// don't let the tokenizer add another one.
bool prompt_has_bos(const llama_vocab * vocab, const std::string & prompt) {
    llama_token bos = llama_vocab_bos(vocab);
    if (bos < 0) return false;
    const char * text = llama_vocab_get_text(vocab, bos);
    return text && *text && prompt.rfind(text, 0) == 0;
}

bool tokenize_prompt(const llama_vocab * vocab, const std::string & prompt,
                     std::vector<llama_token> & out) {
    const bool add_special = !prompt_has_bos(vocab, prompt);
    int n = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                            nullptr, 0, add_special, true);
    if (n <= 0) return false;
    out.resize(static_cast<size_t>(n));
    int r = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                           out.data(), static_cast<int32_t>(out.size()), add_special, true);
    if (r < 0) return false;
    out.resize(static_cast<size_t>(r));
    return true;
}

bool format_chat_with_budget(const std::string & system,
                             const std::string & encoded_history,
                             const std::string & user,
                             int limit,
                             std::string & out,
                             int * prompt_tokens_out = nullptr) {
    if (!g_model) return false;
    auto * vocab = llama_model_get_vocab(g_model);
    if (!vocab) return false;

    auto turns = history(encoded_history);
    const int safety = std::max(4, limit + 2);

    while (true) {
        if (!format_chat(system, turns, user, out)) return false;

        std::vector<llama_token> toks;
        if (tokenize_prompt(vocab, out, toks) &&
            static_cast<uint32_t>(toks.size() + safety) < g_context) {
            if (prompt_tokens_out) *prompt_tokens_out = static_cast<int>(toks.size());
            return true;
        }

        if (turns.empty()) return false;

        turns.erase(turns.begin());
        if (!turns.empty() && turns.front().role == "assistant") {
            turns.erase(turns.begin());
        }
    }
}

// MAGIC 2: stop the model from writing fake "next turns" by itself.
const char * const kStops[] = {
    "<|im_start|>", "<|im_end|>", "<|eot_id|>", "<|start_header_id|>",
    "<end_of_turn>", "\nUser:", "\n[user]", "\n[assistant]"
};

// Returns how many bytes of w are safe to show. If a full stop marker is found,
// sets stop=true and returns the position where it begins. Text that might be
// the beginning of a marker is held back until we know.
size_t stop_scan(const std::string & w, bool & stop) {
    stop = false;
    size_t best = std::string::npos;
    for (const char * st : kStops) {
        size_t pos = w.find(st);
        if (pos < best) best = pos;
    }
    if (best != std::string::npos) { stop = true; return best; }
    size_t hold = 0;
    for (const char * st : kStops) {
        const size_t len = std::strlen(st);
        for (size_t k = std::min(len - 1, w.size()); k > hold; --k) {
            if (w.compare(w.size() - k, k, st, k) == 0) { hold = k; break; }
        }
    }
    return w.size() - hold;
}

// MAGIC 3: stop endless loops (the same chunk repeated 3 times at the end).
bool looks_looping(const std::string & s) {
    const size_t n = s.size();
    for (size_t p = 8; p <= 64 && p * 3 <= n; ++p) {
        bool same = true;
        for (size_t k = 0; k < p * 2; ++k) {
            if (s[n - 1 - k] != s[n - 1 - k - p]) { same = false; break; }
        }
        if (same) return true;
    }
    return false;
}

std::string generate(const std::string & prompt, int limit,
                     const Notifier * cb = nullptr, int * generated_tokens_out = nullptr,
                     bool greedy = false) {
    if (generated_tokens_out) *generated_tokens_out = 0;
    // NOTE: g_abort is intentionally NOT reset here (see RequestGuard).
    if (!g_model || !g_ctx || !reset_sampler_locked(greedy)) return {};
    llama_set_abort_callback(g_ctx, llamaAbortCallback, nullptr);

    auto * vocab = llama_model_get_vocab(g_model);
    auto * memory = llama_get_memory(g_ctx);
    if (!vocab || !memory) return {};

    std::vector<llama_token> tokens;
    if (!tokenize_prompt(vocab, prompt, tokens)) return {};
    if (static_cast<uint32_t>(tokens.size() + limit + 2) >= g_context) return {};

    size_t common = 0;
    while (common < g_cached_prompt_tokens.size() &&
           common < tokens.size() &&
           g_cached_prompt_tokens[common] == tokens[common]) {
        ++common;
    }

    const bool first_context = llama_memory_seq_pos_max(memory, 0) < 0;
    if (first_context || g_cached_prompt_tokens.empty()) common = 0;

    // FIX 1: the KV cache is about to be modified, so the token cache is not
    // trustworthy again until prefill has fully succeeded. Any early return
    // below (decode failure, cancel) leaves it empty, which forces a full
    // re-prefill next time instead of reusing a mismatched prefix.
    clear_prompt_cache_locked();

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

    g_cached_prompt_tokens = tokens;  // prefill complete: cache is valid again

    std::string result;
    result.reserve(static_cast<size_t>(limit) * 4);
    std::string pending;  // bytes not yet known to form complete UTF-8 characters
    std::string window;   // complete text waiting for the stop-marker check
    bool stopped = false;

    auto emit = [&](const std::string & t) -> bool {
        if (t.empty()) return true;
        result += t;
        return !cb || (*cb)(t);
    };
    auto drain = [&](bool final_flush) -> bool {
        size_t n = final_flush ? pending.size() : utf8_complete_prefix(pending);
        window.append(pending, 0, n);
        pending.erase(0, n);
        bool stop = false;
        size_t safe = stop_scan(window, stop);
        if (stop) {
            stopped = true;
            bool ok = emit(window.substr(0, safe));
            window.clear();
            return ok;
        }
        if (final_flush) safe = window.size();
        bool ok = emit(window.substr(0, safe));
        window.erase(0, safe);
        return ok;
    };

    int generated_tokens = 0;
    for (int i = 0; i < limit; ++i) {
        if (g_abort.load(std::memory_order_relaxed)) break;

        // llama_sampler_sample() already calls llama_sampler_accept() internally.
        llama_token token = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (token < 0 || llama_vocab_is_eog(vocab, token)) break;
        ++generated_tokens;

        char piece_buffer[512];
        int n = llama_token_to_piece(vocab, token, piece_buffer, sizeof(piece_buffer), 0, true);
        if (n > 0) pending.append(piece_buffer, static_cast<size_t>(n));
        if (!drain(false) || stopped) break;
        if (looks_looping(result)) break;

        if (static_cast<uint32_t>(tokens.size() + i + 2) >= g_context) break;

        llama_token next = token;
        auto batch = llama_batch_get_one(&next, 1);
        if (llama_decode(g_ctx, batch) != 0) break;
    }
    if (!stopped) drain(true);

    if (generated_tokens_out) *generated_tokens_out = generated_tokens;
    return result;
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
        << "\nModel weights: memory-mapped, not locked in RAM"
        << "\nPrompt KV cache: " << g_cached_prompt_tokens.size() << " prompt tokens cached"
        << "\nRSS: " << rss() << " bytes";
    return out.str();
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_redmi9_offlineai_MainActivity_nativeEngineInfo(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ensure_backend();
    std::ostringstream out;
    out << "llama.cpp: " << (llama_version() ? llama_version() : "unknown")
        << "\nABI: armeabi-v7a"
        << "\nCPU threads: adaptive 1-" << choose_threads(0)
  
