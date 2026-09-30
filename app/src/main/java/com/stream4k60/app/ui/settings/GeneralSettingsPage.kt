package com.stream4k60.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.stream4k60.app.ui.settings.components.SettingsSection
import com.stream4k60.app.ui.settings.components.SettingsToggle

@Composable
fun GeneralSettingsPage(viewModel: SettingsViewModel) {
    val s by viewModel.generalSettings.collectAsState()
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SettingsSection("Output") {
            SettingsToggle("Show confirmation dialog when stopping streams", s.confirmStopStreaming,
                { viewModel.saveGeneralSettings(s.copy(confirmStopStreaming = it)) })
            SettingsToggle("Show confirmation dialog when stopping recordings", s.confirmStopRecording,
                { viewModel.saveGeneralSettings(s.copy(confirmStopRecording = it)) })
            SettingsToggle("Automatically record when streaming", s.autoRecordWhenStreaming,
                { viewModel.saveGeneralSettings(s.copy(autoRecordWhenStreaming = it)) },
                description = "Starts a recording whenever you go live.")
        }
        SettingsSection("Snapping") {
            SettingsToggle("Enable source snapping", s.snappingEnabled,
                { viewModel.saveGeneralSettings(s.copy(snappingEnabled = it)) },
                description = "Dragged sources snap to the canvas edges and centre.")
            SettingsToggle("Snap sources to other sources", s.snapToSources,
                { viewModel.saveGeneralSettings(s.copy(snapToSources = it)) }, enabled = s.snappingEnabled)
        }
    }
}
