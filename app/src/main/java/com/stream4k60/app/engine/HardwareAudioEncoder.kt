package com.stream4k60.app.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import com.stream4k60.app.data.model.AudioInputRoute
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Hardware AAC encoder fed by the native AAudio mixer. No Kotlin AudioRecord capture loops are
 * used for live device inputs: Android's native low-latency audio callback owns the capture clock.
 */
class HardwareAudioEncoder(
    private val context: Context,
    private val sampleRate: Int = 48_000,
    private val channels: Int = 2,
    private val bitrate: Int = 192_000,
    private val onSample: (HardwareVideoEncoder.Sample) -> Unit,
    private val onFormat: (MediaFormat) -> Unit
) {
    private data class PcmBlock(val samples: ShortArray, val ptsUs: Long)
    private val pcmQueue = ArrayBlockingQueue<PcmBlock>(32)
    private var codec: MediaCodec? = null
    private var encodeThread: Thread? = null
    @Volatile private var running = false
    private val sink: (ByteBuffer, Long, Int, Int, Int) -> Unit = { buffer, ptsUs, frames, channels, sampleRate -> callback.onMixed(buffer, ptsUs, frames, channels, sampleRate) }
    private val callback = object : NativeAudioMixer.Callback {
        override fun onMixed(buffer: ByteBuffer, ptsUs: Long, frames: Int, channels: Int, sampleRate: Int) {
            if (!running) return
            val dup = buffer.duplicate()
            dup.clear()
            val count = frames * channels
            val out = ShortArray(count)
            for (i in 0 until count) {
                val f = dup.getFloat()
                out[i] = (f.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            }
            // Drop oldest audio under overload; never let live latency grow without bound.
            while (!pcmQueue.offer(PcmBlock(out, ptsUs))) pcmQueue.poll()
        }
    }

    fun start(
        inputRoutes: List<AudioInputRoute> = emptyList(),
        deviceIds: List<Int> = emptyList(),
        monitorDeviceId: Int? = null,
        monitorEnabled: Boolean = false,
        playbackCaptureEnabled: Boolean = false
    ) {
        check(!running)
        val routes = if (inputRoutes.isNotEmpty()) inputRoutes else deviceIds.mapIndexed { index, id ->
            AudioInputRoute(sourceId = "audio_device_$id", deviceId = id)
        }
        require(routes.isNotEmpty() || playbackCaptureEnabled || NativeAudioGraph.hasConfiguredSources()) { "No audio input device, media source, or Android playback source is available" }

        val c = MediaCodec.createEncoderByType("audio/mp4a-latm")
        c.configure(
            MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
        )
        c.start()
        codec = c
        running = true

        NativeAudioGraph.configure(routes, playbackCaptureEnabled, monitorDeviceId, monitorEnabled)
        NativeAudioGraph.registerSink(sink)

        encodeThread = Thread({ encodeLoop(c) }, "Stream4k-AudioEncoder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun setInputConfig(route: AudioInputRoute): Boolean = NativeAudioGraph.setVolume(route.sourceId, route)
    fun addInput(route: AudioInputRoute): Boolean {
        val current = NativeAudioGraph.currentHandle()
        if (current == 0L) return false
        return NativeAudioMixer.addInput(current, route.sourceId, route.deviceId, route.volume, route.balance, route.muted, route.monitoring.ordinal, route.syncOffsetMs)
    }
    fun removeInput(sourceId: String): Boolean {
        val current = NativeAudioGraph.currentHandle()
        return current != 0L && NativeAudioMixer.removeInput(current, sourceId)
    }
    fun setMonitorVolume(volume: Float) { val current = NativeAudioGraph.currentHandle(); if (current != 0L) NativeAudioMixer.setMonitorVolume(current, volume) }
    fun setMonitorMuted(muted: Boolean) { val current = NativeAudioGraph.currentHandle(); if (current != 0L) NativeAudioMixer.setMonitorMuted(current, muted) }
    fun peak(sourceId: String): Float = NativeAudioGraph.peak(sourceId)

    private fun encodeLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var formatSent = false
        while (running && !Thread.currentThread().isInterrupted) {
            val block = pcmQueue.poll(30, TimeUnit.MILLISECONDS)
            if (block != null) {
                val index = c.dequeueInputBuffer(5_000)
                if (index >= 0) {
                    val input = c.getInputBuffer(index)
                    if (input != null) {
                        input.clear()
                        val bytes = block.samples.size * 2
                        if (input.remaining() >= bytes) {
                            for (sample in block.samples) input.putShort(sample)
                            c.queueInputBuffer(index, 0, bytes, block.ptsUs, 0)
                        }
                    }
                }
            }
            var outIndex = c.dequeueOutputBuffer(info, 0)
            while (outIndex >= 0) {
                val out = c.getOutputBuffer(outIndex)
                if (out != null && info.size > 0) {
                    val dup = out.duplicate()
                    dup.position(info.offset)
                    dup.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    dup.get(bytes)
                    onSample(
                        HardwareVideoEncoder.Sample(
                            bytes,
                            info.presentationTimeUs,
                            false,
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        )
                    )
                }
                c.releaseOutputBuffer(outIndex, false)
                outIndex = c.dequeueOutputBuffer(info, 0)
            }
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !formatSent) {
                formatSent = true
                onFormat(c.outputFormat)
            }
        }
    }

    fun stop() {
        running = false
        encodeThread?.interrupt()
        runCatching { encodeThread?.join(1000) }
        encodeThread = null
        NativeAudioGraph.unregisterSink(sink)
        pcmQueue.clear()
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }
}
