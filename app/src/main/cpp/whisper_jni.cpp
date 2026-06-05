// JNI bridge to whisper.cpp. Mirrors com.changyow.mediadler.transcribe.WhisperNative.
#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define LOG_TAG "whisper_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Bridges whisper.cpp's C callbacks back to a Kotlin WhisperCallback. whisper_full runs on the
// calling (JNI) thread, so the captured JNIEnv is valid inside these callbacks — no thread attach.
struct CallbackBridge {
    JNIEnv* env;
    jobject callback;       // Kotlin WhisperCallback
    jmethodID onProgress;   // (I)V
    jmethodID onSegment;    // (Ljava/lang/String;)V
    jmethodID isCancelled;  // ()Z
};

// Returning true makes whisper_full abort the current window — used by the "放棄" action.
static bool abort_bridge(void* user_data) {
    auto* b = reinterpret_cast<CallbackBridge*>(user_data);
    if (b != nullptr && b->callback != nullptr) {
        return b->env->CallBooleanMethod(b->callback, b->isCancelled) == JNI_TRUE;
    }
    return false;
}

static void progress_bridge(struct whisper_context*, struct whisper_state*, int progress, void* user_data) {
    auto* b = reinterpret_cast<CallbackBridge*>(user_data);
    if (b != nullptr && b->callback != nullptr) {
        b->env->CallVoidMethod(b->callback, b->onProgress, progress);
    }
}

static void segment_bridge(struct whisper_context* ctx, struct whisper_state*, int n_new, void* user_data) {
    auto* b = reinterpret_cast<CallbackBridge*>(user_data);
    if (b == nullptr || b->callback == nullptr) return;
    const int total = whisper_full_n_segments(ctx);
    for (int i = total - n_new; i < total; i++) {
        if (i < 0) continue;
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) continue;
        jstring js = b->env->NewStringUTF(text);
        b->env->CallVoidMethod(b->callback, b->onSegment, js);
        b->env->DeleteLocalRef(js);
    }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_changyow_mediadler_transcribe_WhisperNative_nativeInit(
        JNIEnv* env, jobject, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU-only build on Android
    whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    if (ctx == nullptr) LOGE("whisper_init failed for %s", path);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_changyow_mediadler_transcribe_WhisperNative_nativeFree(
        JNIEnv*, jobject, jlong ptr) {
    auto* ctx = reinterpret_cast<whisper_context*>(ptr);
    if (ctx != nullptr) whisper_free(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_changyow_mediadler_transcribe_WhisperNative_nativeFullTranscribe(
        JNIEnv* env, jobject, jlong ptr, jfloatArray samples, jstring language, jint threads,
        jobject callback) {
    auto* ctx = reinterpret_cast<whisper_context*>(ptr);
    if (ctx == nullptr) return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(samples);
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads       = threads > 0 ? threads : 4;
    params.translate       = false;
    params.no_timestamps   = true;
    params.print_special   = false;
    params.print_progress  = false;
    params.print_realtime  = false;
    params.print_timestamps = false;

    std::string lang;
    if (language != nullptr) {
        const char* l = env->GetStringUTFChars(language, nullptr);
        lang = l;
        env->ReleaseStringUTFChars(language, l);
    }
    // "auto" lets whisper detect the language, then transcribe.
    params.language = (lang.empty() || lang == "auto") ? "auto" : lang.c_str();
    params.detect_language = false;

    // whisper's Chinese (base/small) emits almost no punctuation, leaving one unbroken block that's
    // hard to read/segment. A short punctuated Chinese context biases it toward 。，？！ so the
    // transcript can be split into sentences. Only when the language is explicitly locked to Chinese
    // (not "auto") — never bias other languages or the auto-detect first window. Must outlive
    // whisper_full, so keep the backing string in this scope.
    std::string prompt;
    if (lang == "zh") {
        prompt = "以下是一段中文內容，包含標點符號。";
        params.initial_prompt = prompt.c_str();
    }

    // Wire live progress + per-segment text back to Kotlin (skipped when no callback given).
    CallbackBridge bridge{};
    if (callback != nullptr) {
        jclass cls = env->GetObjectClass(callback);
        bridge.env = env;
        bridge.callback = callback;
        bridge.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
        bridge.onSegment = env->GetMethodID(cls, "onSegment", "(Ljava/lang/String;)V");
        bridge.isCancelled = env->GetMethodID(cls, "isCancelled", "()Z");
        if (bridge.onProgress != nullptr) {
            params.progress_callback = progress_bridge;
            params.progress_callback_user_data = &bridge;
        }
        if (bridge.onSegment != nullptr) {
            params.new_segment_callback = segment_bridge;
            params.new_segment_callback_user_data = &bridge;
        }
        if (bridge.isCancelled != nullptr) {
            params.abort_callback = abort_bridge;
            params.abort_callback_user_data = &bridge;
        }
    }

    const int rc = whisper_full(ctx, params, reinterpret_cast<const float*>(data), static_cast<int>(n));
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (rc != 0) {
        LOGE("whisper_full failed rc=%d", rc);
        return env->NewStringUTF("");
    }

    std::string out;
    const int nseg = whisper_full_n_segments(ctx);
    for (int i = 0; i < nseg; i++) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) out += text;
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_changyow_mediadler_transcribe_WhisperNative_nativeDetectedLanguage(
        JNIEnv* env, jobject, jlong ptr) {
    auto* ctx = reinterpret_cast<whisper_context*>(ptr);
    if (ctx == nullptr) return env->NewStringUTF("");
    const int id = whisper_full_lang_id(ctx);
    const char* s = (id >= 0) ? whisper_lang_str(id) : "";
    return env->NewStringUTF(s != nullptr ? s : "");
}

} // extern "C"
