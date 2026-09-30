package com.stream4k60.app.data.model

import java.io.Serializable

data class AdvancedAudioProperties(
    val volume: Float = 1.0f,
    val volumeDb: Float = 0f,
    val monoDownmix: Boolean = false,
    val balance: Float = 0f,
    val syncOffsetMs: Int = 0,
    val monitorType: AudioMonitorType = AudioMonitorType.MONITOR_OFF,
    val tracks: List<Boolean> = listOf(true, false, false, false, false, false)
) : Serializable

enum class AudioMonitorType(val displayName: String) : Serializable {
    MONITOR_OFF("Monitor Off"),
    MONITOR_ONLY("Monitor Only (mute output)"),
    MONITOR_AND_OUTPUT("Monitor and Output")
}
