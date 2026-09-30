package com.stream4k60.app.engine

import java.nio.ByteBuffer

/** Native AAudio real-time mixer. Capture and monitoring stay in native callback threads. */
object NativeAudioMixer {
    init { System.loadLibrary("stream4k60_engine") }

    interface Callback {
        fun onMixed(buffer: ByteBuffer, ptsUs: Long, frames: Int, channels: Int, sampleRate: Int)
    }

    external fun start(callback: Callback, sampleRate: Int, channels: Int, blockFrames: Int, monitorDeviceId: Int, monitorEnabled: Boolean): Long
    external fun addInput(handle: Long, sourceId: String, deviceId: Int, initialVolume: Float, balance: Float, muted: Boolean, monitoring: Int, syncOffsetMs: Int): Boolean
    external fun addExternalInput(handle: Long, sourceId: String, initialVolume: Float, balance: Float, muted: Boolean, monitoring: Int, syncOffsetMs: Int, solo: Boolean): Boolean
    external fun pushExternalPcm(handle: Long, sourceId: String, pcm: ByteBuffer, frames: Int, channels: Int, sampleRate: Int, ptsUs: Long): Boolean
    external fun removeInput(handle: Long, sourceId: String): Boolean
    external fun setInputConfig(handle: Long, sourceId: String, volume: Float, balance: Float, muted: Boolean, monitoring: Int, syncOffsetMs: Int, solo: Boolean): Boolean
    external fun setInputGate(handle: Long, sourceId: String, enabled: Boolean, openDb: Float, closeDb: Float, attackMs: Float, holdMs: Float, releaseMs: Float): Boolean
    external fun stop(handle: Long)
    external fun setMonitorVolume(handle: Long, volume: Float)
    external fun setMonitorMuted(handle: Long, muted: Boolean)
    external fun getPeak(handle: Long, sourceId: String): Float
}
