package com.stream4k60.app.engine

import android.view.KeyEvent
import com.stream4k60.app.data.model.HotkeyAction
import com.stream4k60.app.data.model.HotkeyBinding
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide keyboard dispatcher for an external keyboard, mouse or controller. The Activity owns Android key
 * events; the Studio screen registers actions here, so shortcuts work while a settings panel or editor is open.
 * Bindings come from Settings → Hotkeys.
 */
object HotkeyDispatcher {
    const val MODIFIER_MASK = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON

    /** Defaults use Ctrl/Ctrl+Shift so ordinary typing is never consumed. */
    val defaultBindings: Map<HotkeyAction, HotkeyBinding> = mapOf(
        HotkeyAction.START_STREAM to HotkeyBinding(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
        HotkeyAction.STOP_STREAM to HotkeyBinding(KeyEvent.KEYCODE_X, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
        HotkeyAction.STUDIO_MODE to HotkeyBinding(KeyEvent.KEYCODE_F11, KeyEvent.META_CTRL_ON),
        HotkeyAction.MUTE_MIC to HotkeyBinding(KeyEvent.KEYCODE_M, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
        HotkeyAction.PUSH_TO_TALK to HotkeyBinding(KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON)
    ) + (1..9).associate { n -> HotkeyAction.valueOf("SCENE_$n") to HotkeyBinding(KeyEvent.KEYCODE_1 + n - 1, KeyEvent.META_CTRL_ON) }

    @Volatile private var bindings: Map<HotkeyAction, HotkeyBinding> = defaultBindings
    @Volatile private var handler: ((HotkeyAction, Boolean) -> Unit)? = null
    private val held = CopyOnWriteArraySet<HotkeyAction>()

    fun setBindings(value: Map<HotkeyAction, HotkeyBinding>) { bindings = value }

    /** [onAction] receives each action; `pressed` is false only for the release of push-to-talk. */
    fun attach(onAction: (HotkeyAction, pressed: Boolean) -> Unit) { handler = onAction }

    fun detach() { handler = null; held.clear() }

    fun handle(event: KeyEvent): Boolean {
        val h = handler ?: return false
        val action = actionFor(event) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Fire once per press; auto-repeat while held is swallowed.
                if (event.repeatCount == 0 && held.add(action)) h(action, true)
            }
            KeyEvent.ACTION_UP -> {
                held.remove(action)
                if (action == HotkeyAction.PUSH_TO_TALK) h(action, false)
            }
        }
        return true
    }

    fun actionFor(event: KeyEvent): HotkeyAction? {
        val mods = event.metaState and MODIFIER_MASK
        return bindings.entries.firstOrNull { (_, b) -> b.keyCode == event.keyCode && (b.modifiers and MODIFIER_MASK) == mods }?.key
    }

    /** Human-readable form such as "Ctrl+Shift+S". */
    fun describe(binding: HotkeyBinding): String = buildList {
        if (binding.modifiers and KeyEvent.META_CTRL_ON != 0) add("Ctrl")
        if (binding.modifiers and KeyEvent.META_ALT_ON != 0) add("Alt")
        if (binding.modifiers and KeyEvent.META_SHIFT_ON != 0) add("Shift")
        if (binding.modifiers and KeyEvent.META_META_ON != 0) add("Meta")
        add(KeyEvent.keyCodeToString(binding.keyCode).removePrefix("KEYCODE_").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
    }.joinToString("+")
}
