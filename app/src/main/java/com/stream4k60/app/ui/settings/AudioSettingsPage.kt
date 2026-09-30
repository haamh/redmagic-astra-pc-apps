package com.stream4k60.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stream4k60.app.ui.settings.components.SettingsInfo
import com.stream4k60.app.ui.settings.components.SettingsSection

@Composable
fun AudioSettingsPage(viewModel: SettingsViewModel) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SettingsSection("Audio engine") {
            SettingsInfo("Mix format", "48 kHz stereo")
            Text(
                "Audio input sources, gain, pan, mute, solo, monitoring and live meters are managed in the Studio audio mixer. Per-device sample-rate selection and the OBS-style audio filters are not available yet.",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
