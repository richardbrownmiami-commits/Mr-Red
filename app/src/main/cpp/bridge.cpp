#include <jni.h>
#include "llama.h"
#include <algorithm>
#include <mutex>
#include <string>
#include <vector>

static llama_model * model = nullptr;
static llama_context * ctx = nullptr;
static std::mutex guard;
static std::vector<llama_token> cached_tokens;
static uint32_t context_size = 0;

static bool decode_tokens(const std::vector<llama_token> & tokens, size_t start, size_t base_position) {
    while (start < tokens.size()) {
        const size_t count = std::min<size_t>(32, tokens.size() - start);
        llama_batch batch = llama_batch_init((int32_t) count, 0, 1);
        batch.n_tokens = (int32_t) count;
        for (size_t i = 0; i < count; ++i) {
            const size_t pos = start + i;
            batch.token[i] = tokens[pos];
            batch.pos[i] = (llama_pos) (base_position + pos);
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (pos + 1 == tokens.size());
        }
        const int result = llama_decode(ctx, batch);
        llama_batch_free(batch);
        if (result != 0) return false;
        start += count;
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_arick_androidlocalllmlab_NativeLlmBridge_load(JNIEnv * env, jobject, jstring path, jint contextSize) {
    std::lock_guard<std::mutex> lock(guard);
    if (ctx || model) return JNI_FALSE;
    const char * file = env->GetStringUTFChars(path, nullptr);
    llama_backend_init();
    auto mp = llama_model_default_params();
    model = llama_model_load_from_file(file, mp);
    env->ReleaseStringUTFChars(path, file);
    if (!model) { llama_backend_free(); return JNI_FALSE; }
    auto cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) std::max(256, contextSize);
    cp.n_batch = 32;
    cp.n_ubatch = 32;
    cp.n_seq_max = 1;
    ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model); model = nullptr; llama_backend_free();
        return JNI_FALSE;
    }
    context_size = llama_n_ctx(ctx);
    cached_tokens.clear();
    return JNI_TRUE;
}


extern "C" JNIEXPORT jboolean JNICALL
Java_com_arick_androidlocalllmlab_NativeLlmBridge_primeSystemPrompt(JNIEnv * env, jobject, jstring prompt) {
    std::lock_guard<std::mutex> lock(guard);
    if (!ctx || !cached_tokens.empty()) return JNI_FALSE;
    const char * chars = env->GetStringUTFChars(prompt, nullptr);
    const std::string input(chars);
    env->ReleaseStringUTFChars(prompt, chars);
    const auto * vocab = llama_model_get_vocab(model);
    int32_t needed = llama_tokenize(vocab, input.c_str(), (int32_t) input.size(), nullptr, 0, true, true);
    if (needed < 0) needed = -needed;
    std::vector<llama_token> tokens((size_t) needed + 1);
    int32_t count = llama_tokenize(vocab, input.c_str(), (int32_t) input.size(), tokens.data(), (int32_t) tokens.size(), true, true);
    if (count < 0) return JNI_FALSE;
    tokens.resize((size_t) count);
    if (tokens.size() >= context_size || !decode_tokens(tokens, 0, 0)) {
        llama_kv_self_clear(ctx);
        cached_tokens.clear();
        return JNI_FALSE;
    }
    cached_tokens = std::move(tokens);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_arick_androidlocalllmlab_NativeLlmBridge_generate(JNIEnv * env, jobject, jstring prompt, jint maxTokens) {
    std::lock_guard<std::mutex> lock(guard);
    if (!ctx) return env->NewStringUTF("Model is not loaded");
    const char * chars = env->GetStringUTFChars(prompt, nullptr);
    const std::string input(chars);
    env->ReleaseStringUTFChars(prompt, chars);

    const auto * vocab = llama_model_get_vocab(model);
    int32_t needed = llama_tokenize(vocab, input.c_str(), (int32_t) input.size(), nullptr, 0, true, true);
    if (needed < 0) needed = -needed;
    std::vector<llama_token> prompt_tokens((size_t) needed + 1);
    int32_t token_count = llama_tokenize(vocab, input.c_str(), (int32_t) input.size(),
                                         prompt_tokens.data(), (int32_t) prompt_tokens.size(), true, true);
    if (token_count < 0) return env->NewStringUTF("Prompt tokenization failed");
    prompt_tokens.resize((size_t) token_count);

    // The system prompt is primed once. Each request appends only its new turn
    // to the existing KV sequence; no prompt-prefix token matching is performed.
    const size_t base_position = cached_tokens.size();
    const size_t reserve = (size_t) std::max(1, maxTokens);
    if (base_position + prompt_tokens.size() + reserve > context_size)
        return env->NewStringUTF("Context is full. Clear the conversation or reload with a larger context size.");

    if (!prompt_tokens.empty()) {
        if (!decode_tokens(prompt_tokens, 0, base_position)) {
            llama_kv_self_clear(ctx); cached_tokens.clear();
            return env->NewStringUTF("Input decode failed; KV cache was reset");
        }
        cached_tokens.insert(cached_tokens.end(), prompt_tokens.begin(), prompt_tokens.end());
    }

    auto * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    std::string output;
    const int limit = std::max(1, std::min(maxTokens, 2048));
    for (int i = 0; i < limit; ++i) {
        const llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (token == llama_vocab_eos(vocab)) break;
        char piece[512];
        const int piece_size = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (piece_size > 0) output.append(piece, (size_t) piece_size);

        llama_batch batch = llama_batch_init(1, 0, 1);
        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = (llama_pos) cached_tokens.size();
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;
        const int result = llama_decode(ctx, batch);
        llama_batch_free(batch);
        if (result != 0) break;
        cached_tokens.push_back(token);
    }
    llama_sampler_free(sampler);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_arick_androidlocalllmlab_NativeLlmBridge_unload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(guard);
    if (ctx) { llama_free(ctx); ctx = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; }
    cached_tokens.clear();
    context_size = 0;
    llama_backend_free();
}

extern "C" JNIEXPORT void JNICALL
Java_com_arick_androidlocalllmlab_NativeLlmBridge_clearConversation(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(guard);
    if (ctx) llama_kv_self_clear(ctx);
    cached_tokens.clear();
}
