// JNI bridge to whisper.cpp. Mirrors com.changyow.mediadler.transcribe.WhisperNative.
#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define LOG_TAG "whisper_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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
        JNIEnv* env, jobject, jlong ptr, jfloatArray samples, jstring language, jint threads) {
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
