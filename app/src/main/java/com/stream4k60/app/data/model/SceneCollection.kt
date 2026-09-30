package com.stream4k60.app.data.model

import java.util.UUID

data class SceneCollection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val scenes: List<Scene> = emptyList(),
    val transitions: List<SceneTransition> = emptyList(),
    val isActive: Boolean = false
)
