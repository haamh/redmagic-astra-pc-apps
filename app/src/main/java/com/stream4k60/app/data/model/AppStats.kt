package com.stream4k60.app.data.model

data class AppStats(
    val cpuUsage: Double = 0.0,
    val memoryUsage: Long = 0,
    val fps: Double = 0.0,
    val droppedFrames: Long = 0,
    val totalFrames: Long = 0,
    val streamDuration: Long = 0,
    val recordDuration: Long = 0,
    val bitrate: Int = 0,
    val renderTime: Double = 0.0,
    val encodingTime: Double = 0.0,
    val diskSpaceAvailable: Long = 0
)
