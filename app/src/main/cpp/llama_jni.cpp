/**
 * llama_jni.cpp — JNI bridge between the Android Kotlin layer and llama.cpp.
 *
 * When LLAMA_CPP_AVAILABLE is defined (by adding llama.cpp sources to the
 * CMakeLists.txt subdirectory and defining the macro), this file uses the
 * real llama.cpp API for on-device GGUF inference.
 *
 * Without LLAMA_CPP_AVAILABLE the file compiles a lightweight stub that:
 *   - Reports STUB_RESPONSE_MARKER so the Kotlin layer knows to use its
 *     own rule-based fallback.
 *   - Still provides the complete JNI surface so the build succeeds and the
 *     integration architecture is fully exercised.
 *
 * Adding real llama.cpp:
 *   1. git submodule add https://github.com/ggerganov/llama.cpp \
 *          app/src/main/cpp/llama.cpp
 *   2. In CMakeLists.txt uncomment add_subdirectory(llama.cpp),
 *      target_compile_definitions(...LLAMA_CPP_AVAILABLE) and link llama/ggml.
 *   3. Rebuild — no changes to this file are required.
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <string>
#include <atomic>

#ifdef LLAMA_CPP_AVAILABLE
#include "llama.cpp/include/llama.h"
#endif

#define LOG_TAG "LlamaJni"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...)  __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Kotlin checks for this marker to know it should use the rule-based fallback.
static const char* STUB_RESPONSE_MARKER = "__LLAMA_STUB__";

// Maximum number of tokens generated per call (can be overridden via nativeInit).
static const int DEFAULT_MAX_TOKENS = 256;

// --------------------------------------------------------------------------
// Native context wrapper
// --------------------------------------------------------------------------
struct NativeContext {
    std::string modelPath;
    int nCtx;
    int nThreads;
    std::atomic<bool> cancelled{false};

#ifdef LLAMA_CPP_AVAILABLE
    llama_model*   model   = nullptr;
    llama_context* lctx    = nullptr;
#endif
};

// --------------------------------------------------------------------------
// Helper: convert jstring → std::string
// --------------------------------------------------------------------------
static std::string jstringToStdString(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* raw = env->GetStringUTFChars(js, nullptr);
    std::string result(raw ? raw : "");
    env->ReleaseStringUTFChars(js, raw);
    return result;
}

// --------------------------------------------------------------------------
// nativeInit
// --------------------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_starbucknotetaker_LlamaJni_nativeInit(
        JNIEnv* env,
        jobject /* thiz */,
        jstring jModelPath,
        jint    nCtx,
        jint    nThreads) {

    std::string modelPath = jstringToStdString(env, jModelPath);
    LOGI("nativeInit: modelPath=%s nCtx=%d nThreads=%d", modelPath.c_str(), nCtx, nThreads);

    auto* ctx = new NativeContext();
    ctx->modelPath = modelPath;
    ctx->nCtx      = nCtx;
    ctx->nThreads  = nThreads;

#ifdef LLAMA_CPP_AVAILABLE
    llama_model_params mparams = llama_model_default_params();
    ctx->model = llama_load_model_from_file(modelPath.c_str(), mparams);
    if (!ctx->model) {
        LOGE("nativeInit: failed to load model from %s", modelPath.c_str());
        delete ctx;
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = static_cast<uint32_t>(nCtx);
    cparams.n_threads = static_cast<uint32_t>(nThreads);
    ctx->lctx = llama_new_context_with_model(ctx->model, cparams);
    if (!ctx->lctx) {
        LOGE("nativeInit: failed to create llama context");
        llama_free_model(ctx->model);
        delete ctx;
        return 0L;
    }
    LOGI("nativeInit: model loaded successfully");
#else
    LOGW("nativeInit: llama.cpp not compiled in — stub mode");
#endif

    return reinterpret_cast<jlong>(ctx);
}

// --------------------------------------------------------------------------
// nativeGenerate
// --------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_starbucknotetaker_LlamaJni_nativeGenerate(
        JNIEnv*  env,
        jobject  /* thiz */,
        jlong    handle,
        jstring  jPrompt,
        jint     maxTokens,
        jobject  jCallback) {

    if (handle == 0L) {
        LOGE("nativeGenerate: null handle");
        return env->NewStringUTF(STUB_RESPONSE_MARKER);
    }

    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    ctx->cancelled.store(false);

    std::string prompt = jstringToStdString(env, jPrompt);
    LOGI("nativeGenerate: prompt_len=%zu maxTokens=%d", prompt.size(), maxTokens);

#ifdef LLAMA_CPP_AVAILABLE
    // --- Real llama.cpp inference path ---
    jclass callbackClass = env->GetObjectClass(jCallback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken",
                                               "(Ljava/lang/String;)Z");

    // Tokenise
    std::vector<llama_token> promptTokens(prompt.size() + 8);
    int nPromptTokens = llama_tokenize(ctx->model, prompt.c_str(),
                                       static_cast<int32_t>(prompt.size()),
                                       promptTokens.data(),
                                       static_cast<int32_t>(promptTokens.size()),
                                       /* add_bos */ true, /* special */ true);
    if (nPromptTokens < 0) {
        LOGE("nativeGenerate: tokenization failed (%d)", nPromptTokens);
        return env->NewStringUTF(STUB_RESPONSE_MARKER);
    }
    promptTokens.resize(static_cast<size_t>(nPromptTokens));

    llama_kv_cache_clear(ctx->lctx);

    // Decode prompt
    llama_batch batch = llama_batch_init(512, 0, 1);
    for (int i = 0; i < nPromptTokens; ++i) {
        llama_batch_add(batch, promptTokens[i], i, {0}, false);
    }
    batch.logits[batch.n_tokens - 1] = true;

    if (llama_decode(ctx->lctx, batch) != 0) {
        LOGE("nativeGenerate: prompt decode failed");
        llama_batch_free(batch);
        return env->NewStringUTF(STUB_RESPONSE_MARKER);
    }
    llama_batch_free(batch);

    // Sample + generate
    std::string response;
    int nGenerated = 0;
    int nCur = nPromptTokens;

    llama_token eosToken = llama_token_eos(ctx->model);

    while (nGenerated < maxTokens && !ctx->cancelled.load()) {
        llama_token_data_array candidates;
        const float* logits = llama_get_logits_ith(ctx->lctx, -1);
        int nVocab = llama_n_vocab(ctx->model);

        std::vector<llama_token_data> tokenData(static_cast<size_t>(nVocab));
        for (int i = 0; i < nVocab; ++i) {
            tokenData[i] = {i, logits[i], 0.0f};
        }
        candidates.data  = tokenData.data();
        candidates.size  = static_cast<size_t>(nVocab);
        candidates.sorted = false;

        llama_sample_top_p(ctx->lctx, &candidates, 0.9f, 1);
        llama_sample_temp(ctx->lctx, &candidates, 0.8f);
        llama_token newToken = llama_sample_token(ctx->lctx, &candidates);

        if (newToken == eosToken) break;

        char piece[256] = {};
        llama_token_to_piece(ctx->model, newToken, piece, sizeof(piece), 0, false);
        std::string pieceStr(piece);
        response += pieceStr;
        ++nGenerated;

        // Deliver token to Kotlin callback
        if (jCallback && onTokenMethod) {
            jstring jPiece = env->NewStringUTF(pieceStr.c_str());
            jboolean keepGoing = env->CallBooleanMethod(jCallback, onTokenMethod, jPiece);
            env->DeleteLocalRef(jPiece);
            if (!keepGoing) break;
        }

        // Feed token back
        llama_batch nextBatch = llama_batch_init(1, 0, 1);
        llama_batch_add(nextBatch, newToken, nCur++, {0}, true);
        llama_decode(ctx->lctx, nextBatch);
        llama_batch_free(nextBatch);
    }

    return env->NewStringUTF(response.c_str());

#else
    // --- Stub path: signal Kotlin to use its rule-based fallback ---
    (void)prompt;
    (void)maxTokens;
    (void)jCallback;
    return env->NewStringUTF(STUB_RESPONSE_MARKER);
#endif
}

// --------------------------------------------------------------------------
// nativeRelease
// --------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_example_starbucknotetaker_LlamaJni_nativeRelease(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong   handle) {

    if (handle == 0L) return;
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    ctx->cancelled.store(true);

#ifdef LLAMA_CPP_AVAILABLE
    if (ctx->lctx)  llama_free(ctx->lctx);
    if (ctx->model) llama_free_model(ctx->model);
#endif

    delete ctx;
    LOGI("nativeRelease: context freed");
}
