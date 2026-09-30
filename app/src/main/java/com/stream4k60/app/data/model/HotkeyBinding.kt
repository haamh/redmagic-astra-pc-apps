package com.stream4k60.app.data.model

import java.util.UUID

data class HotkeyBinding(
    val id: String = UUID.randomUUID().toString(),
    val action: HotkeyAction,
    val keyCode: Int,
    val modifiers: Int = 0,
    val description: String = ""
)

enum class HotkeyAction {
    START_STREAMING, STOP_STREAMING, START_RECORDING, STOP_RECORDING, 
    PAUSE_RECORDING, UNPAUSE_RECORDING, SHOW_SCENE, HIDE_SCENE, 
    MUTE_AUDIO, UNMUTE_AUDIO, PUSH_TO_TALK, PUSH_TO_MUTE
}
