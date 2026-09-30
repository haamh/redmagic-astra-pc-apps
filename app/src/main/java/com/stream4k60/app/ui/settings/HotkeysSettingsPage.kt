package com.stream4k60.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stream4k60.app.ui.settings.components.SettingsInfo
import com.stream4k60.app.ui.settings.components.SettingsSection

@Composable
fun HotkeysSettingsPage(viewModel: SettingsViewModel) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SettingsSection("Keyboard shortcuts") {
            SettingsInfo("Start streaming", "Ctrl + Shift + S")
            SettingsInfo("Stop streaming", "Ctrl + Shift + X")
            SettingsInfo("Start recording", "Ctrl + Shift + R")
            SettingsInfo("Stop recording", "Ctrl + Shift + T")
            SettingsInfo("Replay buffer", "Ctrl + F10")
            SettingsInfo("Studio Mode", "Ctrl + F11")
            SettingsInfo("Mute primary microphone", "Ctrl + Shift + M")
            SettingsInfo("Push to talk", "Hold Ctrl + Space")
            Text(
                "These shortcuts are active while the Studio screen is open. Rebinding and conflict detection are not available yet.",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
