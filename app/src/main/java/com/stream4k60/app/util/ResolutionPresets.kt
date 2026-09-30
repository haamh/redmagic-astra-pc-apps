package com.stream4k60.app.util

object ResolutionPresets {
    val UHD_4K = Pair(3840, 2160)
    val QHD_1440P = Pair(2560, 1440)
    val FHD_1080P = Pair(1920, 1080)
    val HD_720P = Pair(1280, 720)
    val SD_480P = Pair(854, 480)

    fun calculateDownscale(canvas: Pair<Int, Int>, output: Pair<Int, Int>): Float {
        return output.first.toFloat() / canvas.first.toFloat()
    }
}
