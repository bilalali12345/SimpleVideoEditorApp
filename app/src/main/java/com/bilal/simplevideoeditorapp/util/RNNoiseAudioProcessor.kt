package com.bilal.simplevideoeditorapp.util

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A Media3 audio-processing step that runs every sample of audio through RNNoise
 * before it's encoded back into the exported video. This plugs into the same
 * Transformer pipeline that exportTrimmedVideo/mergeVideos already use, via
 * EditedMediaItem's effects list - see exportDenoisedVideo() below.
 *
 * Handles converting whatever format the source audio is in (any sample rate,
 * mono or stereo) down to what RNNoise requires: 48kHz, mono, 16-bit PCM.
 * The resampling here is a simple linear interpolation - good enough to feed a
 * noise suppressor, not meant to be audiophile-grade.
 */
@UnstableApi
class RNNoiseAudioProcessor : BaseAudioProcessor() {

    private var rnNoise: RNNoise? = null

    private var inputSampleRate = 0
    private var inputChannelCount = 0

    // Resampling state, carried across queueInput() calls so the stream stays
    // continuous even though audio arrives in arbitrarily-sized chunks.
    private var resamplePosition = 0.0
    private var previousSample = 0f

    // Samples already resampled to 48kHz mono but not yet enough to fill one
    // full RNNoise frame (480 samples) - held here until they do.
    private val pendingSamples = ArrayDeque<Short>()

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }

        inputSampleRate = inputAudioFormat.sampleRate
        inputChannelCount = inputAudioFormat.channelCount
        resamplePosition = 0.0
        previousSample = 0f
        pendingSamples.clear()

        rnNoise?.release()
        rnNoise = RNNoise()

        return AudioFormat(RNNoise.SAMPLE_RATE_HZ, /* channelCount= */ 1, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val shortsIn = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frameCount = shortsIn.remaining() / inputChannelCount
        if (frameCount <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        // 1. Downmix every channel down to one, by averaging them.
        val monoSamples = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            var sum = 0f
            for (channel in 0 until inputChannelCount) {
                sum += shortsIn.get(i * inputChannelCount + channel)
            }
            monoSamples[i] = sum / inputChannelCount
        }
        inputBuffer.position(inputBuffer.limit())

        // 2. Resample to RNNoise's required 48kHz using linear interpolation.
        //    A "position" of -1 refers to the last sample of the PREVIOUS chunk,
        //    so the interpolation stays smooth across chunk boundaries.
        val ratio = inputSampleRate.toDouble() / RNNoise.SAMPLE_RATE_HZ.toDouble()
        var position = resamplePosition
        while (position < frameCount - 1) {
            val index = floor(position).toInt()
            val fraction = (position - index).toFloat()
            val sampleA = if (index < 0) previousSample else monoSamples[index]
            val sampleB = if (index + 1 < frameCount) monoSamples[index + 1] else monoSamples[frameCount - 1]
            val interpolated = sampleA + (sampleB - sampleA) * fraction
            pendingSamples.addLast(interpolated.roundToInt().toShort())
            position += ratio
        }
        resamplePosition = position - frameCount
        previousSample = monoSamples[frameCount - 1]

        // 3. Once there's enough resampled audio for a full 10ms RNNoise frame,
        //    run it through the denoiser and hand the cleaned audio downstream.
        val denoiser = rnNoise ?: return
        val readyFrames = pendingSamples.size / RNNoise.FRAME_SIZE
        if (readyFrames == 0) return

        val outputBuffer = replaceOutputBuffer(readyFrames * RNNoise.FRAME_SIZE * 2)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        repeat(readyFrames) {
            val frame = ShortArray(RNNoise.FRAME_SIZE) { pendingSamples.removeFirst() }
            denoiser.processFrame(frame)
            for (sample in frame) outputBuffer.putShort(sample)
        }
        outputBuffer.flip()
    }

    override fun onQueueEndOfStream() {
        // Pad whatever's left (less than one full frame) with silence so RNNoise
        // can still process this last little bit, rather than just dropping it.
        if (pendingSamples.isEmpty()) return

        val denoiser = rnNoise ?: return
        val frame = ShortArray(RNNoise.FRAME_SIZE)
        var i = 0
        while (pendingSamples.isNotEmpty() && i < RNNoise.FRAME_SIZE) {
            frame[i] = pendingSamples.removeFirst()
            i++
        }
        denoiser.processFrame(frame)

        val outputBuffer = replaceOutputBuffer(RNNoise.FRAME_SIZE * 2)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (sample in frame) outputBuffer.putShort(sample)
        outputBuffer.flip()
    }

    override fun onFlush() {
        resamplePosition = 0.0
        previousSample = 0f
        pendingSamples.clear()
    }

    override fun onReset() {
        rnNoise?.release()
        rnNoise = null
    }
}