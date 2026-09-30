package com.stream4k60.app.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFilterChainTest {
    @Test
    fun legacyEffectsBecomeColorThenChromaStages() {
        val config = JSONObject().put("settings", JSONObject().put("effects", JSONObject()
            .put("brightness", 0.2).put("contrast", 1.5)
            .put("chromaKeyEnabled", true).put("chromaKeyColor", "#FF0000FF").put("chromaSimilarity", 0.3)))
        val stages = VideoFilterChain.read(config.toString())
        assertEquals(listOf(VideoFilterType.COLOR_CORRECTION, VideoFilterType.CHROMA_KEY), stages.map { it.type })
        assertEquals(0.2f, stages[0].float("brightness"), 1e-6f)
        assertEquals(1.5f, stages[0].float("contrast"), 1e-6f)
        assertEquals(0xFF0000FF.toInt(), stages[1].color("keyColor"))
        assertEquals(0.3f, stages[1].float("similarity"), 1e-6f)
    }

    @Test
    fun defaultLegacyEffectsProduceNoStages() {
        val config = JSONObject().put("effects", JSONObject().put("brightness", 0.0).put("chromaKeyEnabled", false))
        assertTrue(VideoFilterChain.read(config.toString()).isEmpty())
    }

    @Test
    fun writeThenReadPreservesOrderAndRemovesLegacyEffects() {
        val original = JSONObject().put("settings", JSONObject().put("url", "x").put("effects", JSONObject().put("brightness", 0.5)))
        val stages = listOf(
            VideoFilterStage(type = VideoFilterType.LUMA_KEY, name = "Luma", enabled = false).with("lumaMin", 0.25),
            VideoFilterStage(type = VideoFilterType.COLOR_KEY).with("keyColor", "#FFFF00FF")
        )
        val written = JSONObject(VideoFilterChain.write(original.toString(), stages))
        val settings = written.getJSONObject("settings")
        assertFalse(settings.has("effects"))
        assertEquals("x", settings.getString("url"))
        val read = VideoFilterChain.read(written.toString())
        assertEquals(stages.map { it.id }, read.map { it.id })
        assertEquals(listOf(VideoFilterType.LUMA_KEY, VideoFilterType.COLOR_KEY), read.map { it.type })
        assertFalse(read[0].enabled)
        assertEquals(0.25f, read[0].float("lumaMin"), 1e-6f)
        assertEquals(0xFFFF00FF.toInt(), read[1].color("keyColor"))
    }

    @Test
    fun packSkipsDisabledStagesAndCapsAtMax() {
        val stages = List(VideoFilterChain.MAX_STAGES + 3) { i ->
            VideoFilterStage(type = VideoFilterType.LUMA_KEY, enabled = i != 0).with("lumaMin", i / 100.0)
        }
        val (types, params) = VideoFilterChain.pack(stages)
        assertEquals(VideoFilterChain.MAX_STAGES, types.size)
        assertEquals(VideoFilterChain.MAX_STAGES * VideoFilterChain.FLOATS_PER_STAGE, params.size)
        // First packed stage is the first enabled one (index 1).
        assertEquals(0.01f, params[0], 1e-6f)
        assertTrue(types.all { it == VideoFilterType.LUMA_KEY.nativeId })
    }

    @Test
    fun packColorCorrectionLayout() {
        val stage = VideoFilterStage(type = VideoFilterType.COLOR_CORRECTION)
            .with("brightness", 0.1).with("contrast", 1.2).with("saturation", 0.5).with("gamma", 2.0)
            .with("hueDegrees", 30.0).with("opacity", 0.75).with("colorMultiply", "#FF804020").with("colorAdd", "#FF102030")
        val (types, p) = VideoFilterChain.pack(listOf(stage))
        assertEquals(1, types.single())
        assertEquals(listOf(0.1f, 1.2f, 0.5f, 0.5f), p.slice(0..3))           // gamma is sent as exponent 1/gamma
        assertEquals(listOf(30f, 0.75f), p.slice(4..5))
        assertEquals(0x80 / 255f, p[8], 1e-6f); assertEquals(0x40 / 255f, p[9], 1e-6f); assertEquals(0x20 / 255f, p[10], 1e-6f)
        assertEquals(0x10 / 255f, p[12], 1e-6f); assertEquals(0x20 / 255f, p[13], 1e-6f); assertEquals(0x30 / 255f, p[14], 1e-6f)
    }

    @Test
    fun packChromaKeyUsesCbCrOfKeyColor() {
        val (_, p) = VideoFilterChain.pack(listOf(VideoFilterStage(type = VideoFilterType.CHROMA_KEY)))
        // Pure green in the shader's BT.709 limited-range projection.
        assertEquals(-0.398942f + 0.501961f, p[0], 1e-5f)                  // Cr
        assertEquals(-0.338572f + 0.501961f, p[1], 1e-5f)                  // Cb
        assertEquals(0.4f, p[2], 1e-6f)
        assertEquals(0.08f, p[3], 1e-6f)
        assertEquals(0.1f, p[4], 1e-6f)
        assertEquals(listOf(1f, 1f, 0f, 1f), p.slice(8..11))
    }

    @Test
    fun parseColorFormats() {
        assertEquals(0xFF112233.toInt(), VideoFilterStage.parseColor("#112233"))
        assertEquals(0x80112233.toInt(), VideoFilterStage.parseColor("#80112233"))
        assertNull(VideoFilterStage.parseColor("#12345"))
        assertNull(VideoFilterStage.parseColor("green"))
    }

    @Test
    fun unknownStageTypesAreIgnored() {
        val config = JSONObject().put("videoFilters", org.json.JSONArray()
            .put(JSONObject().put("type", "RENDER_DELAY"))
            .put(JSONObject().put("type", "LUMA_KEY")))
        assertEquals(listOf(VideoFilterType.LUMA_KEY), VideoFilterChain.read(config.toString()).map { it.type })
    }

    @Test
    fun lutStagesGetSlotsAndExtraLutsAreSkipped() {
        val stages = listOf(
            VideoFilterStage(type = VideoFilterType.LUT).with("path", "a.cube").with("amount", 0.5),
            VideoFilterStage(type = VideoFilterType.SHARPEN).with("sharpness", 0.2),
            VideoFilterStage(type = VideoFilterType.LUT).with("path", "b.png"),
            VideoFilterStage(type = VideoFilterType.LUT).with("path", "c.cube")
        )
        val (types, p) = VideoFilterChain.pack(stages)
        assertEquals(listOf(5, 6, 5), types.toList())
        assertEquals(0.5f, p[0], 0f); assertEquals(0f, p[1], 0f)                       // first LUT, slot 0
        assertEquals(0.2f, p[16], 1e-6f)                                                   // sharpen
        assertEquals(1f, p[32], 0f); assertEquals(1f, p[33], 0f)                          // second LUT, slot 1
        assertEquals(listOf("a.cube", "b.png"), VideoFilterChain.lutPaths(stages))
    }
}
