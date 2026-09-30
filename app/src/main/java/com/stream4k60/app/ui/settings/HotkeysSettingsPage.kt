package com.stream4k60.app.ui.settings

import com.stream4k60.app.ui.common.ClosableTitle

import android.view.KeyEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stream4k60.app.data.model.HotkeyAction
import com.stream4k60.app.data.model.HotkeyBinding
import com.stream4k60.app.engine.HotkeyDispatcher
import com.stream4k60.app.ui.settings.components.SettingsButton
import com.stream4k60.app.ui.settings.components.SettingsSection

@Composable
fun HotkeysSettingsPage(viewModel: SettingsViewModel) {
    val bindings by viewModel.hotkeys.collectAsState()
    var capturing by remember { mutableStateOf<HotkeyAction?>(null) }
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(
            "Works with a keyboard, mouse buttons or a controller connected to the tablet, while the Studio is open. Tap Set, then press the combination.",
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsSection("Streaming") {
            listOf(HotkeyAction.START_STREAM, HotkeyAction.STOP_STREAM, HotkeyAction.TOGGLE_STREAM, HotkeyAction.STUDIO_MODE)
                .forEach { HotkeyRow(it, bindings[it], { capturing = it }, { viewModel.saveHotkeys(bindings - it) }) }
        }
        SettingsSection("Audio") {
            listOf(HotkeyAction.MUTE_MIC, HotkeyAction.PUSH_TO_TALK)
                .forEach { HotkeyRow(it, bindings[it], { capturing = it }, { viewModel.saveHotkeys(bindings - it) }) }
        }
        SettingsSection("Scenes") {
            HotkeyAction.entries.filter { it.name.startsWith("SCENE_") }
                .forEach { HotkeyRow(it, bindings[it], { capturing = it }, { viewModel.saveHotkeys(bindings - it) }) }
        }
        SettingsButton("Reset all to defaults", { viewModel.saveHotkeys(HotkeyDispatcher.defaultBindings) }, destructive = true)
    }
    capturing?.let { action ->
        KeyCaptureDialog(
            action = action,
            bindings = bindings,
            onSave = { binding ->
                // A combination can only do one thing: the action that had it loses it.
                viewModel.saveHotkeys(bindings.filterValues { it != binding } + (action to binding))
                capturing = null
            },
            onDismiss = { capturing = null }
        )
    }
}

@Composable
private fun HotkeyRow(action: HotkeyAction, binding: HotkeyBinding?, onSet: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(action.label, fontSize = 12.sp, modifier = Modifier.weight(0.4f))
        Box(
            Modifier.weight(0.35f).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text(binding?.let(HotkeyDispatcher::describe) ?: "Not set", fontSize = 12.sp,
                color = if (binding == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
        TextButton(onClick = onSet) { Text("Set", fontSize = 12.sp) }
        TextButton(onClick = onClear, enabled = binding != null) { Text("Clear", fontSize = 12.sp) }
    }
}

private val modifierKeys = setOf(
    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
    KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK
)

@Composable
private fun KeyCaptureDialog(
    action: HotkeyAction,
    bindings: Map<HotkeyAction, HotkeyBinding>,
    onSave: (HotkeyBinding) -> Unit,
    onDismiss: () -> Unit
) {
    var pending by remember { mutableStateOf<HotkeyBinding?>(null) }
    val focus = remember { FocusRequester() }
    val conflict = pending?.let { p -> bindings.entries.firstOrNull { it.value == p && it.key != action }?.key }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ClosableTitle(action.label, onDismiss) },
        text = {
            Column(
                Modifier.fillMaxWidth().focusRequester(focus).focusable().onPreviewKeyEvent { e ->
                    val native = e.nativeKeyEvent
                    if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent true
                    val mods = native.metaState and HotkeyDispatcher.MODIFIER_MASK
                    when {
                        native.keyCode in modifierKeys -> Unit // wait for the main key
                        (native.keyCode == KeyEvent.KEYCODE_ESCAPE || native.keyCode == KeyEvent.KEYCODE_BACK) && mods == 0 -> onDismiss()
                        else -> pending = HotkeyBinding(native.keyCode, mods)
                    }
                    true
                }
            ) {
                Text(pending?.let(HotkeyDispatcher::describe) ?: "Press a key or combination…",
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                when {
                    conflict != null -> Text("Already used by \"${conflict.label}\". Saving moves it here and leaves that action unbound.",
                        color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    pending?.let { it.modifiers == 0 && !KeyEvent.isGamepadButton(it.keyCode) } == true ->
                        Text("No Ctrl/Alt/Shift: this key won't type in text boxes on the Studio screen while bound.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    else -> Text("Esc cancels.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = {
            TextButton(onClick = { pending?.let(onSave) }, enabled = pending != null) { Text(if (conflict != null) "Replace" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

