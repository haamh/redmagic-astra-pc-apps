package com.stream4k60.app.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/**
 * Ordered per-source GPU video filter chain.
 *
 * Stages run top to bottom, matching OBS filter-list order. Each stage is a per-pixel operation evaluated
 * in the compositor's layer shader, so ordering is exact (for example, color correction before or after a key).
 * Stored in a source config as `settings.videoFilters` (or root `videoFilters` when there is no settings object).
 */
enum class VideoFilterType(val nativeId: Int, val label: String) {
    COLOR_CORRECTION(1, "Color Correction"),
    CHROMA_KEY(2, "Chroma Key"),
    COLOR_KEY(3, "Color Key"),
    LUMA_KEY(4, "Luma Key");

    companion object {
        fun fromName(value: String?): VideoFilterType? = entries.firstOrNull { it.name.equals(value, true) }
    }
}

data class VideoFilterStage(
    val id: String = UUID.randomUUID().toString(),
    val type: VideoFilterType,
    val name: String = type.label,
    val enabled: Boolean = true,
    val settings: Map<String, Any> = defaultSettings(type)
) {
    fun float(key: String): Float = (settings[key] as? Number)?.toFloat() ?: (defaultSettings(type)[key] as Number).toFloat()
    fun color(key: String): Int = parseColor(settings[key]?.toString()) ?: parseColor(defaultSettings(type)[key].toString())!!

    fun with(key: String, value: Any): VideoFilterStage = copy(settings = settings + (key to value))

    companion object {
        fun defaultSettings(type: VideoFilterType): Map<String, Any> = when (type) {
            // gamma > 1 brightens midtones (output = input^(1/gamma)); contrast/saturation are multipliers.
            VideoFilterType.COLOR_CORRECTION -> mapOf(
                "gamma" to 1.0, "contrast" to 1.0, "brightness" to 0.0, "saturation" to 1.0,
                "hueDegrees" to 0.0, "opacity" to 1.0, "colorMultiply" to "#FFFFFFFF", "colorAdd" to "#FF000000"
            )
            // Similarity/smoothness/spill use OBS's 0..1 scale (OBS UI value / 1000).
            VideoFilterType.CHROMA_KEY -> mapOf(
                "keyColor" to "#FF00FF00", "similarity" to 0.4, "smoothness" to 0.08, "spill" to 0.1,
                "opacity" to 1.0, "contrast" to 1.0, "brightness" to 0.0, "gamma" to 1.0
            )
            VideoFilterType.COLOR_KEY -> mapOf(
                "keyColor" to "#FF00FF00", "similarity" to 0.08, "smoothness" to 0.05,
                "opacity" to 1.0, "contrast" to 1.0, "brightness" to 0.0, "gamma" to 1.0
            )
            VideoFilterType.LUMA_KEY -> mapOf(
                "lumaMin" to 0.0, "lumaMax" to 1.0, "lumaMinSmooth" to 0.0, "lumaMaxSmooth" to 0.0
            )
        }

        /** Parses #RRGGBB or #AARRGGBB into an ARGB int. */
        fun parseColor(value: String?): Int? {
            val hex = value?.trim()?.removePrefix("#") ?: return null
            if (hex.length != 6 && hex.length != 8) return null
            val parsed = hex.toLongOrNull(16) ?: return null
            return (if (hex.length == 6) parsed or 0xFF000000 else parsed).toInt()
        }
    }
}

object VideoFilterChain {
    /** Must match kMaxFilterStages in gl_compositor.h. */
    const val MAX_STAGES = 8
    /** Four vec4 parameters per stage; must match the shader's uStageParams layout. */
    const val FLOATS_PER_STAGE = 16

    fun read(configJson: String): List<VideoFilterStage> {
        val root = runCatching { JSONObject(configJson) }.getOrDefault(JSONObject())
        val settings = root.optJSONObject("settings") ?: root
        val array = settings.optJSONArray("videoFilters") ?: root.optJSONArray("videoFilters")
        if (array != null) return parseArray(array)
        val legacy = settings.optJSONObject("effects") ?: root.optJSONObject("effects")
        return legacy?.let(::fromLegacyEffects).orEmpty()
    }

    /** Returns [configJson] with the chain stored and any legacy single-stage `effects` object removed. */
    fun write(configJson: String, stages: List<VideoFilterStage>): String {
        val root = runCatching { JSONObject(configJson) }.getOrDefault(JSONObject())
        val target = root.optJSONObject("settings") ?: root
        target.put("videoFilters", toJson(stages))
        target.remove("effects")
        root.remove("effects")
        if (target !== root) root.put("settings", target)
        return root.toString()
    }

    fun toJson(stages: List<VideoFilterStage>): JSONArray = JSONArray().apply {
        for (stage in stages) {
            put(JSONObject()
                .put("id", stage.id)
                .put("type", stage.type.name)
                .put("name", stage.name)
                .put("enabled", stage.enabled)
                .put("settings", JSONObject(stage.settings)))
        }
    }

