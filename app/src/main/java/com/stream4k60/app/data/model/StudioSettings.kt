package com.stream4k60.app.data.model

/**
 * OBS-style global audio: Desktop Audio (game/app sound via Android playback capture) and a Mic/Aux input that
 * exist in every scene. Their mixer/filter state is stored as source config JSON, like a scene source's.
 */
data class AudioSettings(
    val desktopAudioEnabled: Boolean = true,
    /** Android AudioDeviceInfo id; [MIC_DEFAULT] = system default input, [MIC_DISABLED] = off. */
    val micDeviceId: Int = MIC_DEFAULT,
    /** Android output device id for monitoring; -1 = system default. */
    val monitorDeviceId: Int = -1,
    val pushToTalk: Boolean = false,
    val pushToMute: Boolean = false,
    val pushDelayMs: Int = 200,
    val desktopConfig: String = "{\"volume\":1.0,\"monitoring\":\"OUTPUT_ONLY\"}",
    val micConfig: String = "{\"volume\":1.0,\"monitoring\":\"OUTPUT_ONLY\"}"
) {
    companion object {
        const val MIC_DEFAULT = -2
        const val MIC_DISABLED = -1
    }
}

/** Settings → Advanced: what happens when the connection to the streaming server drops. */
data class AdvancedSettings(
    val autoReconnect: Boolean = true,
    val reconnectDelaySec: Int = 2,
    val maxRetries: Int = 20
)

/** A key combination; modifiers are android.view.KeyEvent META_* flags (ctrl/shift/alt/meta only). */
data class HotkeyBinding(val keyCode: Int, val modifiers: Int)

enum class HotkeyAction(val label: String) {
    START_STREAM("Start Streaming"), STOP_STREAM("Stop Streaming"), TOGGLE_STREAM("Start/Stop Streaming"),
    STUDIO_MODE("Toggle Studio Mode"), MUTE_MIC("Mute/Unmute Mic"), PUSH_TO_TALK("Push-to-talk (hold)"),
    SCENE_1("Switch to scene 1"), SCENE_2("Switch to scene 2"), SCENE_3("Switch to scene 3"),
    SCENE_4("Switch to scene 4"), SCENE_5("Switch to scene 5"), SCENE_6("Switch to scene 6"),
    SCENE_7("Switch to scene 7"), SCENE_8("Switch to scene 8"), SCENE_9("Switch to scene 9")
}

/** Settings → Accessibility. */
data class AccessibilitySettings(val uiScale: Float = 1f)
