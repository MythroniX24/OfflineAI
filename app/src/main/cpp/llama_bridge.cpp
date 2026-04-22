/**
 * llama_bridge.cpp
 * Uses common_init_from_params like kotlinllamacpp/rn-llama.
 * use_mmap=false, use_mlock=false — CRITICAL for Android.
 */
#include <jni.h>
#include <unistd.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>
#include <cstdio>
#include <memory>

#include "llama.h"
#include "common/common.h"

#define TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static common_init_result_ptr g_init;
static llama_model*           g_model = nullptr;
static llama_context*         g_ctx   = nullptr;
static std::atomic<bool>      g_stop{false};

static void freeAll() {
    if (g_ctx)   { llama_free(g_ctx);        g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_init.reset();
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
    std::string r(c);
    e->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeInit(JNIEnv*, jobject) {
    llama_backend_init();
    LOGI("backend init ok");
}

JNIEXPORT jint JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeLoadModelFd(
        JNIEnv*, jobject, jint fd, jint nThreads, jint nCtx) {

    freeAll();
    if (fd < 0) { LOGE("invalid fd=%d", fd); return -1; }

    int dfd = dup(fd);
    close(fd);
    if (dfd < 0) { LOGE("dup failed errno=%d", errno); return -2; }

    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", dfd);
    LOGI("Loading from: %s  threads=%d ctx=%d", path, nThreads, nCtx);

    common_params params;
    params.model.path               = path;
    params.n_ctx                    = nCtx;
    params.cpuparams.n_threads      = nThreads;
    params.cpuparams_batch.n_threads= nThreads;
    params.n_batch                  = 512;
    params.n_parallel               = 1;
    params.use_mmap                 = false;  // CRITICAL
    params.use_mlock                = false;  // CRITICAL
    params.n_gpu_layers             = 0;
    params.flash_attn               = false;
    params.warmup                   = false;

    try {
        g_init = common_init_from_params(params);
        close(dfd);

        if (!g_init) { LOGE("common_init_from_params returned null"); return -3; }

        g_model = g_init->model();
        g_ctx   = g_init->context();

        if (!g_model || !g_ctx) {
            LOGE("null model=%p ctx=%p", g_model, g_ctx);
            freeAll(); return -4;
        }

        const llama_vocab* v = llama_model_get_vocab(g_model);
        LOGI("Loaded OK vocab=%d ctx=%d", llama_vocab_n_tokens(v), llama_n_ctx(g_ctx));
        return 0;

    } catch (const std::exception& ex) {
        close(dfd);
        LOGE("exception: %s", ex.what());
        freeAll(); return -5;
    } catch (...) {
        close(dfd);
        LOGE("unknown exception");
        freeAll(); return -6;
    }
}

JNIEXPORT jstring JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeInfer(
        JNIEnv* env, jobject, jstring jp, jint maxTok, jobject cb) {

    if (!g_model || !g_ctx) return env->NewStringUTF("[not loaded]");
    g_stop.store(false);

    std::string prompt = jstr(env, jp);
    jmethodID onToken = env->GetMethodID(
        env->GetObjectClass(cb), "onToken", "(Ljava/lang/String;)V");
    if (!onToken) return env->NewStringUTF("[callback missing]");

    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    std::vector<llama_token> tokens(prompt.size() + 64);
    int n = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                           tokens.data(), (int)tokens.size(), true, true);
    if (n < 0) return env->NewStringUTF("[tokenize fail]");
    tokens.resize(n);

    llama_batch batch = llama_batch_init(std::max(512, n + 1), 0, 1);
    for (int i = 0; i < n; i++) batch_add(batch, tokens[i], i, i == n-1);
    if (llama_decode(g_ctx, batch) != 0) {
        llama_batch_free(batch);
        return env->NewStringUTF("[decode fail]");
    }

    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string result;
    llama_token eos = llama_vocab_eos(vocab);
    int cur = n;

    for (int i = 0; i < maxTok && !g_stop.load(); i++) {
        llama_token tok = llama_sampler_sample(sampler, g_ctx, batch.n_tokens-1);
        if (tok == eos) break;

        char buf[64] = {};
        int len = llama_token_to_piece(vocab, tok, buf, sizeof(buf)-1, 0, false);
        if (len > 0) {
            buf[len] = '\0'; result += buf;
            jstring js = env->NewStringUTF(buf);
            env->CallVoidMethod(cb, onToken, js);
            env->DeleteLocalRef(js);
        }
        batch.n_tokens = 0;
        batch_add(batch, tok, cur++, true);
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
    freeAll();
    llama_backend_free();
    LOGI("freed");
}

JNIEXPORT jstring JNICALL
Java_com_om_offlineai_engine_LlamaEngine_nativeGetModelInfo(JNIEnv* env, jobject) {
    if (!g_model) return env->NewStringUTF("{}");
    char buf[256]={};
    llama_model_desc(g_model, buf, sizeof(buf));
    const llama_vocab* v = llama_model_get_vocab(g_model);
    std::string s = std::string("{\"desc\":\"") + buf
                  + "\",\"vocab\":" + std::to_string(llama_vocab_n_tokens(v)) + "}";
    return env->NewStringUTF(s.c_str());
}

} // extern "C"
