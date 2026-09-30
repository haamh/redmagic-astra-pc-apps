package com.stream4k60.app.data.model

import java.util.UUID

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val streamConfig: StreamConfig = StreamConfig(),
    val recordingConfig: RecordingConfig = RecordingConfig(),
    val audioConfig: AudioConfig = AudioConfig(),
    val videoConfig: VideoConfig = VideoConfig(),
    val isActive: Boolean = false
)
