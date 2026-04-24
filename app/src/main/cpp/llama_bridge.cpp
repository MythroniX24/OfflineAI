/**
 * llama_bridge.cpp
 * Direct llama API — no common_init_from_params dependency.
 * use_mmap=false + use_mlock=false = CRITICAL for Android.
 */
#include <jni.h>
#include <unistd.h>
#include <android/log.h>
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

static void freeAll() {
    if (g_ctx)   { llama_free(g_ctx);        g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

static void batch_add(llama_batch& b, llama_token tok, int pos, bool logits) {
    b.token[b.n_tokens]     = tok;
    b.pos[b.n_tokens]       = (llama_pos)pos;
    b.n_seq_id[b.n_tokens]  = 1;
    b.seq_id[b.n_tokens][0] = 0;
    b.logits[b.n_tokens]    = logits ? 1 : 0;
    b.n_tokens++;
}

static std::string jstr(JNIEnv* e, jstring s) {
    const char* c = e->GetStringUTFChars(s, nullptr);
    std::string r(c); e->ReleaseStringUTFChars(s, c); return r;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeInit(JNIEnv*, jobject) {
    llama_backend_init();
    LOGI("backend ok");
}

JNIEXPORT jint JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeLoadModelFd(
        JNIEnv*, jobject, jint fd, jint nThreads, jint nCtx) {

    freeAll();
    if (fd < 0) { LOGE("bad fd=%d", fd); return -1; }

    int dfd = dup(fd);
    close(fd);
    if (dfd < 0) { LOGE("dup failed errno=%d", errno); return -2; }

    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", dfd);
    LOGI("load path=%s threads=%d ctx=%d", path, nThreads, nCtx);

    // Direct llama API — full control over params
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mmap     = false;   // CRITICAL: mmap can fail on Android
    mp.use_mlock    = false;   // CRITICAL: mlock not needed

    g_model = llama_model_load_from_file(path, mp);
    close(dfd);

    if (!g_model) {
        LOGE("llama_model_load_from_file returned null");
        return -3;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx            = (uint32_t)std::min(nCtx, 2048);  // cap at 2048 for low RAM
    cp.n_threads        = (uint32_t)nThreads;
    cp.n_threads_batch  = (uint32_t)nThreads;
    cp.flash_attn       = false;

    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        LOGE("llama_init_from_model failed");
        llama_model_free(g_model); g_model = nullptr;
        return -4;
    }

    const llama_vocab* v = llama_model_get_vocab(g_model);
    LOGI("loaded vocab=%d ctx=%d", llama_vocab_n_tokens(v), (int)cp.n_ctx);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeInfer(
        JNIEnv* env, jobject, jstring jp, jint maxTok, jobject cb) {

    if (!g_model || !g_ctx) return env->NewStringUTF("[not loaded]");
    g_stop.store(false);

    std::string prompt = jstr(env, jp);
    jmethodID onTok = env->GetMethodID(
        env->GetObjectClass(cb), "onToken", "(Ljava/lang/String;)V");
    if (!onTok) return env->NewStringUTF("[no callback]");

    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    std::vector<llama_token> tokens(prompt.size() + 64);
    int n = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                           tokens.data(), (int)tokens.size(), true, true);
    if (n < 0) return env->NewStringUTF("[tokenize fail]");
    tokens.resize(n);

    llama_batch batch = llama_batch_init(std::max(512, n+1), 0, 1);
    for (int i = 0; i < n; i++) batch_add(batch, tokens[i], i, i==n-1);
    if (llama_decode(g_ctx, batch) != 0) {
        llama_batch_free(batch);
        return env->NewStringUTF("[decode fail]");
    }

    llama_sampler* s = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(s, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(s, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(s, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string result;
    llama_token eos = llama_vocab_eos(vocab);
    int cur = n;

    for (int i = 0; i < maxTok && !g_stop.load(); i++) {
        llama_token tok = llama_sampler_sample(s, g_ctx, batch.n_tokens-1);
        if (tok == eos) break;
        char buf[64] = {};
        int len = llama_token_to_piece(vocab, tok, buf, sizeof(buf)-1, 0, false);
        if (len > 0) {
            buf[len]='\0'; result+=buf;
            jstring js = env->NewStringUTF(buf);
            env->CallVoidMethod(cb, onTok, js);
            env->DeleteLocalRef(js);
        }
        batch.n_tokens = 0;
        batch_add(batch, tok, cur++, true);
        if (llama_decode(g_ctx, batch) != 0) break;
    }

    llama_sampler_free(s);
    llama_batch_free(batch);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeStop(JNIEnv*, jobject) {
    g_stop.store(true);
}

JNIEXPORT void JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeFree(JNIEnv*, jobject) {
    g_stop.store(true); freeAll(); llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeGetModelInfo(JNIEnv* env, jobject) {
    if (!g_model) return env->NewStringUTF("{}");
    char buf[256]={}; llama_model_desc(g_model, buf, sizeof(buf));
    const llama_vocab* v = llama_model_get_vocab(g_model);
    std::string s = std::string("{\"desc\":\"") + buf
                  + "\",\"vocab\":" + std::to_string(llama_vocab_n_tokens(v)) + "}";
    return env->NewStringUTF(s.c_str());
}

} // extern "C"
