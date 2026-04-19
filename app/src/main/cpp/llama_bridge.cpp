/**
 * llama_bridge.cpp
 * JNI bridge — updated for llama.cpp new API (post b3000)
 * All deprecated functions replaced with new equivalents.
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>

#include "llama.h"
#include "common/common.h"

#define TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Global state ──────────────────────────────────────────────────────────────
static llama_model*   g_model = nullptr;
static llama_context* g_ctx   = nullptr;
static std::atomic<bool> g_stop{false};

// ── Helper ────────────────────────────────────────────────────────────────────
static std::string jstr(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" {

// ── Init backend ─────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeInit(JNIEnv*, jobject) {
    llama_backend_init();   // NEW API: no arguments
    LOGI("llama backend initialized");
}

// ── Load model ────────────────────────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeLoadModel(
        JNIEnv* env, jobject, jstring jpath, jint nThreads, jint nCtx) {

    if (g_ctx)   { llama_free(g_ctx);        g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }  // NEW: llama_model_free

    std::string path = jstr(env, jpath);
    LOGI("Loading model: %s", path.c_str());

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    // NEW API: llama_model_load_from_file
    g_model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g_model) {
        LOGE("Failed to load model");
        return -1;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx          = (uint32_t)nCtx;
    cparams.n_threads      = (uint32_t)nThreads;
    cparams.n_threads_batch = (uint32_t)nThreads;

    // NEW API: llama_init_from_model
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return -1;
    }

    // NEW API: get vocab via llama_model_get_vocab, then llama_vocab_n_tokens
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    LOGI("Model loaded. Vocab: %d, ctx: %d",
         llama_vocab_n_tokens(vocab), nCtx);
    return 0;
}

// ── Inference ─────────────────────────────────────────────────────────────────
JNIEXPORT jstring JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeInfer(
        JNIEnv* env, jobject, jstring jprompt, jint maxTokens, jobject callback) {

    if (!g_model || !g_ctx) return env->NewStringUTF("[ERROR: Model not loaded]");

    g_stop.store(false);
    std::string prompt = jstr(env, jprompt);

    // Get token callback method
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    if (!onToken) return env->NewStringUTF("[ERROR: Callback not found]");

    // NEW API: vocab from model
    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    // Tokenize — NEW API: pass vocab instead of model
    std::vector<llama_token> tokens(prompt.size() + 64);
    int n_tokens = llama_tokenize(
        vocab,
        prompt.c_str(), (int32_t)prompt.size(),
        tokens.data(), (int32_t)tokens.size(),
        true,   // add_special
        true    // parse_special
    );
    if (n_tokens < 0) return env->NewStringUTF("[ERROR: Tokenization failed]");
    tokens.resize(n_tokens);

    // Build batch and decode prompt
    llama_batch batch = llama_batch_init(512, 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        common_batch_add(batch, tokens[i], i, {0}, false);  // NEW: common_batch_add
    }
    if (batch.n_tokens > 0)
        batch.logits[batch.n_tokens - 1] = true;

    if (llama_decode(g_ctx, batch) != 0) {
        llama_batch_free(batch);
        return env->NewStringUTF("[ERROR: Decode failed]");
    }

    // NEW sampler chain API
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // NEW: EOS token via vocab
    llama_token eos = llama_vocab_eos(vocab);

    std::string result;
    int n_cur = n_tokens;
    int n_gen = 0;

    while (n_gen < maxTokens && !g_stop.load()) {
        // NEW sampler API: llama_sampler_sample
        llama_token new_token = llama_sampler_sample(sampler, g_ctx, batch.n_tokens - 1);

        if (new_token == eos) break;

        // NEW: token_to_piece via vocab
        char buf[64] = {};
        int len = llama_token_to_piece(vocab, new_token, buf, sizeof(buf) - 1, 0, false);
        if (len < 0) len = 0;
        buf[len] = '\0';

        std::string piece(buf, len);
        result += piece;

        // Stream to Kotlin
        jstring jpiece = env->NewStringUTF(piece.c_str());
        env->CallVoidMethod(callback, onToken, jpiece);
        env->DeleteLocalRef(jpiece);

        // Next batch
        batch.n_tokens = 0;  // llama_batch_clear removed in new API
        common_batch_add(batch, new_token, n_cur, {0}, true);
        n_cur++;
        n_gen++;

        if (llama_decode(g_ctx, batch) != 0) break;
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);
    return env->NewStringUTF(result.c_str());
}

// ── Stop ─────────────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeStop(JNIEnv*, jobject) {
    g_stop.store(true);
}

// ── Free ─────────────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeFree(JNIEnv*, jobject) {
    g_stop.store(true);
    if (g_ctx)   { llama_free(g_ctx);        g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_free();
    LOGI("Model freed");
}

// ── Model info ────────────────────────────────────────────────────────────────
JNIEXPORT jstring JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeGetModelInfo(JNIEnv* env, jobject) {
    if (!g_model) return env->NewStringUTF("{}");

    char buf[256] = {};
    llama_model_desc(g_model, buf, sizeof(buf));

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    std::string info = "{";
    info += "\"desc\":\"" + std::string(buf) + "\",";
    info += "\"n_vocab\":" + std::to_string(llama_vocab_n_tokens(vocab)) + ",";
    info += "\"n_ctx_train\":" + std::to_string(llama_model_n_ctx_train(g_model));
    info += "}";

    return env->NewStringUTF(info.c_str());
}

} // extern "C"
