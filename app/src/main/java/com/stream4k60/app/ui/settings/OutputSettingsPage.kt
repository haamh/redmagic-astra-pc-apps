package com.stream4k60.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.stream4k60.app.data.model.OutputCodec
import com.stream4k60.app.engine.EncoderCapabilities
import com.stream4k60.app.ui.settings.components.SettingsDropdown
import com.stream4k60.app.ui.settings.components.SettingsSection
import com.stream4k60.app.ui.settings.components.SettingsSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AstraCodecStatus(
    val hardwareEncoders: List<String>,
    val h264Supported: Boolean,
    val hevcSupported: Boolean,
    val selectedBitrateRangeKbps: IntRange?,
    val selectedAchievableMaxFps: Int?
)

@Composable
fun OutputSettingsPage(viewModel: SettingsViewModel) {
    val config by viewModel.videoConfig.collectAsState()
    val selectedMime = if (config.outputCodec == OutputCodec.HEVC) "video/hevc" else "video/avc"
    val codecStatus by produceState<AstraCodecStatus?>(null, config.outputResWidth, config.outputResHeight, config.frameRate, config.outputCodec) {
        value = withContext(Dispatchers.IO) {
            AstraCodecStatus(
                hardwareEncoders = EncoderCapabilities.getAvailableVideoEncoders()
                    .filter { it.isHardware }
                    .map { "${it.displayName} (${it.maxWidth}×${it.maxHeight})" }
                    .distinct(),
                h264Supported = EncoderCapabilities.supportsVideo("video/avc", config.outputResWidth, config.outputResHeight, config.frameRate),
                hevcSupported = EncoderCapabilities.supportsVideo("video/hevc", config.outputResWidth, config.outputResHeight, config.frameRate),
                selectedBitrateRangeKbps = EncoderCapabilities.supportedBitrateRangeKbps(selectedMime, config.outputResWidth, config.outputResHeight, config.frameRate),
                selectedAchievableMaxFps = EncoderCapabilities.achievableMaxFrameRate(selectedMime, config.outputResWidth, config.outputResHeight, config.frameRate)
            )
        }
    }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SettingsSection("Streaming") {
            SettingsSlider(
                label = "Video bitrate",
                value = config.videoBitrateKbps.toFloat(),
                onValueChange = { viewModel.setVideoBitrate(it.toInt()) },
                valueRange = 1_000f..100_000f,
                steps = 98,
                unit = " Kbps",
                description = "Target stream video rate, configurable up to 100,000 Kbps. At 4K120, begin near 80,000–100,000 Kbps only if Android, your upload link, and the ingest service support it."
            )
            SettingsDropdown(
                label = "Video encoder",
                options = listOf("H.264", "H.265 / HEVC"),
                selected = if (config.outputCodec == OutputCodec.HEVC) "H.265 / HEVC" else "H.264",
                description = "Selects the hardware video codec. The device and ingest service must both support HEVC; H.264 has the broadest compatibility.",
                onSelect = { viewModel.setEncoder(it) }
            )
        }

        SettingsSection("Detected Android hardware codecs") {
            val dimensions = "${config.outputResWidth} × ${config.outputResHeight} @ ${config.frameRate} FPS"
            Text(
                "Current output ($dimensions): H.264 ${if (codecStatus?.h264Supported == true) "supported" else if (codecStatus == null) "checking…" else "not exposed"}; HEVC ${if (codecStatus?.hevcSupported == true) "supported" else if (codecStatus == null) "checking…" else "not exposed"}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Hardware encoders exposed by Android: ${codecStatus?.hardwareEncoders?.takeIf { it.isNotEmpty() }?.joinToString() ?: if (codecStatus == null) "checking…" else "none reported"}. This checks the installed system's MediaCodec capability ranges; a successful query does not prove sustained encoding or streaming compatibility.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Selected codec bitrate range reported by Android: ${codecStatus?.selectedBitrateRangeKbps?.let { "${it.first}–${it.last} Kbps" } ?: if (codecStatus == null) "checking…" else "unavailable"}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Android encoder real-time estimate at this size: ${codecStatus?.selectedAchievableMaxFps?.let { "up to $it FPS" } ?: if (codecStatus == null) "checking…" else "not published"}. This estimate does not include the compositor, multiple live sources, network or thermal load.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
