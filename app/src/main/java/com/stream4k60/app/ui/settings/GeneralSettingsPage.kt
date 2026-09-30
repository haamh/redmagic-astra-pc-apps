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
fun GeneralSettingsPage(viewModel: SettingsViewModel) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SettingsSection("Current behavior") {
            SettingsInfo("Studio appearance", "OBS-inspired dark theme")
            SettingsInfo("Profile video settings", "Saved automatically to the active profile")
            Text(
                "OBS profile and scene-collection import is available from Profiles. Confirmation prompts, automatic recording, snapping, and theme/language preferences are not configurable here yet.",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
