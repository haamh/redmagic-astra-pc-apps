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
fun AccessibilitySettingsPage(viewModel: SettingsViewModel) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SettingsSection("Android accessibility") {
            SettingsInfo("Text scaling", "Uses the Android display size and font size settings")
            SettingsInfo("Screen reader", "Uses Android TalkBack accessibility services")
            Text(
                "App-specific high contrast, reduced motion, and color filters are not implemented yet.",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
