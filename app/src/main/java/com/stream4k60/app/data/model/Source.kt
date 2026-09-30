package com.stream4k60.app.data.model

import java.util.UUID

data class Source(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: SourceType,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val width: Float = 1920f,
    val height: Float = 1080f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val filters: List<Filter> = emptyList(),
    val volume: Float = 1f,
    val audioMonitoring: AudioMonitoring = AudioMonitoring.OFF,
    val cropLeft: Float = 0f,
    val cropRight: Float = 0f,
    val cropTop: Float = 0f,
    val cropBottom: Float = 0f,
    val blendMode: BlendMode = BlendMode.NORMAL
)

enum class AudioMonitoring {
    OFF, OUTPUT_ONLY, MONITOR_ONLY, MONITOR_AND_OUTPUT
}

enum class BlendMode {
    NORMAL, ADDITIVE, MULTIPLY, SCREEN
}
