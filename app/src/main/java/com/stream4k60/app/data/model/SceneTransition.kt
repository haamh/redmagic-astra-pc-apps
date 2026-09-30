package com.stream4k60.app.data.model

import java.util.UUID

data class SceneTransition(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: TransitionType,
    val durationMs: Int = 300,
    val properties: Map<String, Any> = emptyMap()
)

enum class TransitionType {
    CUT, FADE, SLIDE, SWIPE, STINGER, FADE_TO_COLOR, LUMA_WIPE
}
