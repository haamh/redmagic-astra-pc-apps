package com.stream4k60.app.data.model

data class RecordingConfig(
    val outputPath:String="",
    val outputWidth:Int=3840,
    val outputHeight:Int=2160,
    val fps:Int=60,
    val bitrate:Int=35_000_000,
    val codec:OutputCodec=OutputCodec.HEVC,
    val audioDeviceIds: List<Int> = emptyList(),
    val audioInputs: List<AudioInputRoute> = emptyList(),
    val monitorDeviceId: Int? = null,
    val monitorEnabled: Boolean = false,
    val audioPlaybackCaptureEnabled: Boolean = false
)
