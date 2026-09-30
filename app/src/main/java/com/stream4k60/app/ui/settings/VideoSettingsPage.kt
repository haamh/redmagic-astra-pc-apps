package com.stream4k60.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.stream4k60.app.data.model.FpsCommon
import com.stream4k60.app.data.model.FpsType
import com.stream4k60.app.ui.settings.components.AdvancedSection
import com.stream4k60.app.ui.settings.components.SettingsDropdown
import com.stream4k60.app.ui.settings.components.SettingsNumberInput
import com.stream4k60.app.ui.settings.components.SettingsSection

private data class ResolutionPreset(val label: String, val width: Int, val height: Int)

private val astraCanvasPreset = ResolutionPreset("Astra native panel (2400 × 1504, 16:10)", 2400, 1504)

private val resolutionPresets = listOf(
    ResolutionPreset("3840 × 2160 (4K UHD)", 3840, 2160),
    ResolutionPreset("2560 × 1440 (1440p)", 2560, 1440),
    ResolutionPreset("1920 × 1080 (1080p)", 1920, 1080),
    ResolutionPreset("1280 × 720 (720p)", 1280, 720),
    ResolutionPreset("854 × 480 (480p)", 854, 480)
)

private fun resolutionLabel(width: Int, height: Int, presets: List<ResolutionPreset> = resolutionPresets): String =
    presets.firstOrNull { it.width == width && it.height == height }?.label ?: "Custom (${width} × $height)"

@Composable
fun VideoSettingsPage(viewModel: SettingsViewModel) {
    val config by viewModel.videoConfig.collectAsState()
    val scrollState = rememberScrollState()
    val baseCanvasPresets = listOf(astraCanvasPreset) + resolutionPresets
    val outputPresets = resolutionPresets

    Column(modifier = Modifier.verticalScroll(scrollState)) {
        SettingsSection("Video") {
            SettingsDropdown(
                label = "Base (Canvas) Resolution",
                options = baseCanvasPresets.map { it.label },
                selected = resolutionLabel(config.baseResWidth, config.baseResHeight, baseCanvasPresets),
                description = "Sets the scene coordinate space and preview layout. Use the Astra native panel for a full-screen 16:10 layout, or a 16:9 canvas for common streaming output.",
                onSelect = { selected ->
                    baseCanvasPresets.firstOrNull { it.label == selected }?.let {
                        viewModel.setVideoConfig(config.copy(baseResWidth = it.width, baseResHeight = it.height))
                    }
                }
            )
            SettingsDropdown(
                label = "Output (Scaled) Resolution",
                options = outputPresets.map { it.label },
                selected = resolutionLabel(config.outputResWidth, config.outputResHeight),
                description = "Sets the encoded stream/recording size independently of the canvas. Example: 1920 × 1080 output from a 4K canvas.",
                onSelect = { selected ->
                    resolutionPresets.firstOrNull { it.label == selected }?.let {
                        viewModel.setVideoConfig(config.copy(outputResWidth = it.width, outputResHeight = it.height))
                    }
                }
            )
            SettingsDropdown(
                label = "Common FPS Values",
                options = listOf("30 FPS", "60 FPS", "120 FPS"),
                selected = "${config.frameRate} FPS",
                description = "Controls compositor pacing and the requested encoder frame rate. 120 FPS requires an Astra hardware encoder and an ingest service that accepts 120 FPS; YouTube Live is limited to 60 FPS.",
                onSelect = { selected ->
                    val fps = selected.substringBefore(' ').toIntOrNull() ?: return@SettingsDropdown
                    val common = FpsCommon.entries.firstOrNull { it.value == fps } ?: FpsCommon.FPS_60
                    viewModel.setVideoConfig(config.copy(fpsType = FpsType.COMMON, fpsCommon = common, fpsInt = fps))
                }
            )
        }

        AdvancedSection("Custom dimensions and frame rate") {
            SettingsNumberInput(
                label = "Canvas width",
                value = config.baseResWidth,
                onValueChange = { viewModel.setVideoConfig(config.copy(baseResWidth = it)) },
                min = 320,
                max = 3840,
                unit = "px",
                description = "Scene canvas width. Example: 3840 px. Very wide canvases use more GPU memory."
            )
            SettingsNumberInput(
                label = "Canvas height",
                value = config.baseResHeight,
                onValueChange = { viewModel.setVideoConfig(config.copy(baseResHeight = it)) },
                min = 240,
                max = 2160,
                unit = "px",
                description = "Scene canvas height. Example: 2160 px. Very tall canvases use more GPU memory."
            )
            SettingsNumberInput(
                label = "Output width",
                value = config.outputResWidth,
                onValueChange = { viewModel.setVideoConfig(config.copy(outputResWidth = it)) },
                min = 320,
                max = 3840,
                unit = "px",
                description = "Final encoded width (maximum 3840 px). Encoder and ingest service must support the selected size and FPS."
            )
            SettingsNumberInput(
                label = "Output height",
                value = config.outputResHeight,
                onValueChange = { viewModel.setVideoConfig(config.copy(outputResHeight = it)) },
                min = 240,
                max = 2160,
                unit = "px",
                description = "Final encoded height (maximum 2160 px). Encoder and ingest service must support the selected size and FPS."
            )
            SettingsNumberInput(
                label = "Integer FPS",
                value = config.frameRate,
                onValueChange = { viewModel.setVideoConfig(config.copy(fpsType = FpsType.INTEGER, fpsInt = it)) },
                min = 1,
                max = 120,
                unit = "FPS",
                description = "Integer compositor/encoder rate up to 120 FPS. The exact Astra codec mode and the ingest service must support it."
            )
        }

    }
}