    fun parseArray(array: JSONArray): List<VideoFilterStage> = buildList {
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val type = VideoFilterType.fromName(entry.optString("type")) ?: continue
            val saved = entry.optJSONObject("settings") ?: JSONObject()
            val settings = VideoFilterStage.defaultSettings(type).toMutableMap()
            for (key in saved.keys()) saved.opt(key)?.let { if (it != JSONObject.NULL) settings[key] = it }
            add(VideoFilterStage(
                id = entry.optString("id").ifBlank { UUID.randomUUID().toString() },
                type = type,
                name = entry.optString("name").ifBlank { type.label },
                enabled = entry.optBoolean("enabled", true),
                settings = settings
            ))
        }
    }

    /** Converts the pre-chain fixed `effects` object (one color stage, then one chroma stage). */
    private fun fromLegacyEffects(effects: JSONObject): List<VideoFilterStage> = buildList {
        val color = VideoFilterStage(type = VideoFilterType.COLOR_CORRECTION, settings = VideoFilterStage.defaultSettings(VideoFilterType.COLOR_CORRECTION) + mapOf(
            "brightness" to effects.optDouble("brightness", 0.0),
            "contrast" to effects.optDouble("contrast", 1.0),
            "saturation" to effects.optDouble("saturation", 1.0),
            "gamma" to effects.optDouble("gamma", 1.0),
            "hueDegrees" to effects.optDouble("hueDegrees", 0.0)
        ))
        if (color.settings != VideoFilterStage.defaultSettings(VideoFilterType.COLOR_CORRECTION)) add(color)
        if (effects.optBoolean("chromaKeyEnabled", false)) {
            add(VideoFilterStage(type = VideoFilterType.CHROMA_KEY, settings = VideoFilterStage.defaultSettings(VideoFilterType.CHROMA_KEY) + mapOf(
                "keyColor" to effects.optString("chromaKeyColor", "#FF00FF00"),
                "similarity" to effects.optDouble("chromaSimilarity", 0.4),
                "smoothness" to effects.optDouble("chromaSmoothness", 0.08)
            )))
        }
    }

    /** Packs enabled stages for NativeEngine.setSourceFilterChain. Stages past [MAX_STAGES] are not sent. */
    fun pack(stages: List<VideoFilterStage>): Pair<IntArray, FloatArray> {
        val active = stages.filter { it.enabled }.take(MAX_STAGES)
        val types = IntArray(active.size)
        val params = FloatArray(active.size * FLOATS_PER_STAGE)
        active.forEachIndexed { index, stage ->
            types[index] = stage.type.nativeId
            val p = FloatArray(FLOATS_PER_STAGE)
            when (stage.type) {
                VideoFilterType.COLOR_CORRECTION -> {
                    val mul = stage.color("colorMultiply")
                    val add = stage.color("colorAdd")
                    p.set(0, stage.float("brightness").coerceIn(-1f, 1f), stage.float("contrast").coerceIn(0f, 4f),
                        stage.float("saturation").coerceIn(0f, 4f), 1f / max(0.01f, stage.float("gamma")))
                    p.set(4, stage.float("hueDegrees"), stage.float("opacity").coerceIn(0f, 1f), 0f, 0f)
                    p.set(8, red(mul) / 255f, green(mul) / 255f, blue(mul) / 255f, 1f)
                    p.set(12, red(add) / 255f, green(add) / 255f, blue(add) / 255f, 0f)
                }
                VideoFilterType.CHROMA_KEY -> {
                    val key = stage.color("keyColor")
                    val r = red(key) / 255f; val g = green(key) / 255f; val b = blue(key) / 255f
                    // Same BT.709-limited CbCr projection the shader uses, so distances match OBS.
                    val cb = r * -0.100644f + g * -0.338572f + b * 0.439216f + 0.501961f
                    val cr = r * 0.439216f + g * -0.398942f + b * -0.040274f + 0.501961f
                    p.set(0, cr, cb, stage.float("similarity").coerceIn(0f, 1f), max(0.001f, stage.float("smoothness")))
                    p.set(4, max(0.001f, stage.float("spill")), 0f, 0f, 0f)
                    packKeyColorAdjust(p, stage)
                }
                VideoFilterType.COLOR_KEY -> {
                    val key = stage.color("keyColor")
                    p.set(0, red(key) / 255f, green(key) / 255f, blue(key) / 255f, stage.float("similarity").coerceIn(0f, 1f))
                    p.set(4, max(0.001f, stage.float("smoothness")), 0f, 0f, 0f)
                    packKeyColorAdjust(p, stage)
                }
                VideoFilterType.LUMA_KEY -> p.set(0,
                    stage.float("lumaMin").coerceIn(0f, 1f), stage.float("lumaMax").coerceIn(0f, 1f),
                    stage.float("lumaMinSmooth").coerceIn(0f, 1f), stage.float("lumaMaxSmooth").coerceIn(0f, 1f))
            }
            p.copyInto(params, index * FLOATS_PER_STAGE)
        }
        return types to params
    }

    private fun packKeyColorAdjust(p: FloatArray, stage: VideoFilterStage) {
        p.set(8, stage.float("opacity").coerceIn(0f, 1f), stage.float("contrast").coerceIn(0f, 4f),
            stage.float("brightness").coerceIn(-1f, 1f), 1f / max(0.01f, stage.float("gamma")))
    }

    private fun FloatArray.set(offset: Int, a: Float, b: Float, c: Float, d: Float) {
        this[offset] = a; this[offset + 1] = b; this[offset + 2] = c; this[offset + 3] = d
    }

    private fun red(color: Int) = (color shr 16) and 0xFF
    private fun green(color: Int) = (color shr 8) and 0xFF
    private fun blue(color: Int) = color and 0xFF

    fun colorHex(color: Int): String = String.format(Locale.US, "#%08X", color)
}
