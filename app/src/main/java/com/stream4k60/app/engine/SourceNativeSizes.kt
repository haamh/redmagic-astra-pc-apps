package com.stream4k60.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Real pixel size of sources whose size is only known at runtime: auto-sized text and media whose video size
 * was not known at import (e.g. URLs). Layout uses it like OBS does, so imported OBS scales apply to the real size.
 */
object SourceNativeSizes {
    private val _sizes = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val sizes = _sizes.asStateFlow()

    fun report(sourceId: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        _sizes.update { if (it[sourceId] == (width to height)) it else it + (sourceId to (width to height)) }
    }

    fun get(sourceId: String): Pair<Int, Int>? = _sizes.value[sourceId]
}
