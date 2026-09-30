package com.stream4k60.app.data.model

import java.util.UUID

data class Scene(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sources: List<Source> = emptyList(),
    val order: Int = 0,
    val isActive: Boolean = false
)
