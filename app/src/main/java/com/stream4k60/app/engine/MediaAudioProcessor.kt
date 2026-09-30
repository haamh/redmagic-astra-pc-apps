package com.stream4k60.app.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Copies decoded media PCM into the studio mixer, while passing the original PCM to Media3. */
class MediaAudioProcessor(private val mixerSourceId: String) : BaseAudioProcessor() {
    private var nextPtsUs = 0L
    private var floatBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val format = inputAudioFormat
        val channels = format.channelCount.coerceAtLeast(1)
        val bytesPerSample = when (format.encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> { inputBuffer.position(inputBuffer.limit()); return }
        }
        val frameBytes = bytesPerSample * channels
        val frames = bytes / frameBytes
        val output = replaceOutputBuffer(bytes)
        val pcm = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val start = pcm.position()
        val stereoBytes = frames * 2 * Float.SIZE_BYTES
        if (floatBuffer.capacity() < stereoBytes) {
            floatBuffer = ByteBuffer.allocateDirect(stereoBytes).order(ByteOrder.nativeOrder())
        }
        floatBuffer.clear()
        for (frame in 0 until frames) {
            val base = start + frame * frameBytes
            val left = sample(pcm, base, format.encoding)
            val right = if (channels == 1) left else sample(pcm, base + bytesPerSample, format.encoding)
            val center = if (channels >= 3) sample(pcm, base + bytesPerSample * 2, format.encoding) else 0f
            val surroundLeft = if (channels >= 5) sample(pcm, base + bytesPerSample * 4, format.encoding) else 0f
            val surroundRight = if (channels >= 6) sample(pcm, base + bytesPerSample * 5, format.encoding) else 0f
            floatBuffer.putFloat((left + center * 0.7071f + surroundLeft * 0.7071f).coerceIn(-1f, 1f))
            floatBuffer.putFloat((right + center * 0.7071f + surroundRight * 0.7071f).coerceIn(-1f, 1f))
        }
        floatBuffer.flip()
        val durationUs = frames.toLong() * 1_000_000L / format.sampleRate.coerceAtLeast(1)
        val blockPtsUs = if (nextPtsUs == 0L) System.nanoTime() / 1_000L else nextPtsUs
        if (frames > 0) NativeAudioGraph.pushExternalPcm(mixerSourceId, floatBuffer, frames, 2, format.sampleRate, blockPtsUs)
        nextPtsUs = blockPtsUs + durationUs
        output.put(inputBuffer)
        output.flip()
    }

    override fun onFlush() {
        nextPtsUs = 0L
    }

    override fun onReset() {
        nextPtsUs = 0L
        floatBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    private fun sample(buffer: ByteBuffer, offset: Int, encoding: Int): Float = when (encoding) {
        C.ENCODING_PCM_8BIT -> ((buffer.get(offset).toInt() and 0xff) - 128) / 128f
        C.ENCODING_PCM_16BIT -> buffer.getShort(offset) / 32768f
        C.ENCODING_PCM_24BIT -> {
            var value = (buffer.get(offset).toInt() and 0xff) or
                ((buffer.get(offset + 1).toInt() and 0xff) shl 8) or
                (buffer.get(offset + 2).toInt() shl 16)
            if (value and 0x800000 != 0) value = value or -0x1000000
            value / 8388608f
        }
        C.ENCODING_PCM_32BIT -> buffer.getInt(offset) / 2147483648f
        C.ENCODING_PCM_FLOAT -> buffer.getFloat(offset).coerceIn(-1f, 1f)
        else -> 0f
    }
}
