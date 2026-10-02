// JNI bridge between dev.jacob.aillama.Llama and llama.cpp: load a GGUF model, format chats with
// the model's own template, and stream generated text back to Java token by token.

#include <jni.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
};

std::atomic<bool> g_cancel{false};
std::once_flag g_backend_once;

std::string utf8(JNIEnv * env, jstring s) {
    if (!s) return "";
    // GetStringUTFChars gives *modified* UTF-8 (different for emoji); decode via getBytes instead.
    jclass strClass = env->GetObjectClass(s);
    jmethodID getBytes = env->GetMethodID(strClass, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    auto bytes = (jbyteArray) env->CallObjectMethod(s, getBytes, charset);
    jsize n = env->GetArrayLength(bytes);
    std::string out((size_t) n, '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(strClass);
    return out;
}

jstring jstr(JNIEnv * env, const std::string & text) {
    jbyteArray bytes = env->NewByteArray((jsize) text.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) text.size(), reinterpret_cast<const jbyte *>(text.data()));
    jclass strClass = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto s = (jstring) env->NewObject(strClass, ctor, bytes, charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(strClass);
    return s;
}

/** Length of the longest prefix of s that ends on a whole UTF-8 character. */
size_t complete_utf8(const std::string & s) {
    size_t n = s.size(), i = n;
    // Walk back over continuation bytes (10xxxxxx) to the last lead byte.
    while (i > 0 && (static_cast<unsigned char>(s[i - 1]) & 0xC0) == 0x80) i--;
    if (i == 0) return n;
    unsigned char lead = static_cast<unsigned char>(s[i - 1]);
    size_t need = lead < 0x80 ? 1 : (lead >> 5) == 0x6 ? 2 : (lead >> 4) == 0xE ? 3 : (lead >> 3) == 0x1E ? 4 : 1;
    return (n - (i - 1)) >= need ? n : i - 1;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool special) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, special, true);
    std::vector<llama_token> out((size_t) std::max(n, 0));
    if (n > 0) llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), n, special, true);
    return out;
}

void quiet_log(ggml_log_level level, const char * text, void *) {
    (void) level;
    (void) text;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_jacob_aillama_Llama_nativeLoad(JNIEnv * env, jclass, jstring path, jint nCtx, jint threads, jboolean vocabOnly) {
    std::call_once(g_backend_once, [] {
        llama_log_set(quiet_log, nullptr);
        llama_backend_init();
    });
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.vocab_only = vocabOnly;
    auto * e = new Engine();
    e->model = llama_model_load_from_file(utf8(env, path).c_str(), mp);
    if (!e->model) { delete e; return 0; }
    e->vocab = llama_model_get_vocab(e->model);
    if (!vocabOnly) {
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = (uint32_t) nCtx;
        cp.n_batch = 512;
        cp.n_ubatch = 512;
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        cp.no_perf = true;
        e->ctx = llama_init_from_model(e->model, cp);
        if (!e->ctx) { llama_model_free(e->model); delete e; return 0; }
    }
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_dev_jacob_aillama_Llama_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

JNIEXPORT void JNICALL
Java_dev_jacob_aillama_Llama_nativeCancel(JNIEnv *, jclass) { g_cancel.store(true); }

JNIEXPORT jint JNICALL
Java_dev_jacob_aillama_Llama_nativeContextSize(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    return e && e->ctx ? (jint) llama_n_ctx(e->ctx) : 0;
}

JNIEXPORT jint JNICALL
Java_dev_jacob_aillama_Llama_nativeCountTokens(JNIEnv * env, jclass, jlong handle, jstring text) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return -1;
    return (jint) tokenize(e->vocab, utf8(env, text), false).size();
}

/**
 * Formats a conversation with the model's built-in chat template. Returns null if the model has
 * no template llama.cpp understands (Java then falls back to a generic format).
 */
JNIEXPORT jstring JNICALL
Java_dev_jacob_aillama_Llama_nativeFormat(JNIEnv * env, jclass, jlong handle, jobjectArray roles, jobjectArray contents) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return nullptr;
    const char * tmpl = llama_model_chat_template(e->model, nullptr);
    if (!tmpl) return nullptr;
    jsize n = env->GetArrayLength(roles);
    std::vector<std::string> r((size_t) n), c((size_t) n);
    std::vector<llama_chat_message> msgs((size_t) n);
    size_t total = 0;
    for (jsize i = 0; i < n; i++) {
        auto rs = (jstring) env->GetObjectArrayElement(roles, i);
        auto cs = (jstring) env->GetObjectArrayElement(contents, i);
        r[i] = utf8(env, rs);
        c[i] = utf8(env, cs);
        env->DeleteLocalRef(rs);
        env->DeleteLocalRef(cs);
        total += r[i].size() + c[i].size();
    }
    for (jsize i = 0; i < n; i++) msgs[i] = {r[i].c_str(), c[i].c_str()};
    std::vector<char> buf(total * 2 + 1024);
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    if (len < 0) return nullptr;
    if ((size_t) len > buf.size()) {
        buf.resize((size_t) len + 1);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
        if (len < 0) return nullptr;
    }
    return jstr(env, std::string(buf.data(), (size_t) len));
}

/**
 * Generates a reply to an already-formatted prompt. Text is streamed to listener.onText(String),
 * which returns false to stop. Returns: 0 finished, 1 cancelled or stopped, 2 hit maxTokens,
 * -2 prompt too long for the context, -3 decode error, -1 bad handle.
 */
JNIEXPORT jint JNICALL
Java_dev_jacob_aillama_Llama_nativeGenerate(JNIEnv * env, jclass, jlong handle, jstring prompt, jint maxTokens,
                                            jfloat temperature, jint seed, jobject listener) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e || !e->ctx) return -1;
    g_cancel.store(false);
    jclass cls = env->GetObjectClass(listener);
    jmethodID onText = env->GetMethodID(cls, "onText", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(cls);
    if (!onText) return -1;

    llama_memory_clear(llama_get_memory(e->ctx), true);
    std::vector<llama_token> tokens = tokenize(e->vocab, utf8(env, prompt), true);
    const int n_ctx = (int) llama_n_ctx(e->ctx);
    if ((int) tokens.size() + maxTokens > n_ctx) return -2;

    // Feed the prompt in batch-sized pieces.
    const int n_batch = 512;
    for (size_t i = 0; i < tokens.size(); i += n_batch) {
        if (g_cancel.load()) return 1;
        int n = (int) std::min<size_t>(n_batch, tokens.size() - i);
        if (llama_decode(e->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) return -3;
    }

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(e->vocab), 64, 1.1f, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t) seed));
    }

    std::string pending;
    int status = 2;
    char piece[256];
    for (int i = 0; i < maxTokens; i++) {
        if (g_cancel.load()) { status = 1; break; }
        llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) { status = 0; break; }
        int n = llama_token_to_piece(e->vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) pending.append(piece, (size_t) n);
        size_t ok = complete_utf8(pending);
        if (ok > 0) {
            jstring s = jstr(env, pending.substr(0, ok));
            jboolean more = env->CallBooleanMethod(listener, onText, s);
            env->DeleteLocalRef(s);
            pending.erase(0, ok);
            if (!more) { status = 1; break; }
        }
        llama_token next = tok;
        if (llama_decode(e->ctx, llama_batch_get_one(&next, 1)) != 0) { status = -3; break; }
    }
    llama_sampler_free(smpl);
    return status;
}

JNIEXPORT jstring JNICALL
Java_dev_jacob_aillama_Llama_nativeSystemInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

} // extern "C"
