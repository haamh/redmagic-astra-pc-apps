package com.stream4k60.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.stream4k60.app.ui.settings.components.SettingsButton
import com.stream4k60.app.ui.settings.components.SettingsInfo
import com.stream4k60.app.ui.settings.components.SettingsSection
import com.stream4k60.app.ui.settings.components.SettingsSlider

@Composable
fun AccessibilitySettingsPage(viewModel: SettingsViewModel) {
    val s by viewModel.accessibilitySettings.collectAsState()
    // Applied on release so the page doesn't resize under your finger while dragging.
    var draft by remember(s.uiScale) { mutableFloatStateOf(s.uiScale) }
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SettingsSection("Interface") {
            SettingsSlider("UI scale", draft, { draft = (it * 20).let(Math::round) / 20f },
                valueRange = 0.75f..1.5f, displayValue = "${(draft * 100).toInt()}%",
                description = "Makes buttons and text in the whole app bigger or smaller. 100% fits the most on the Astra's screen.")
            SettingsButton("Apply ${(draft * 100).toInt()}%", { viewModel.saveAccessibilitySettings(s.copy(uiScale = draft)) }, enabled = draft != s.uiScale)
            SettingsButton("Reset to 100%", { draft = 1f; viewModel.saveAccessibilitySettings(s.copy(uiScale = 1f)) }, enabled = s.uiScale != 1f)
        }
        SettingsSection("Android accessibility", initiallyExpanded = false) {
            SettingsInfo("Font size", "Also follows Android Settings → Display → Font size")
            SettingsInfo("Screen reader", "Works with Android TalkBack")
        }
    }
}
