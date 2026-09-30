package com.stream4k60.app.ui.main.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.stream4k60.app.data.model.OutputCodec
import com.stream4k60.app.data.model.ImportedRtmpEndpoint
import com.stream4k60.app.data.model.StreamConfig
import com.stream4k60.app.data.model.StreamProtocol
import com.stream4k60.app.data.model.StreamService
import com.stream4k60.app.data.model.VideoConfig
import com.stream4k60.app.ui.util.showImeOnFocus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomRtmpDialog(
    video: VideoConfig,
    importedEndpoint: ImportedRtmpEndpoint?,
    onSelect: (StreamConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var serverUrl by remember(importedEndpoint) { mutableStateOf(importedEndpoint?.serverUrl.orEmpty()) }
    var streamKey by remember(importedEndpoint) { mutableStateOf(importedEndpoint?.streamKey.orEmpty()) }
    var protocol by remember(importedEndpoint) { mutableStateOf(importedEndpoint?.protocol ?: StreamProtocol.RTMPS) }
    var protocolMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var revealKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom RTMP destination") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Configure an RTMP ingest server. The current Astra output profile will be used.", style = MaterialTheme.typography.bodySmall)
                if (importedEndpoint != null) Text("Prefilled from the active imported OBS profile. Confirm that the URL/key are current and that its service accepts the selected output.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Output: ${video.outputResWidth} × ${video.outputResHeight} at ${video.frameRate} FPS · ${if (video.outputCodec == OutputCodec.HEVC) "HEVC" else "H.264"} · ${video.videoBitrateKbps} Kbps", style = MaterialTheme.typography.bodySmall)
                ExposedDropdownMenuBox(expanded = protocolMenu, onExpandedChange = { protocolMenu = it }) {
                    OutlinedTextField(
                        value = if (protocol == StreamProtocol.RTMPS) "RTMPS (encrypted)" else "RTMP",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Transport") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolMenu) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = protocolMenu, onDismissRequest = { protocolMenu = false }) {
                        DropdownMenuItem(text = { Text("RTMPS (encrypted)") }, onClick = { protocol = StreamProtocol.RTMPS; protocolMenu = false })
                        DropdownMenuItem(text = { Text("RTMP") }, onClick = { protocol = StreamProtocol.RTMP; protocolMenu = false })
                    }
                }
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    modifier = Modifier.fillMaxWidth().showImeOnFocus(),
                    label = { Text("Ingest server URL") },
                    placeholder = { Text(if (protocol == StreamProtocol.RTMPS) "rtmps://server.example/app" else "rtmp://server.example/app") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = streamKey,
                    onValueChange = { streamKey = it },
                    modifier = Modifier.fillMaxWidth().showImeOnFocus(),
                    label = { Text("Stream key") },
                    visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { revealKey = !revealKey }) {
                            Icon(if (revealKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = if (revealKey) "Hide stream key" else "Show stream key")
                        }
                    },
                    singleLine = true
                )
                Text("120 FPS is sent only to this custom endpoint. The ingest server must accept the selected codec, bitrate, resolution and frame rate; MediaCodec support alone cannot guarantee server acceptance or sustained performance.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val url = serverUrl.trim()
                val expectedScheme = if (protocol == StreamProtocol.RTMPS) "rtmps://" else "rtmp://"
                if (!url.startsWith(expectedScheme, ignoreCase = true)) {
                    error = "Enter an ingest URL that starts with $expectedScheme."
                    return@Button
                }
                if (streamKey.isBlank()) {
                    error = "Enter the stream key for this ingest server."
                    return@Button
                }
                onSelect(
                    StreamConfig(
                        service = StreamService.CUSTOM,
                        protocol = protocol,
                        ingestionUrl = url,
                        streamName = streamKey.trim(),
                        outputCodec = video.outputCodec,
                        outputWidth = video.outputResWidth,
                        outputHeight = video.outputResHeight,
                        fps = video.frameRate,
                        bitrate = video.videoBitrateKbps * 1_000
                    )
                )
            }) { Text("Use destination") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
