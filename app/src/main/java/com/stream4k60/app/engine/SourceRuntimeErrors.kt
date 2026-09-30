package com.stream4k60.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Only reports concrete failures; a configured source is not called live until media is observed. */
object SourceRuntimeErrors {
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors = _errors.asStateFlow()

    fun report(sourceId: String, message: String) {
        _errors.update { it + (sourceId to message) }
    }

    fun clear(sourceId: String) {
        _errors.update { if (sourceId in it) it - sourceId else it }
    }

    fun clearAll() {
        _errors.value = emptyMap()
    }
}
