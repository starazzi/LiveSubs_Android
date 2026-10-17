#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_pt_livelegendas_Whisper_nativeInit(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_pt_livelegendas_Whisper_nativeFree(JNIEnv *, jclass, jlong handle) {
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

// Devolve o texto em UTF-8 como bytes (evita NewStringUTF com UTF-8 incompleto).
JNIEXPORT jbyteArray JNICALL
Java_pt_livelegendas_Whisper_nativeTranscribe(JNIEnv *env, jclass, jlong handle, jfloatArray audio,
                                              jstring lang, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    jsize n = env->GetArrayLength(audio);
    jfloat *data = env->GetFloatArrayElements(audio, nullptr);
    const char *l = env->GetStringUTFChars(lang, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.translate = false;
    p.detect_language = false;
    p.language = l;
    p.n_threads = threads;
    p.no_context = true;
    p.single_segment = true;
    p.suppress_blank = true;
    p.no_speech_thold = 0.6f;

    std::string out;
    if (whisper_full(ctx, p, data, n) == 0) {
        int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; i++) out += whisper_full_get_segment_text(ctx, i);
    }
    env->ReleaseStringUTFChars(lang, l);
    env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);

    jbyteArray res = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(res, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return res;
}

}
