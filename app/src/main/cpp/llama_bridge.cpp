/**
 * llama_bridge.cpp
 * JNI glue between Kotlin LlamaEngine and the native llama.cpp library.
 * Runs inference on a background thread; streams tokens back via callbacks.
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>
#include <cstring>

#include "llama.h"
#include "common.h"

#define TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Global state (one model + context at a time) ───────────────────────────
static llama_model*   g_model   = nullptr;
static llama_context* g_ctx     = nullptr;
static std::atomic<bool> g_stop_flag{false};

// ── JNI helpers ───────────────────────────────────────────────────────────
static jmethodID g_onToken_method = nullptr;   // Kotlin streaming callback
static jclass    g_engine_class   = nullptr;

// ── Helper: jstring → std::string ────────────────────────────────────────
static std::string jstr(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" {

/**
 * Initialize llama backend (call once on app start).
 */
JNIEXPORT void JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeInit(JNIEnv* env, jobject /*thiz*/) {
    llama_backend_init(false);
    LOGI("llama backend initialized");
}

/**
 * Load model from path. Returns 0 on success, -1 on failure.
 * nThreads: number of CPU threads to use
 * nCtx:     context size (tokens)
 */
JNIEXPORT jint JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeLoadModel(
        JNIEnv* env, jobject /*thiz*/,
        jstring modelPath,
        jint nThreads,
        jint nCtx) {

    // Free any previously loaded model
    if (g_ctx)   { llama_free(g_ctx);   g_ctx = nullptr; }
    if (g_model) { llama_free_model(g_model); g_model = nullptr; }

    std::string path = jstr(env, modelPath);
    LOGI("Loading model: %s", path.c_str());

    // Model params
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;   // CPU-only for Android compatibility

    g_model = llama_load_model_from_file(path.c_str(), mparams);
    if (!g_model) {
        LOGE("Failed to load model from %s", path.c_str());
        return -1;
    }

    // Context params
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx    = (uint32_t)nCtx;
    cparams.n_threads = (uint32_t)nThreads;
    cparams.n_threads_batch = (uint32_t)nThreads;

    g_ctx = llama_new_context_with_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create llama context");
        llama_free_model(g_model);
        g_model = nullptr;
        return -1;
    }

    LOGI("Model loaded. Vocab size: %d, n_ctx: %d", llama_n_vocab(g_model), nCtx);
    return 0;
}

/**
 * Run inference. Calls back to Kotlin with each generated token.
 * @param prompt    Full formatted prompt string
 * @param maxTokens Maximum tokens to generate
 * @param callback  Kotlin object with fun onToken(token: String)
 */
JNIEXPORT jstring JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeInfer(
        JNIEnv* env, jobject /*thiz*/,
        jstring jprompt,
        jint maxTokens,
        jobject callback) {

    if (!g_model || !g_ctx) {
        LOGE("Model not loaded");
        return env->NewStringUTF("[ERROR: Model not loaded]");
    }

    g_stop_flag.store(false);
    std::string prompt = jstr(env, jprompt);

    // Get callback method
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    if (!onToken) {
        LOGE("onToken method not found");
        return env->NewStringUTF("[ERROR: Callback not found]");
    }

    // Tokenize prompt
    std::vector<llama_token> tokens(prompt.size() + 64);
    int n_tokens = llama_tokenize(
        g_model,
        prompt.c_str(), (int)prompt.size(),
        tokens.data(), (int)tokens.size(),
        /*add_bos=*/true,
        /*special=*/true
    );
    if (n_tokens < 0) {
        LOGE("Tokenization failed");
        return env->NewStringUTF("[ERROR: Tokenization failed]");
    }
    tokens.resize(n_tokens);

    // Evaluate prompt tokens in batches
    llama_batch batch = llama_batch_init(512, 0, 1);
    int n_ctx = llama_n_ctx(g_ctx);

    // Fill batch with prompt tokens
    for (int i = 0; i < n_tokens; i++) {
        llama_batch_add(batch, tokens[i], i, {0}, false);
    }
    batch.logits[batch.n_tokens - 1] = true; // only need logits of last token

    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("llama_decode failed for prompt");
        llama_batch_free(batch);
        return env->NewStringUTF("[ERROR: Decode failed]");
    }

    // Generation loop
    std::string result;
    int n_cur = n_tokens;
    int n_gen = 0;

    llama_token eos = llama_token_eos(g_model);

    while (n_gen < maxTokens && !g_stop_flag.load()) {
        // Sample next token
        llama_token_data_array candidates_p;
        auto n_vocab = llama_n_vocab(g_model);
        std::vector<llama_token_data> candidates(n_vocab);

        const float* logits = llama_get_logits_ith(g_ctx, batch.n_tokens - 1);
        for (int i = 0; i < n_vocab; i++) {
            candidates[i] = { i, logits[i], 0.0f };
        }
        candidates_p = { candidates.data(), candidates.size(), false };

        // Apply temperature + top-p sampling
        llama_sample_temp(g_ctx, &candidates_p, 0.7f);
        llama_sample_top_p(g_ctx, &candidates_p, 0.9f, 1);
        llama_token new_token = llama_sample_token(g_ctx, &candidates_p);

        if (new_token == eos) break;

        // Decode token to string
        char buf[64] = {};
        int len = llama_token_to_piece(g_model, new_token, buf, sizeof(buf), false);
        if (len < 0) len = 0;
        buf[len] = '\0';

        std::string piece(buf, len);
        result += piece;

        // Stream token to Kotlin
        jstring jpiece = env->NewStringUTF(piece.c_str());
        env->CallVoidMethod(callback, onToken, jpiece);
        env->DeleteLocalRef(jpiece);

        // Prepare next batch
        llama_batch_clear(batch);
        llama_batch_add(batch, new_token, n_cur, {0}, true);
        n_cur++;
        n_gen++;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode failed during generation");
            break;
        }
    }

    llama_batch_free(batch);
    return env->NewStringUTF(result.c_str());
}

/**
 * Stop any ongoing inference.
 */
JNIEXPORT void JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_stop_flag.store(true);
    LOGI("Inference stop requested");
}

/**
 * Free model and context from memory.
 */
JNIEXPORT void JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_stop_flag.store(true);
    if (g_ctx)   { llama_free(g_ctx);        g_ctx   = nullptr; }
    if (g_model) { llama_free_model(g_model); g_model = nullptr; }
    llama_backend_free();
    LOGI("Model and context freed");
}

/**
 * Returns model metadata string (name, arch, params).
 */
JNIEXPORT jstring JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeGetModelInfo(JNIEnv* env, jobject /*thiz*/) {
    if (!g_model) return env->NewStringUTF("{}");

    char buf[256];
    llama_model_desc(g_model, buf, sizeof(buf));

    std::string info = "{";
    info += "\"desc\":\"" + std::string(buf) + "\",";
    info += "\"n_vocab\":" + std::to_string(llama_n_vocab(g_model)) + ",";
    info += "\"n_ctx_train\":" + std::to_string(llama_n_ctx_train(g_model));
    info += "}";

    return env->NewStringUTF(info.c_str());
}

} // extern "C"
