package com.stream4k60.app.ui.settings

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.stream4k60.app.data.model.AudioSettings
import com.stream4k60.app.ui.settings.components.SettingsDropdown
import com.stream4k60.app.ui.settings.components.SettingsInfo
import com.stream4k60.app.ui.settings.components.SettingsNumberInput
import com.stream4k60.app.ui.settings.components.SettingsSection
import com.stream4k60.app.ui.settings.components.SettingsToggle

@Composable
fun AudioSettingsPage(viewModel: SettingsViewModel) {
    val s by viewModel.audioSettings.collectAsState()
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val inputs = remember { am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }.map { it.id to deviceLabel(it) } }
    val outputs = remember { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }.map { it.id to deviceLabel(it) } }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SettingsSection("Global audio devices") {
            SettingsToggle("Desktop Audio", s.desktopAudioEnabled, { viewModel.saveAudioSettings(s.copy(desktopAudioEnabled = it)) },
                description = "Game and app sound, captured with Android playback capture. It is in every scene, so you don't add it per scene. Android asks for capture permission the first time.")
            val micOptions = listOf(AudioSettings.MIC_DEFAULT to "Default", AudioSettings.MIC_DISABLED to "Disabled") + inputs
            SettingsDropdown("Mic/Aux Audio", micOptions.map { it.second },
                micOptions.firstOrNull { it.first == s.micDeviceId }?.second ?: "Default",
                { label -> micOptions.firstOrNull { it.second == label }?.let { viewModel.saveAudioSettings(s.copy(micDeviceId = it.first)) } },
                description = "Your microphone, in every scene. Pick a USB headset or capture card mic, or Default for the tablet's own mic.")
        }
        SettingsSection("Monitoring") {
            val monitorOptions = listOf(-1 to "Default") + outputs
            SettingsDropdown("Monitoring device", monitorOptions.map { it.second },
                monitorOptions.firstOrNull { it.first == s.monitorDeviceId }?.second ?: "Default",
                { label -> monitorOptions.firstOrNull { it.second == label }?.let { viewModel.saveAudioSettings(s.copy(monitorDeviceId = it.first)) } },
                description = "Where you hear sources set to \"Monitor\" in the mixer. Use headphones so viewers don't hear an echo.")
        }
        SettingsSection("Advanced", initiallyExpanded = false) {
            SettingsToggle("Push-to-talk", s.pushToTalk, { viewModel.saveAudioSettings(s.copy(pushToTalk = it, pushToMute = if (it) false else s.pushToMute)) },
                description = "Mic stays muted until you hold the Push-to-talk hotkey (Settings → Hotkeys).")
            SettingsToggle("Push-to-mute", s.pushToMute, { viewModel.saveAudioSettings(s.copy(pushToMute = it, pushToTalk = if (it) false else s.pushToTalk)) },
                description = "Mic is live; holding the Push-to-talk hotkey mutes it instead.")
            SettingsNumberInput("Release delay", s.pushDelayMs, { viewModel.saveAudioSettings(s.copy(pushDelayMs = it)) },
                min = 0, max = 2000, step = 50, unit = "ms", enabled = s.pushToTalk || s.pushToMute,
                description = "How long the mic stays open after you let go, so the end of a word isn't cut off.")
            SettingsInfo("Sample rate", "48 kHz stereo (fixed, what streaming services expect)")
        }
    }
}

private fun deviceLabel(d: AudioDeviceInfo): String {
    val kind = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
        else -> "Device"
    }
    val name = d.productName?.toString().orEmpty()
    return if (name.isBlank()) "$kind #${d.id}" else "$kind: $name (#${d.id})"
}
