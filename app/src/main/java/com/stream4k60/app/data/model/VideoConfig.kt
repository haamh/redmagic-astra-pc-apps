package com.stream4k60.app.data.model

data class VideoConfig(
    val baseResWidth: Int = 3840,
    val baseResHeight: Int = 2160,
    val outputResWidth: Int = 3840,
    val outputResHeight: Int = 2160,
    val videoBitrateKbps: Int = 40_000,
    val outputCodec: OutputCodec = OutputCodec.H264,
    val fpsType: FpsType = FpsType.COMMON,
    val fpsCommon: FpsCommon = FpsCommon.FPS_60,
    val fpsInt: Int = 60,
    val fpsNum: Int = 60,
    val fpsDen: Int = 1,
    val downscaleFilter: DownscaleFilter = DownscaleFilter.BICUBIC,
    val colorFormat: ColorFormat = ColorFormat.NV12,
    val colorSpace: ColorSpace = ColorSpace.SRGB,
    val colorRange: ColorRange = ColorRange.PARTIAL,
    val hdrEnabled: Boolean = false
) {
    val frameRate: Int
        get() = when (fpsType) {
            FpsType.COMMON -> fpsCommon.value
            FpsType.INTEGER -> fpsInt.coerceIn(1, 120)
            FpsType.FRACTIONAL -> (fpsNum.toDouble() / fpsDen.coerceAtLeast(1)).toInt().coerceIn(1, 120)
        }
}

enum class FpsType {
    COMMON, INTEGER, FRACTIONAL
}

enum class FpsCommon(val value: Int) {
    FPS_30(30), FPS_60(60), FPS_120(120)
}

enum class DownscaleFilter {
    BILINEAR, BICUBIC, LANCZOS, AREA
}

enum class ColorFormat {
    NV12, I420, I444, P010, I010
}

enum class ColorSpace {
    SRGB, REC709, REC2100PQ, REC2100HLG
}

enum class ColorRange {
    PARTIAL, FULL
}
