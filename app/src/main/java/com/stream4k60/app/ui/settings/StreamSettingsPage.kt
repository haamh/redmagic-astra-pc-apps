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
fun StreamSettingsPage(viewModel: SettingsViewModel) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SettingsSection("Streaming workflow") {
            SettingsInfo("YouTube", "Connect and choose a broadcast from the Studio toolbar. YouTube Live ingest is limited to 60 FPS.")
            SettingsInfo("Custom RTMP(S)", "Enter a custom ingest URL and stream key from the Studio toolbar to stream to a compatible server, including servers that accept up to 120 FPS.")
            Text(
                "Values entered manually stay in the current app session. Imported OBS service metadata remains with its profile and can prefill the endpoint, but credentials are not stored in an encrypted account vault. The ingest server must accept the selected codec, resolution, bitrate and frame rate; Astra's encoder capability query cannot verify a remote server's limits. The active video output settings are in Output and Video.",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
