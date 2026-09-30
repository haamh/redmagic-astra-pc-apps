package com.stream4k60.app.data.model

data class AudioConfig(
    val sampleRate: SampleRate = SampleRate.SR_48KHZ,
    val channels: AudioChannels = AudioChannels.STEREO,
    val desktopAudio1: String = "default",
    val desktopAudio2: String = "disabled",
    val micAux1: String = "default",
    val micAux2: String = "disabled",
    val micAux3: String = "disabled",
    val micAux4: String = "disabled",
    val monitoringDevice: String = "default",
    val pushToMute: Boolean = false,
    val pushToTalk: Boolean = false,
    val hotkeys: Map<String, String> = emptyMap()
)

data class AudioInputRoute(
    val sourceId: String,
    val deviceId: Int,
    val volume: Float = 1f,
    val balance: Float = 0f,
    val muted: Boolean = false,
    val monitoring: AudioMonitoring = AudioMonitoring.MONITOR_AND_OUTPUT,
    val syncOffsetMs: Int = 0,
    val solo: Boolean = false,
    /** Null when the source has no enabled noise gate. */
    val noiseGate: com.stream4k60.app.engine.NoiseGateConfig? = null
)

enum class SampleRate {
    SR_44_1KHZ, SR_48KHZ
}

enum class AudioChannels {
    MONO, STEREO, SURROUND_51
}
