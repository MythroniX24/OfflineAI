/**
 * llama_bridge.cpp
 * JNI bridge for llama.cpp (new API, no common dependency)
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>

#include "llama.h"

#define TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model*   g_model = nullptr;
static llama_context* g_ctx   = nullptr;
static std::atomic<bool> g_stop{false};

// ── Inline batch helper (replaces common_batch_add) ─────────────────────────
static void batch_add(llama_batch& batch, llama_token token, int pos,
                      bool get_logits) {
    batch.token   [batch.n_tokens] = token;
    batch.pos     [batch.n_tokens] = (llama_pos)pos;
    batch.n_seq_id[batch.n_tokens] = 1;
    batch.seq_id  [batch.n_tokens][0] = 0;
    batch.logits  [batch.n_tokens] = get_logits ? 1 : 0;
    batch.n_tokens++;
}

static std::string jstr(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeInit(JNIEnv*, jobject) {
    llama_backend_init();
    LOGI("llama backend initialized");
}

JNIEXPORT jint JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeLoadModel(
        JNIEnv* env, jobject, jstring jpath, jint nThreads, jint nCtx) {

    if (g_ctx)   { llama_free(g_ctx);        g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    std::string path = jstr(env, jpath);
    LOGI("Loading model: %s", path.c_str());

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g_model) { LOGE("Failed to load model"); return -1; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t)nCtx;
    cparams.n_threads       = (uint32_t)nThreads;
    cparams.n_threads_batch = (uint32_t)nThreads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return -1;
    }

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    LOGI("Model loaded. Vocab: %d, ctx: %d", llama_vocab_n_tokens(vocab), nCtx);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeInfer(
        JNIEnv* env, jobject, jstring jprompt, jint maxTokens, jobject callback) {

    if (!g_model || !g_ctx) return env->NewStringUTF("[ERROR: Model not loaded]");

    g_stop.store(false);
    std::string prompt = jstr(env, jprompt);

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    if (!onToken) return env->NewStringUTF("[ERROR: Callback not found]");

    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    // Tokenize
    std::vector<llama_token> tokens(prompt.size() + 64);
    int n_tokens = llama_tokenize(
        vocab, prompt.c_str(), (int32_t)prompt.size(),
        tokens.data(), (int32_t)tokens.size(), true, true);
    if (n_tokens < 0) return env->NewStringUTF("[ERROR: Tokenization failed]");
    tokens.resize(n_tokens);

    // Fill batch with prompt tokens
    llama_batch batch = llama_batch_init(std::max(512, n_tokens + 1), 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        batch_add(batch, tokens[i], i, (i == n_tokens - 1));
    }

    if (llama_decode(g_ctx, batch) != 0) {
        llama_batch_free(batch);
        return env->NewStringUTF("[ERROR: Decode failed]");
    }

    // Sampler chain
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_token eos = llama_vocab_eos(vocab);
    std::string result;
    int n_cur = n_tokens;
    int n_gen = 0;

    while (n_gen < maxTokens && !g_stop.load()) {
        llama_token new_token = llama_sampler_sample(sampler, g_ctx, batch.n_tokens - 1);
        if (new_token == eos) break;

        char buf[64] = {};
        int len = llama_token_to_piece(vocab, new_token, buf, sizeof(buf) - 1, 0, false);
        if (len > 0) {
            buf[len] = '\0';
            std::string piece(buf, len);
            result += piece;
            jstring jpiece = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(callback, onToken, jpiece);
            env->DeleteLocalRef(jpiece);
        }

        // Next batch
        batch.n_tokens = 0;
        batch_add(batch, new_token, n_cur, true);
        n_cur++;
        n_gen++;

        if (llama_decode(g_ctx, batch) != 0) break;
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeStop(JNIEnv*, jobject) {
    g_stop.store(true);
}

JNIEXPORT void JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeFree(JNIEnv*, jobject) {
    g_stop.store(true);
    if (g_ctx)   { llama_free(g_ctx);        g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeGetModelInfo(JNIEnv* env, jobject) {
    if (!g_model) return env->NewStringUTF("{}");
    char buf[256] = {};
    llama_model_desc(g_model, buf, sizeof(buf));
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    std::string info = "{\"desc\":\"" + std::string(buf) + "\","
                     + "\"n_vocab\":" + std::to_string(llama_vocab_n_tokens(vocab)) + ","
                     + "\"n_ctx_train\":" + std::to_string(llama_model_n_ctx_train(g_model)) + "}";
    return env->NewStringUTF(info.c_str());
}

} // extern "C"
