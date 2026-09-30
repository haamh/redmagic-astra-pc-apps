package com.stream4k60.app.engine

import android.view.KeyEvent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide keyboard dispatcher for an external keyboard/mouse workstation. The Activity owns
 * Android key events; the active Studio screen registers actions here, avoiding Compose focus
 * dependencies and making shortcuts work while a settings panel or source property editor is open.
 */
object HotkeyDispatcher {
    private data class Binding(val keyCode: Int, val modifiers: Int)
    private val down = CopyOnWriteArrayList<Int>()
    @Volatile private var active = false
    @Volatile private var onStartStream: (() -> Unit)? = null
    @Volatile private var onStopStream: (() -> Unit)? = null
    @Volatile private var onStartRecord: (() -> Unit)? = null
    @Volatile private var onStopRecord: (() -> Unit)? = null
    @Volatile private var onReplay: (() -> Unit)? = null
    @Volatile private var onStudio: (() -> Unit)? = null
    @Volatile private var onToggleMic: (() -> Unit)? = null
    @Volatile private var onPtt: ((Boolean) -> Unit)? = null

    // Desktop-friendly defaults. They only activate with Ctrl+Shift so normal Android text input
    // is never consumed.
    private val bindings = mapOf(
        "start_stream" to Binding(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
        "stop_stream" to Binding(KeyEvent.KEYCODE_X, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
        "start_record" to Binding(KeyEvent.KEYCODE_R, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
        "stop_record" to Binding(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
        "replay" to Binding(KeyEvent.KEYCODE_F10, KeyEvent.META_CTRL_ON),
        "studio" to Binding(KeyEvent.KEYCODE_F11, KeyEvent.META_CTRL_ON),
        "toggle_mic" to Binding(KeyEvent.KEYCODE_M, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
        "ptt" to Binding(KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON)
    )

    fun attach(
        startStream: () -> Unit,
        stopStream: () -> Unit,
        startRecord: () -> Unit,
        stopRecord: () -> Unit,
        replay: () -> Unit,
        studio: () -> Unit,
        toggleMic: () -> Unit,
        ptt: (Boolean) -> Unit
    ) {
        onStartStream = startStream
        onStopStream = stopStream
        onStartRecord = startRecord
        onStopRecord = stopRecord
        onReplay = replay
        onStudio = studio
        onToggleMic = toggleMic
        onPtt = ptt
        active = true
    }

    fun detach() {
        active = false
        onStartStream = null
        onStopStream = null
        onStartRecord = null
        onStopRecord = null
        onReplay = null
        onStudio = null
        onToggleMic = null
        onPtt = null
        down.clear()
    }

    fun handle(event: KeyEvent): Boolean {
        if (!active) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
            if (matches("ptt", event)) onPtt?.invoke(true)
            return matchesAnyNonRepeating(event)
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (matches("ptt", event)) onPtt?.invoke(false)
            return matchesAny(event)
        }
        return false
    }

    private fun matchesAnyNonRepeating(event: KeyEvent): Boolean {
        val action = actionFor(event) ?: return false
        if (down.contains(event.keyCode)) return true
        down += event.keyCode
        when (action) {
            "start_stream" -> onStartStream?.invoke()
            "stop_stream" -> onStopStream?.invoke()
            "start_record" -> onStartRecord?.invoke()
            "stop_record" -> onStopRecord?.invoke()
            "replay" -> onReplay?.invoke()
            "studio" -> onStudio?.invoke()
            "toggle_mic" -> onToggleMic?.invoke()
            "ptt" -> Unit
        }
        return true
    }

    private fun matchesAny(event: KeyEvent): Boolean {
        val action = actionFor(event) ?: return false
        down.remove(event.keyCode)
        return action.isNotEmpty()
    }

    private fun matches(name: String, event: KeyEvent): Boolean = bindings[name]?.let { matches(it, event) } ?: false
    private fun actionFor(event: KeyEvent): String? = bindings.entries.firstOrNull { matches(it.value, event) }?.key
    private fun matches(binding: Binding, event: KeyEvent): Boolean {
        val relevant = event.metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON)
        val expected = binding.modifiers and (KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON)
        return event.keyCode == binding.keyCode && relevant == expected
    }
}
