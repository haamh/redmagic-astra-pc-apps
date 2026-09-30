package com.stream4k60.app.engine

interface RtmpClient {
    fun connect(url: String, streamKey: String)
    fun disconnect()
    fun sendVideo(data: ByteArray, timestamp: Long)
    fun sendAudio(data: ByteArray, timestamp: Long)
}
