package com.stream4k60.app.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioFilterChainTest {
    @Test
    fun gateCloseFollowsStartListeningLevel() {
        val gate = AudioFilterChain.gateConfig(AudioFilterStage(type = AudioFilterType.NOISE_GATE).with("openDb", -40.0))
        assertEquals(-40f, gate.openDb, 0f)
        assertEquals(-46f, gate.closeDb, 0f)
        assertEquals(25f, gate.attackMs, 0f)
    }

    @Test
    fun manualCloseThresholdIsClampedBelowOpen() {
        val stage = AudioFilterStage(type = AudioFilterType.NOISE_GATE)
            .with("openDb", -30.0).with("closeFollowsOpen", false).with("closeDb", -10.0)
        assertEquals(-30f, AudioFilterChain.gateConfig(stage).closeDb, 0f)
    }

    @Test
    fun writeReadRoundTripAndDisabledGateIsIgnored() {
        val stages = listOf(AudioFilterStage(type = AudioFilterType.NOISE_GATE, enabled = false).with("openDb", -20.0))
        val config = AudioFilterChain.write(JSONObject().put("settings", JSONObject().put("deviceId", 3)).toString(), stages)
        val read = AudioFilterChain.read(config)
        assertEquals(stages.map { it.id }, read.map { it.id })
        assertEquals(-20f, read.single().float("openDb"), 0f)
        assertEquals(3, JSONObject(config).getJSONObject("settings").getInt("deviceId"))
        assertNull(AudioFilterChain.noiseGate(config))
    }

    @Test
    fun mixerInputIds() {
        assertEquals("usb_audio_a", AudioFilterChain.mixerInputId("a", "USB_CAPTURE"))
        assertEquals("media_audio_a", AudioFilterChain.mixerInputId("a", "media"))
        assertEquals("a", AudioFilterChain.mixerInputId("a", "AUDIO_INPUT"))
    }
}
