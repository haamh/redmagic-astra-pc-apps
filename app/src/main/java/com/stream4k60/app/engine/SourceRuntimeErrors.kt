package com.stream4k60.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Only reports concrete failures; a configured source is not called live until media is observed. */
object SourceRuntimeErrors {
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors = _errors.asStateFlow()

    @Volatile private var logFile: java.io.File? = null

    /** Also keep every error in Android/data/com.stream4k60.app/files/source-errors.txt (adb shell cat …). */
    fun init(context: android.content.Context) {
        logFile = context.getExternalFilesDir(null)?.let { java.io.File(it, "source-errors.txt") }
    }

    fun report(sourceId: String, message: String) {
        if (_errors.value[sourceId] != message) {
            android.util.Log.w("Stream4k60", "Source $sourceId: $message")
            logFile?.let { f ->
                runCatching {
                    if (f.length() > 256 * 1024) f.writeText("")
                    f.appendText("${java.time.LocalDateTime.now()}  $sourceId  $message\n")
                }
            }
        }
        _errors.update { it + (sourceId to message) }
    }

    fun clear(sourceId: String) {
        _errors.update { if (sourceId in it) it - sourceId else it }
    }

    fun clearAll() {
        _errors.value = emptyMap()
    }
}
