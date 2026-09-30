package com.stream4k60.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.stream4k60.app.ui.settings.components.SettingsNumberInput
import com.stream4k60.app.ui.settings.components.SettingsSection
import com.stream4k60.app.ui.settings.components.SettingsToggle

@Composable
fun AdvancedSettingsPage(viewModel: SettingsViewModel) {
    val s by viewModel.advancedSettings.collectAsState()
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SettingsSection("Automatically reconnect") {
            SettingsToggle("Enable", s.autoReconnect, { viewModel.saveAdvancedSettings(s.copy(autoReconnect = it)) },
                description = "If Wi-Fi or mobile data drops mid-stream, keep trying to get back live instead of ending the stream.")
            SettingsNumberInput("Retry delay", s.reconnectDelaySec, { viewModel.saveAdvancedSettings(s.copy(reconnectDelaySec = it)) },
                min = 1, max = 30, unit = "s", enabled = s.autoReconnect,
                description = "Wait before the first retry. Each later retry waits a little longer, up to 30 s.")
            SettingsNumberInput("Maximum retries", s.maxRetries, { viewModel.saveAdvancedSettings(s.copy(maxRetries = it)) },
                min = 1, max = 100, enabled = s.autoReconnect,
                description = "Give up and stop the stream after this many failed attempts.")
        }
    }
}
