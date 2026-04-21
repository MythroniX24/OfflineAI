/**
 * llama_bridge.cpp — FD-based model loading (production approach)
 * Receives a file descriptor from Java/Kotlin via ParcelFileDescriptor.detachFd()
 * Converts it to /proc/self/fd/{n} path which llama.cpp can open.
 * This bypasses all Android storage permission issues.
 */

#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <string>
#include <vector>
#include <atomic>
#include <cstdio>

#include "llama.h"

#define TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model*   g_model = nullptr;
static llama_context* g_ctx   = nullptr;
static std::atomic<bool> g_stop{false};

// Inline batch helper (no common library dependency)
static void batch_add(llama_batch& b, llama_token tok, int pos, bool logits) {
    b.token   [b.n_tokens] = tok;
    b.pos     [b.n_tokens] = (llama_pos)pos;
    b.n_seq_id[b.n_tokens] = 1;
    b.seq_id  [b.n_tokens][0] = 0;
    b.logits  [b.n_tokens] = logits ? 1 : 0;
    b.n_tokens++;
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

/**
 * Load model via file descriptor.
 * fd: raw FD from ParcelFileDescriptor.detachFd() — JNI owns it after this call.
 * We convert it to /proc/self/fd/{n} which llama.cpp opens like a normal file.
 */
JNIEXPORT jint JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeLoadModelFd(
        JNIEnv* env, jobject, jint fd, jint nThreads, jint nCtx) {

    if (g_ctx)   { llama_free(g_ctx);        g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    if (fd < 0) {
        LOGE("Invalid fd=%d", fd);
        return -1;
    }

    // Duplicate FD so llama.cpp owns its own copy
    int dup_fd = dup(fd);
    close(fd);  // close caller's copy

    if (dup_fd < 0) {
        LOGE("dup() failed for fd=%d errno=%d", fd, errno);
        return -2;
    }

    // Build /proc/self/fd/{n} path — llama.cpp opens this like any file
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", dup_fd);
    LOGI("Loading model from FD path: %s", path);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.use_mmap  = false;   // CRITICAL: mmap fails on Android /proc/self/fd paths
    mparams.use_mlock = false;   // CRITICAL: mlock not needed, reduces RAM pressure

    g_model = llama_model_load_from_file(path, mparams);
    close(dup_fd);  // llama.cpp has its own handle now, close ours

    if (!g_model) {
        LOGE("llama_model_load_from_file failed for fd path: %s", path);
        return -3;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t)nCtx;
    cparams.n_threads       = (uint32_t)nThreads;
    cparams.n_threads_batch = (uint32_t)nThreads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("llama_init_from_model failed");
        llama_model_free(g_model);
        g_model = nullptr;
        return -4;
    }

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    LOGI("Model loaded OK. vocab=%d ctx=%d threads=%d",
         llama_vocab_n_tokens(vocab), nCtx, nThreads);
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

    llama_batch batch = llama_batch_init(std::max(512, n_tokens + 1), 0, 1);
    for (int i = 0; i < n_tokens; i++)
        batch_add(batch, tokens[i], i, i == n_tokens - 1);

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

    for (int n = 0; n < maxTokens && !g_stop.load(); n++) {
        llama_token tok = llama_sampler_sample(sampler, g_ctx, batch.n_tokens - 1);
        if (tok == eos) break;

        char buf[64] = {};
        int len = llama_token_to_piece(vocab, tok, buf, sizeof(buf) - 1, 0, false);
        if (len > 0) {
            buf[len] = '\0';
            result += buf;
            jstring jp = env->NewStringUTF(buf);
            env->CallVoidMethod(callback, onToken, jp);
            env->DeleteLocalRef(jp);
        }

        batch.n_tokens = 0;
        batch_add(batch, tok, n_cur++, true);
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
    LOGI("Model freed");
}

JNIEXPORT jstring JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeGetModelInfo(JNIEnv* env, jobject) {
    if (!g_model) return env->NewStringUTF("{}");
    char buf[256] = {};
    llama_model_desc(g_model, buf, sizeof(buf));
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    std::string info = std::string("{\"desc\":\"") + buf + "\","
                     + "\"n_vocab\":" + std::to_string(llama_vocab_n_tokens(vocab)) + ","
                     + "\"n_ctx_train\":" + std::to_string(llama_model_n_ctx_train(g_model)) + "}";
    return env->NewStringUTF(info.c_str());
}

} // extern "C"
