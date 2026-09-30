package com.stream4k60.app.engine

import com.stream4k60.app.data.model.AudioInputRoute
import com.stream4k60.app.data.model.AudioMonitoring
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Studio-lifetime native audio graph. It owns the real AAudio inputs and monitor device even when
 * there is no output session. Encoders subscribe to its mixed program bus instead of owning capture.
 */
object NativeAudioGraph {
    private data class RouteKey(val sourceId: String, val deviceId: Int)
    private val sinks = CopyOnWriteArrayList<(ByteBuffer, Long, Int, Int, Int) -> Unit>()
    private val lock = Any()
    @Volatile private var handle: Long = 0L
    private var configuredKeys = emptySet<RouteKey>()
    private var playbackEnabled = false
    private var playbackConfig: AudioInputRoute? = null
    private var externalConfigs = emptyList<AudioInputRoute>()
    private var monitorDeviceId = -1
    private var monitorEnabled = false
    private val callback = object : NativeAudioMixer.Callback {
        override fun onMixed(buffer: ByteBuffer, ptsUs: Long, frames: Int, channels: Int, sampleRate: Int) {
            // Consumers must copy or enqueue quickly. Native capture/mixing remains independent of UI.
            for (sink in sinks) runCatching { sink(buffer, ptsUs, frames, channels, sampleRate) }
        }
    }

    fun configure(
        routes: List<AudioInputRoute>,
        playbackRoute: AudioInputRoute?,
        monitorDevice: Int?,
        monitor: Boolean,
        externalRoutes: List<AudioInputRoute> = emptyList()
    ) {
        synchronized(lock) {
            val newKeys = routes.map { RouteKey(it.sourceId, it.deviceId) }.toSet()
            val external = listOfNotNull(playbackRoute) + externalRoutes.filterNot { it.sourceId == NativeAudioBridge.PLAYBACK_SOURCE_ID }
            val nextKeys = newKeys + external.map { RouteKey(it.sourceId, -1) }
            val monitorChanged = monitorDevice.orDefault() != monitorDeviceId || monitor != monitorEnabled
            if (handle == 0L || monitorChanged) {
                if (handle != 0L) stopLocked()
                handle = NativeAudioMixer.start(callback, 48_000, 2, 480, monitorDevice.orDefault(), monitor)
                check(handle != 0L) { "Native AAudio mixer could not start" }
                configuredKeys = emptySet()
                monitorDeviceId = monitorDevice.orDefault()
                monitorEnabled = monitor
            }
            for (old in configuredKeys) {
                if (old !in nextKeys) {
                    NativeAudioMixer.removeInput(handle, old.sourceId)
                }
            }
            for (route in routes) {
                val key = RouteKey(route.sourceId, route.deviceId)
                if (key !in configuredKeys) {
                    check(NativeAudioMixer.addInput(handle, route.sourceId, route.deviceId, route.volume, route.balance, route.muted, route.monitoring.ordinal, route.syncOffsetMs)) {
                        "Could not open audio input ${route.deviceId} (${route.sourceId})"
                    }
                }
                NativeAudioMixer.setInputConfig(handle, route.sourceId, route.volume, route.balance, route.muted, route.monitoring.ordinal, route.syncOffsetMs, route.solo)
            }
            for (route in external) {
                val key = RouteKey(route.sourceId, -1)
                if (key !in configuredKeys) {
                    check(NativeAudioMixer.addExternalInput(handle, route.sourceId, route.volume, route.balance, route.muted, route.monitoring.ordinal, route.syncOffsetMs, route.solo)) {
                        "Could not create external audio source ${route.sourceId}"
                    }
                }
                NativeAudioMixer.setInputConfig(handle, route.sourceId, route.volume, route.balance, route.muted, route.monitoring.ordinal, route.syncOffsetMs, route.solo)
            }
            NativeAudioBridge.attach(handle)
            configuredKeys = nextKeys
            playbackEnabled = playbackRoute != null
            playbackConfig = playbackRoute
            externalConfigs = externalRoutes.filterNot { it.sourceId == NativeAudioBridge.PLAYBACK_SOURCE_ID }
        }
    }

    /** Compatibility for output sessions created before the studio graph has a source config. */
    fun configure(routes: List<AudioInputRoute>, playback: Boolean, monitorDevice: Int?, monitor: Boolean) {
        val (stored, external) = synchronized(lock) { playbackConfig to externalConfigs }
        val route = if (playback) stored ?: AudioInputRoute(
            sourceId = NativeAudioBridge.PLAYBACK_SOURCE_ID,
            deviceId = -1,
            monitoring = AudioMonitoring.OUTPUT_ONLY
        ) else null
        configure(routes, route, monitorDevice, monitor, external)
    }

    fun pushExternalPcm(sourceId: String, pcm: ByteBuffer, frames: Int, channels: Int, sampleRate: Int, ptsUs: Long): Boolean {
        val current = handle
        return current != 0L && NativeAudioMixer.pushExternalPcm(current, sourceId, pcm, frames, channels, sampleRate, ptsUs)
    }

    fun registerSink(sink: (ByteBuffer, Long, Int, Int, Int) -> Unit) {
        sinks += sink
    }
    fun unregisterSink(sink: (ByteBuffer, Long, Int, Int, Int) -> Unit) {
        sinks -= sink
    }
    fun currentHandle(): Long = handle
    fun hasConfiguredSources(): Boolean = synchronized(lock) { configuredKeys.isNotEmpty() }
    fun peak(sourceId: String): Float = synchronized(lock) { if (handle == 0L) 0f else NativeAudioMixer.getPeak(handle, sourceId) }
    fun setVolume(sourceId: String, route: AudioInputRoute): Boolean = synchronized(lock) {
        if (handle == 0L) return false
        NativeAudioMixer.setInputConfig(handle, sourceId, route.volume, route.balance, route.muted, route.monitoring.ordinal, route.syncOffsetMs, route.solo)
    }
    fun stop() { synchronized(lock) { stopLocked() } }

    private fun stopLocked() {
        if (handle == 0L) return
        val old = handle
        handle = 0L
        configuredKeys = emptySet()
        playbackEnabled = false
        playbackConfig = null
        externalConfigs = emptyList()
        NativeAudioBridge.detach(old)
        runCatching { NativeAudioMixer.stop(old) }
    }

    private fun Int?.orDefault() = this ?: -1
}
