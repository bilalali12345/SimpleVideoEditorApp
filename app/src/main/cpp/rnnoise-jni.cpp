#include <jni.h>
#include <cstdlib>
#include "rnnoise.h"

// Must match RNNoise.FRAME_SIZE on the Kotlin side: 480 samples = 10ms @ 48kHz.
// RNNoise only ever processes audio in exactly this many samples at a time.
static constexpr int FRAME_SIZE = 480;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_bilal_simplevideoeditorapp_util_RNNoise_nativeCreate(JNIEnv *env, jobject thiz) {
    // Passing nullptr means "use RNNoise's built-in default model" - the one
    // baked into rnnoise_data.c that we vendored in.
    DenoiseState *state = rnnoise_create(nullptr);
    return reinterpret_cast<jlong>(state);
}

JNIEXPORT void JNICALL
Java_com_bilal_simplevideoeditorapp_util_RNNoise_nativeProcessFrame(
        JNIEnv *env, jobject thiz, jlong handle, jshortArray frame) {
auto *state = reinterpret_cast<DenoiseState *>(handle);
if (state == nullptr) return;

jsize length = env->GetArrayLength(frame);
if (length != FRAME_SIZE) return; // Kotlin side already validates this, but be safe.

jshort *samples = env->GetShortArrayElements(frame, nullptr);
if (samples == nullptr) return;

// IMPORTANT: RNNoise expects each float to hold the RAW int16 magnitude,
// i.e. roughly -32768.0f to 32767.0f - NOT audio normalized to -1.0..1.0.
// Feeding it normalized floats is the single most common mistake here;
// it won't crash, it'll just silently produce garbage/near-silent output.
float buffer[FRAME_SIZE];
for (int i = 0; i < FRAME_SIZE; i++) {
buffer[i] = static_cast<float>(samples[i]);
}

// Denoises in place. The float it returns is a voice-activity probability
// (0..1) - useful if you ever want to skip processing silent stretches,
// but we don't need it for a straightforward "clean up the whole clip" pass.
rnnoise_process_frame(state, buffer, buffer);

for (int i = 0; i < FRAME_SIZE; i++) {
samples[i] = static_cast<jshort>(buffer[i]);
}

env->ReleaseShortArrayElements(frame, samples, 0);
}

JNIEXPORT void JNICALL
Java_com_bilal_simplevideoeditorapp_util_RNNoise_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
auto *state = reinterpret_cast<DenoiseState *>(handle);
if (state != nullptr) {
rnnoise_destroy(state);
}
}

} // extern "C"