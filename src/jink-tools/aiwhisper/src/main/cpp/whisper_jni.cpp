// JNI bridge between dev.jacob.aiwhisper.Whisper and whisper.cpp.
// Each transcription runs on the calling Java thread; progress and new text are reported back
// through a Java callback object, and Java can ask it to stop at any time.

#include <jni.h>
#include <atomic>
#include <string>
#include <thread>
#include <algorithm>
#include <cstring>
#include "whisper.h"

namespace {

struct Run {
    JNIEnv * env;
    jobject listener;
    jmethodID onProgress;
    jmethodID onSegment;
    std::atomic<bool> * cancel;
};

std::string utf8(JNIEnv * env, jstring s) {
    if (!s) return "";
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// whisper.cpp text is UTF-8, but a segment can end in the middle of a multi-byte character.
// NewStringUTF needs valid (modified) UTF-8, so build the Java string from bytes instead.
jstring jstr(JNIEnv * env, const char * text) {
    if (!text) text = "";
    size_t n = strlen(text);
    jbyteArray bytes = env->NewByteArray((jsize) n);
    env->SetByteArrayRegion(bytes, 0, (jsize) n, reinterpret_cast<const jbyte *>(text));
    jclass strClass = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto s = (jstring) env->NewObject(strClass, ctor, bytes, charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(strClass);
    return s;
}

void on_progress(whisper_context *, whisper_state *, int progress, void * user) {
    auto * r = static_cast<Run *>(user);
    if (r->listener) r->env->CallVoidMethod(r->listener, r->onProgress, (jint) progress);
}

void on_segment(whisper_context *, whisper_state * state, int n_new, void * user) {
    auto * r = static_cast<Run *>(user);
    if (!r->listener) return;
    const int n = whisper_full_n_segments_from_state(state);
    for (int i = std::max(0, n - n_new); i < n; i++) {
        jstring text = jstr(r->env, whisper_full_get_segment_text_from_state(state, i));
        r->env->CallVoidMethod(r->listener, r->onSegment,
                               (jlong) whisper_full_get_segment_t0_from_state(state, i) * 10,
                               (jlong) whisper_full_get_segment_t1_from_state(state, i) * 10, text);
        r->env->DeleteLocalRef(text);
    }
}

bool should_abort(void * user) {
    auto * r = static_cast<Run *>(user);
    return r->cancel->load();
}

std::atomic<bool> g_cancel{false};

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_jacob_aiwhisper_Whisper_nativeInit(JNIEnv * env, jclass, jstring path) {
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context * ctx = whisper_init_from_file_with_params(utf8(env, path).c_str(), cp);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_dev_jacob_aiwhisper_Whisper_nativeFree(JNIEnv *, jclass, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

JNIEXPORT void JNICALL
Java_dev_jacob_aiwhisper_Whisper_nativeCancel(JNIEnv *, jclass) {
    g_cancel.store(true);
}

/**
 * Transcribes 16 kHz mono float samples. language: "auto", "en", "de"... Returns 0 on success,
 * 1 if cancelled, or a negative whisper.cpp error code. Text arrives through listener.onSegment.
 */
JNIEXPORT jint JNICALL
Java_dev_jacob_aiwhisper_Whisper_nativeTranscribe(JNIEnv * env, jclass, jlong handle, jfloatArray samples,
                                                  jstring language, jboolean translate, jint threads,
                                                  jstring prompt, jobject listener) {
    auto * ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return -100;
    g_cancel.store(false);

    Run run{env, listener, nullptr, nullptr, &g_cancel};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        run.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
        run.onSegment = env->GetMethodID(cls, "onSegment", "(JJLjava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (!run.onProgress || !run.onSegment) return -101;
    }

    std::string lang = utf8(env, language);
    std::string initial = utf8(env, prompt);
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads > 0 ? threads : (int) std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
    p.language = lang.empty() ? "auto" : lang.c_str();
    p.detect_language = false;
    p.translate = translate;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.no_context = false;
    p.initial_prompt = initial.empty() ? nullptr : initial.c_str();
    p.progress_callback = on_progress;
    p.progress_callback_user_data = &run;
    p.new_segment_callback = on_segment;
    p.new_segment_callback_user_data = &run;
    p.abort_callback = should_abort;
    p.abort_callback_user_data = &run;

    jsize n = env->GetArrayLength(samples);
    jfloat * pcm = env->GetFloatArrayElements(samples, nullptr);
    int rc = whisper_full(ctx, p, pcm, n);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    if (g_cancel.load()) return 1;
    return rc;
}

/** The language whisper detected (or was told), e.g. "en". */
JNIEXPORT jstring JNICALL
Java_dev_jacob_aiwhisper_Whisper_nativeLanguage(JNIEnv * env, jclass, jlong handle) {
    auto * ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return env->NewStringUTF("");
    int id = whisper_full_lang_id(ctx);
    const char * s = id >= 0 ? whisper_lang_str(id) : "";
    return env->NewStringUTF(s ? s : "");
}

JNIEXPORT jstring JNICALL
Java_dev_jacob_aiwhisper_Whisper_nativeSystemInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
