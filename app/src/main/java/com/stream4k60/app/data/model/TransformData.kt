package com.stream4k60.app.data.model

import java.io.Serializable

data class TransformData(
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val rotation: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val alignment: Alignment = Alignment.TOP_LEFT,
    val boundsType: BoundsType = BoundsType.NONE,
    val boundsAlignment: Alignment = Alignment.CENTER,
    val boundsWidth: Float = 0f,
    val boundsHeight: Float = 0f,
    val cropLeft: Int = 0,
    val cropRight: Int = 0,
    val cropTop: Int = 0,
    val cropBottom: Int = 0
) : Serializable

enum class Alignment : Serializable {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    CENTER_LEFT, CENTER, CENTER_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
}

enum class BoundsType : Serializable {
    NONE, STRETCH, SCALE_INNER, SCALE_OUTER, SCALE_TO_WIDTH, SCALE_TO_HEIGHT, MAX_ONLY
}
