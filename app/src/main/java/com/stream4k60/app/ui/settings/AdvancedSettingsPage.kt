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
import com.stream4k60.app.ui.settings.components.SettingsSection

@Composable
fun AdvancedSettingsPage(viewModel: SettingsViewModel) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SettingsSection("Advanced output controls") {
            Text(
                "Recording format and destination, stream delay, dynamic bitrate, remuxing, and detailed encoder rate control are not configurable yet. Output currently exposes only the profile-persisted stream bitrate and codec.",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
