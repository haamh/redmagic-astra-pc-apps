package com.stream4k60.app.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Per-source audio filters, stored as `settings.audioFilters` (same entry shape as `videoFilters`).
 * Filters run natively on the 48 kHz program bus before volume/pan.
 */
enum class AudioFilterType(val label: String) {
    NOISE_GATE("Noise Gate");

    companion object {
        fun fromName(value: String?): AudioFilterType? = entries.firstOrNull { it.name.equals(value, true) }
    }
}

data class AudioFilterStage(
    val id: String = UUID.randomUUID().toString(),
    val type: AudioFilterType,
    val name: String = type.label,
    val enabled: Boolean = true,
    val settings: Map<String, Any> = defaultSettings(type)
) {
    fun float(key: String): Float = (settings[key] as? Number)?.toFloat() ?: (defaultSettings(type)[key] as Number).toFloat()
    fun bool(key: String): Boolean = settings[key] as? Boolean ?: (defaultSettings(type)[key] as Boolean)
    fun with(key: String, value: Any): AudioFilterStage = copy(settings = settings + (key to value))

    companion object {
        /** OBS noise-gate defaults. `closeFollowsOpen` keeps the close threshold 6 dB below "start listening at". */
        fun defaultSettings(type: AudioFilterType): Map<String, Any> = when (type) {
            AudioFilterType.NOISE_GATE -> mapOf(
                "openDb" to -26.0, "closeDb" to -32.0, "closeFollowsOpen" to true,
                "attackMs" to 25.0, "holdMs" to 200.0, "releaseMs" to 150.0
            )
        }
    }
}

/** Resolved noise-gate parameters for the native mixer. */
data class NoiseGateConfig(
    val openDb: Float = -26f,
    val closeDb: Float = -32f,
    val attackMs: Float = 25f,
    val holdMs: Float = 200f,
    val releaseMs: Float = 150f
)

object AudioFilterChain {
    const val GATE_HYSTERESIS_DB = 6f

    /** Source types whose audio passes through the native mixer and can take audio filters. */
    val AUDIO_SOURCE_TYPES = setOf("AUDIO_INPUT", "PLAYBACK_AUDIO", "MEDIA", "USB_CAPTURE")

    /** The mixer input id a source's audio uses (see MainStudioViewModel's route builders). */
    fun mixerInputId(sourceId: String, sourceType: String): String = when (sourceType.uppercase()) {
        "USB_CAPTURE" -> "usb_audio_$sourceId"
        "MEDIA" -> "media_audio_$sourceId"
        "PLAYBACK_AUDIO" -> NativeAudioBridge.PLAYBACK_SOURCE_ID
        else -> sourceId
    }

    fun read(configJson: String): List<AudioFilterStage> {
        val root = runCatching { JSONObject(configJson) }.getOrDefault(JSONObject())
        val settings = root.optJSONObject("settings") ?: root
        val array = settings.optJSONArray("audioFilters") ?: root.optJSONArray("audioFilters") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val type = AudioFilterType.fromName(entry.optString("type")) ?: continue
                val saved = entry.optJSONObject("settings") ?: JSONObject()
                val values = AudioFilterStage.defaultSettings(type).toMutableMap()
                for (key in saved.keys()) saved.opt(key)?.let { if (it != JSONObject.NULL) values[key] = it }
                add(AudioFilterStage(
                    id = entry.optString("id").ifBlank { UUID.randomUUID().toString() },
                    type = type,
                    name = entry.optString("name").ifBlank { type.label },
                    enabled = entry.optBoolean("enabled", true),
                    settings = values
                ))
            }
        }
    }

    fun write(configJson: String, stages: List<AudioFilterStage>): String {
        val root = runCatching { JSONObject(configJson) }.getOrDefault(JSONObject())
        val target = root.optJSONObject("settings") ?: root
        target.put("audioFilters", JSONArray().apply {
            for (stage in stages) put(JSONObject()
                .put("id", stage.id).put("type", stage.type.name).put("name", stage.name)
                .put("enabled", stage.enabled).put("settings", JSONObject(stage.settings)))
        })
        if (target !== root) root.put("settings", target)
        return root.toString()
    }

    /** The active gate: the first enabled Noise Gate, or null. */
    fun noiseGate(stages: List<AudioFilterStage>): NoiseGateConfig? =
        stages.firstOrNull { it.enabled && it.type == AudioFilterType.NOISE_GATE }?.let(::gateConfig)

    fun noiseGate(configJson: String): NoiseGateConfig? = noiseGate(read(configJson))

    fun gateConfig(stage: AudioFilterStage): NoiseGateConfig {
        val open = stage.float("openDb").coerceIn(-96f, 0f)
        val close = if (stage.bool("closeFollowsOpen")) open - GATE_HYSTERESIS_DB else stage.float("closeDb")
        return NoiseGateConfig(
            openDb = open,
            closeDb = close.coerceIn(-96f, open),
            attackMs = stage.float("attackMs").coerceIn(0f, 10_000f),
            holdMs = stage.float("holdMs").coerceIn(0f, 10_000f),
            releaseMs = stage.float("releaseMs").coerceIn(0f, 10_000f)
        )
    }
}
