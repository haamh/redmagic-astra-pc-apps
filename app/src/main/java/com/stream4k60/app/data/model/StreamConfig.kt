package com.stream4k60.app.data.model

data class StreamConfig(
    val service: StreamService = StreamService.YOUTUBE,
    val protocol: StreamProtocol = StreamProtocol.RTMPS,
    val ingestionUrl: String = "",
    val streamName: String = "",
    val broadcastId: String? = null,
    val bitrate: Int = 20_000_000,
    val outputCodec: OutputCodec = OutputCodec.H264,
    val outputWidth: Int = 3840,
    val outputHeight: Int = 2160,
    val fps: Int = 60,
    val keyframeInterval: Int = 2,
    val hdr: Boolean = false,
    val autoReconnect: Boolean = true,
    val audioDeviceIds: List<Int> = emptyList(),
    val audioInputs: List<AudioInputRoute> = emptyList(),
    val monitorDeviceId: Int? = null,
    val monitorEnabled: Boolean = false,
    val audioPlaybackCaptureEnabled: Boolean = false
)

enum class OutputCodec { H264, HEVC, AV1 }
enum class StreamService { CUSTOM, TWITCH, YOUTUBE, FACEBOOK, KICK, TIKTOK, INSTAGRAM }
enum class StreamProtocol { RTMP, RTMPS, HLS, SRT, RIST }

/** Stream destination saved in the active profile (Settings → Stream), like OBS's service.json. */
data class StreamSettings(
    val service: StreamService = StreamService.YOUTUBE,
    val server: String = "",
    val streamKey: String = ""
)

/** Studio behaviour toggles (Settings → General). */
data class GeneralSettings(
    val confirmStopStreaming: Boolean = true,
    val confirmStopRecording: Boolean = true,
    val autoRecordWhenStreaming: Boolean = false,
    val snappingEnabled: Boolean = true,
    val snapToSources: Boolean = true
)

data class ImportedRtmpEndpoint(val serverUrl: String, val streamKey: String, val protocol: StreamProtocol)
