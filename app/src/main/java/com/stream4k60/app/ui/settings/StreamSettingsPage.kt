package com.stream4k60.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stream4k60.app.data.model.StreamService
import com.stream4k60.app.data.model.StreamSettings
import com.stream4k60.app.ui.settings.components.SettingsButton
import com.stream4k60.app.ui.settings.components.SettingsDropdown
import com.stream4k60.app.ui.settings.components.SettingsSection
import com.stream4k60.app.ui.settings.components.SettingsTextField

/** Services offered like OBS's Service list; servers are the platforms' published ingest URLs. */
internal object StreamServices {
    data class Server(val label: String, val url: String)
    data class Service(val id: StreamService, val label: String, val servers: List<Server>, val keyHint: String)

    val all = listOf(
        Service(StreamService.YOUTUBE, "YouTube - RTMPS", listOf(
            Server("Primary YouTube ingest server", "rtmps://a.rtmps.youtube.com:443/live2"),
            Server("Backup YouTube ingest server", "rtmps://b.rtmps.youtube.com:443/live2?backup=1"),
            Server("Primary (RTMP, unencrypted)", "rtmp://a.rtmp.youtube.com/live2")
        ), "YouTube Studio → Go live → Stream → Stream key. YouTube accepts up to 60 FPS."),
        Service(StreamService.TWITCH, "Twitch", listOf(
            Server("Auto (recommended)", "rtmp://live.twitch.tv/app")
        ), "Twitch → Creator Dashboard → Settings → Stream → Primary Stream key."),
        Service(StreamService.FACEBOOK, "Facebook Live", listOf(
            Server("Default", "rtmps://live-api-s.facebook.com:443/rtmp/")
        ), "Facebook → Live Producer → Streaming software → Stream key."),
        Service(StreamService.KICK, "Kick", emptyList(), "Kick → Creator Dashboard → Settings → Stream URL and Stream Key. Paste both."),
        Service(StreamService.CUSTOM, "Custom...", emptyList(), "Any RTMP or RTMPS server, e.g. rtmps://server.example/app.")
    )

    fun of(id: StreamService) = all.firstOrNull { it.id == id } ?: all.last()
}

@Composable
fun StreamSettingsPage(viewModel: SettingsViewModel) {
    val saved by viewModel.streamSettings.collectAsState()
    var service by remember { mutableStateOf(saved.service) }
    var server by remember { mutableStateOf(saved.server) }
    var key by remember { mutableStateOf(saved.streamKey) }
    // Follow the stored profile (it loads asynchronously, and switching profiles changes it).
    LaunchedEffect(saved) { service = saved.service; server = saved.server; key = saved.streamKey }
    val info = StreamServices.of(service)
    val dirty = StreamSettings(service, server.trim(), key.trim()) != saved
    val serverValid = server.trim().let { it.startsWith("rtmp://", true) || it.startsWith("rtmps://", true) }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SettingsSection("Stream") {
            SettingsDropdown(
                label = "Service",
                options = StreamServices.all.map { it.label },
                selected = info.label,
                onSelect = { label ->
                    val next = StreamServices.all.first { it.label == label }
                    service = next.id
                    server = next.servers.firstOrNull()?.url ?: if (next.id == saved.service) saved.server else ""
                }
            )
            if (info.servers.isNotEmpty()) {
                SettingsDropdown(
                    label = "Server",
                    options = info.servers.map { it.label },
                    selected = info.servers.firstOrNull { it.url == server }?.label ?: info.servers.first().label,
                    onSelect = { label -> server = info.servers.first { it.label == label }.url }
                )
            } else {
                SettingsTextField(
                    label = "Server",
                    value = server,
                    onValueChange = { server = it },
                    placeholder = "rtmps://server.example/app",
                    description = if (server.isNotBlank() && !serverValid) "Must start with rtmp:// or rtmps://" else null
                )
            }
            SettingsTextField(
                label = "Stream Key",
                value = key,
                onValueChange = { key = it },
                isPassword = true,
                description = info.keyHint
            )
            SettingsButton(
                label = if (dirty) "Apply" else "Saved",
                onClick = { viewModel.saveStreamSettings(StreamSettings(service, server.trim(), key.trim())) },
                enabled = dirty && serverValid && key.isNotBlank()
            )
            Text(
                "Start Streaming uses this destination with the resolution, FPS, bitrate and encoder from Output and Video. " +
                    "The key is stored in this app's private profile data on the tablet, as OBS stores it in its profile.",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
