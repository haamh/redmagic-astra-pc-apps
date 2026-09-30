package com.stream4k60.app.ui.settings

import com.stream4k60.app.ui.settings.components.SettingsButton

import com.stream4k60.app.ui.settings.components.SettingsNumberInput

import com.stream4k60.app.data.model.StreamService

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
            val service by viewModel.streamSettings.collectAsState()
            val recommended = recommendedBitrateKbps(service.service, config.outputResHeight, config.frameRate)
            SettingsNumberInput(
                label = "Video bitrate",
                value = config.videoBitrateKbps,
                onValueChange = { viewModel.setVideoBitrate(it) },
                min = 1_000, max = 100_000, step = 500, unit = "Kbps",
                description = "How much picture data is sent each second. Higher looks sharper but needs more upload speed: keep it under about 70% of your measured upload."
            )
            SettingsButton(
                "Use recommended: $recommended Kbps",
                { viewModel.setVideoBitrate(recommended) },
                description = "For ${StreamServices.of(service.service).label} at ${config.outputResHeight}p${config.frameRate}.",
                enabled = config.videoBitrateKbps != recommended
            )
            SettingsDropdown(
                label = "Video encoder",
                options = listOf("H.264", "H.265 / HEVC"),
                selected = if (config.outputCodec == OutputCodec.HEVC) "H.265 / HEVC" else "H.264",
                description = "Selects the hardware video codec. The device and ingest service must both support HEVC; H.264 has the broadest compatibility.",
                onSelect = { viewModel.setEncoder(it) }
            )
        }

        SettingsSection("Advanced: detected hardware codecs", initiallyExpanded = false) {
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

/** Typical ingest limits/recommendations: Twitch caps around 6–8 Mbps, YouTube scales with resolution and frame rate. */
internal fun recommendedBitrateKbps(service: StreamService, height: Int, fps: Int): Int {
    val high = fps > 30
    return when (service) {
        StreamService.TWITCH -> if (height <= 720 && !high) 4_500 else 6_000
        StreamService.FACEBOOK -> if (height <= 720) 4_500 else 6_000
        StreamService.KICK -> 8_000
        else -> when {
            height >= 2160 -> if (high) 35_000 else 25_000
            height >= 1440 -> if (high) 18_000 else 12_000
            height >= 1080 -> if (high) 9_000 else 6_000
            height >= 720 -> if (high) 6_000 else 4_000
            else -> 2_500
        }
    }
}
