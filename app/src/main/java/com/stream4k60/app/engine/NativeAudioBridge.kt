package com.stream4k60.app.engine

/** Process-local bridge used by the MediaProjection playback-capture service. */
object NativeAudioBridge {
    @Volatile private var mixerHandle: Long = 0L
    const val PLAYBACK_SOURCE_ID = "android_playback_audio"

    fun attach(handle: Long) { mixerHandle = handle }
    fun detach(handle: Long) { if (mixerHandle == handle) mixerHandle = 0L }
    fun handle(): Long = mixerHandle
}
