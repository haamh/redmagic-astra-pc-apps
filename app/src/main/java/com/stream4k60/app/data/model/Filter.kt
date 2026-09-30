package com.stream4k60.app.data.model

import java.util.UUID

data class Filter(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: FilterType,
    val enabled: Boolean = true,
    val settings: Map<String, Any> = emptyMap(),
    val order: Int = 0
)

enum class FilterType {
    COLOR_CORRECTION, CHROMA_KEY, LUT, CROP_PAD, SHARPEN, SCROLL, COLOR_KEY, 
    BLEND, MASK, RENDER_DELAY, NOISE_SUPPRESS, NOISE_GATE, COMPRESSOR, 
    LIMITER, EXPANDER, GAIN, VST, INVERT_POLARITY
}
