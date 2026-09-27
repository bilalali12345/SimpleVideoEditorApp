package com.bilal.simplevideoeditorapp.util

/**
 * Thin Kotlin wrapper around the native RNNoise C library.
 *
 * Usage: create one instance per audio stream you're denoising, feed it
 * FRAME_SIZE-sample chunks in order via processFrame(), then release() it
 * when you're done. It holds native (off-heap) memory, so release() matters.
 */
class RNNoise {
    private var nativeHandle: Long = nativeCreate()

    /**
     * Denoises exactly one frame IN PLACE. The frame must be exactly
     * FRAME_SIZE (480) 16-bit PCM samples - RNNoise doesn't support any
     * other chunk size, and frames must be fed in the same order they
     * appear in the original audio (it's a streaming/stateful model).
     */
    fun processFrame(pcmFrame: ShortArray) {
        check(nativeHandle != 0L) { "This RNNoise instance has already been released." }
        require(pcmFrame.size == FRAME_SIZE) {
            "RNNoise frames must be exactly $FRAME_SIZE samples (10ms @ ${SAMPLE_RATE_HZ}Hz), got ${pcmFrame.size}."
        }
        nativeProcessFrame(nativeHandle, pcmFrame)
    }

    /**
     * Frees the native state. Safe to call more than once.
     */
    fun release() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
    }

    protected fun finalize() {
        // Safety net only - always call release() explicitly when you're done,
        // don't rely on this for timely cleanup.
        release()
    }

    private external fun nativeCreate(): Long
    private external fun nativeProcessFrame(handle: Long, frame: ShortArray)
    private external fun nativeDestroy(handle: Long)

    companion object {
        const val SAMPLE_RATE_HZ = 48000
        const val FRAME_SIZE = 480 // Fixed by RNNoise: 10ms of audio @ 48kHz

        init {
            System.loadLibrary("rnnoise_jni")
        }
    }
}