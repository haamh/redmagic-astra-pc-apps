package com.stream4k60.app.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Direct Android MediaCodec video encoder. The input is an ANativeWindow/Surface, so
 * composition can render straight into the Snapdragon VPU input queue.
 */
class HardwareVideoEncoder(
    private val config: Config,
    private val onSample: (Sample) -> Unit,
    private val onFormat: (MediaFormat) -> Unit
) {
    data class Config(
        val mime: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val keyframeIntervalSec: Int = 2,
        val bitrateMode: Int = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
        val profile: Int? = null,
        val level: Int? = null,
        val hdr: Boolean = false
    )
    data class Sample(val data: ByteArray, val ptsUs: Long, val keyframe: Boolean, val codecConfig: Boolean)

    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var inputSurface: Surface? = null

    suspend fun prepare(): Surface = withContext(Dispatchers.Default) {
        val encoderInfo = findHardwareEncoder(config.mime, config.width, config.height, config.fps)
            ?: error("No hardware encoder supports ${config.mime} at ${config.width}x${config.height}@${config.fps}")
        val c = MediaCodec.createByCodecName(encoderInfo.name)
        val fmt = MediaFormat.createVideoFormat(config.mime, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeIntervalSec)
            setInteger(MediaFormat.KEY_BITRATE_MODE, config.bitrateMode)
            config.profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
            config.level?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
            if (config.hdr && config.mime == "video/hevc") {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            } else {
                // SDR: encode and label as BT.709 limited range, like OBS. Left unset, the encoder picks its own
                // range/tagging and players can show the stream washed out (grey blacks, dull colours).
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        }
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        codec = c
        inputSurface!!
    }

    fun start() {
        val c = codec ?: error("prepare() first")
        check(!running)
        c.start()
        running = true
        thread = Thread({ drain(c) }, "Stream4k-VideoEncoder").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        inputSurface?.release()
        inputSurface = null
    }

    fun requestKeyframe() {
        codec?.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    fun setBitrate(bitrate: Int) {
        codec?.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) })
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = c.dequeueOutputBuffer(info, 10_000)
            when {
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        val dup = buf.duplicate()
                        dup.position(info.offset)
                        dup.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        dup.get(bytes)
                        onSample(Sample(
                            bytes,
                            info.presentationTimeUs,
                            (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        ))
                    }
                    c.releaseOutputBuffer(idx, false)
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(c.outputFormat)
            }
        }
    }

    companion object {
        private val preferredMime = setOf("video/avc", "video/hevc", "video/av01")

        fun availableEncoders(): List<String> = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { info ->
                info.isEncoder &&
                    info.isHardwareAccelerated &&
                    info.supportedTypes.any { it in preferredMime }
            }
            .map { it.name }
            .distinct()

        fun isSupported(mime: String): Boolean = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any {
            it.isEncoder && it.isHardwareAccelerated && !it.isSoftwareOnly && it.supportedTypes.contains(mime)
        }

        fun supportsResolution(mime: String, width: Int, height: Int, fps: Int): Boolean =
            findHardwareEncoder(mime, width, height, fps) != null

        fun findHardwareEncoder(mime: String, width: Int, height: Int, fps: Int): MediaCodecInfo? {
            return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .asSequence()
                .filter { info ->
                    info.isEncoder &&
                        info.isHardwareAccelerated &&
                        !info.isSoftwareOnly &&
                        info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }
                .filter { info ->
                    runCatching {
                        val vc = info.getCapabilitiesForType(mime).videoCapabilities ?: return@runCatching false
                        if (!vc.isSizeSupported(width, height)) return@runCatching false
                        val rates = runCatching { vc.getSupportedFrameRatesFor(width, height) }.getOrNull()
                        rates == null || fps.toDouble() in rates.lower..rates.upper
                    }.getOrDefault(false)
                }
                .sortedWith(compareBy<MediaCodecInfo> { it.name.startsWith("c2.android", true) }
                    .thenBy { it.name.startsWith("OMX.google", true) }
                    .thenBy { it.name })
                .firstOrNull()
        }
    }
}
