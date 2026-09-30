package com.stream4k60.app.ui.main.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stream4k60.app.R
import com.stream4k60.app.data.local.entity.SceneCollectionEntity
import com.stream4k60.app.ui.main.SceneItem
import com.stream4k60.app.ui.main.SourceItem
import com.stream4k60.app.engine.AstraDeviceMonitor

private enum class StudioMenu { SCENES, SOURCES, TOOLS }

@Composable
fun TopBar(
    scenes: List<SceneItem>,
    sceneCollections: List<SceneCollectionEntity>,
    activeCollectionId: String?,
    activeSceneId: String?,
    sources: List<SourceItem>,
    isStreaming: Boolean = false,
    isRecording: Boolean = false,
    isStudioMode: Boolean = false,
    thermalStatus: Int = 0,
    onSelectScene: (String) -> Unit,
    onSelectCollection: (String) -> Unit,
    onAddScene: () -> Unit,
    onAddSource: () -> Unit,
    onToggleSourceVisibility: (String) -> Unit,
    onToggleStudioMode: () -> Unit,
    onReplayBuffer: () -> Unit,
    onSearch: () -> Unit,
    onYouTube: () -> Unit,
    onCustomStream: () -> Unit,
    onProfiles: () -> Unit,
    onSettings: () -> Unit
) {
    var openMenu by remember { mutableStateOf<StudioMenu?>(null) }
    var collectionMenuExpanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(38.dp).background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.stream4k_logo),
            contentDescription = "Stream4k60 logo",
            modifier = Modifier.padding(start = 8.dp, end = 4.dp).size(26.dp)
        )
        TextButton(onClick = onProfiles) { Text("Profiles", fontSize = 12.sp) }
        Box {
            TextButton(onClick = { collectionMenuExpanded = true }) {
                val collectionName = sceneCollections.firstOrNull { it.id == activeCollectionId }?.name ?: "Default"
                Text("Collection: $collectionName", fontSize = 12.sp, maxLines = 1)
            }
            DropdownMenu(expanded = collectionMenuExpanded, onDismissRequest = { collectionMenuExpanded = false }) {
                sceneCollections.forEach { collection ->
                    DropdownMenuItem(
                        text = { Text(collection.name, maxLines = 1) },
                        leadingIcon = { if (collection.id == activeCollectionId) Icon(Icons.Default.Check, contentDescription = "Active collection") },
                        onClick = { collectionMenuExpanded = false; onSelectCollection(collection.id) }
                    )
                }
            }
        }
        Box {
            TextButton(onClick = { openMenu = if (openMenu == StudioMenu.SCENES) null else StudioMenu.SCENES }) {
                Text("Scenes", fontSize = 12.sp)
            }
            DropdownMenu(
                expanded = openMenu == StudioMenu.SCENES,
                onDismissRequest = { openMenu = null },
                modifier = Modifier.heightIn(max = 520.dp)
            ) {
                scenes.forEach { scene ->
                    DropdownMenuItem(
                        text = { Text(scene.name, maxLines = 1) },
                        leadingIcon = {
                            if (scene.id == activeSceneId) Icon(Icons.Default.Check, contentDescription = "Active scene")
                        },
                        onClick = { openMenu = null; onSelectScene(scene.id) }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Add Scene…") },
                    onClick = { openMenu = null; onAddScene() }
                )
            }
        }
        Box {
            TextButton(onClick = { openMenu = if (openMenu == StudioMenu.SOURCES) null else StudioMenu.SOURCES }) {
                Text("Sources", fontSize = 12.sp)
            }
            DropdownMenu(
                expanded = openMenu == StudioMenu.SOURCES,
                onDismissRequest = { openMenu = null },
                modifier = Modifier.heightIn(max = 520.dp)
            ) {
                DropdownMenuItem(
                    text = { Text("Add Source…") },
                    onClick = { openMenu = null; onAddSource() }
                )
                if (sources.isNotEmpty()) HorizontalDivider()
                sources.asReversed().forEach { source ->
                    DropdownMenuItem(
                        text = { Text("${if (source.isVisible) "Hide" else "Show"} · ${source.name}", maxLines = 1) },
                        onClick = { openMenu = null; onToggleSourceVisibility(source.id) }
                    )
                }
            }
        }
        Box {
            TextButton(onClick = { openMenu = if (openMenu == StudioMenu.TOOLS) null else StudioMenu.TOOLS }) {
                Text("Tools", fontSize = 12.sp)
            }
            DropdownMenu(
                expanded = openMenu == StudioMenu.TOOLS,
                onDismissRequest = { openMenu = null }
            ) {
                DropdownMenuItem(
                    text = { Text("Search actions and features…") },
                    onClick = { openMenu = null; onSearch() }
                )
                DropdownMenuItem(
                    text = { Text(if (isStudioMode) "Leave Studio Mode" else "Enter Studio Mode") },
                    onClick = { openMenu = null; onToggleStudioMode() }
                )
                DropdownMenuItem(
                    text = { Text("Replay Buffer") },
                    onClick = { openMenu = null; onReplayBuffer() }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Settings…") },
                    onClick = { openMenu = null; onSettings() }
                )
            }
        }
        TextButton(onClick = onSettings) { Text("Settings", fontSize = 12.sp) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search everything") }
        TextButton(onClick = onYouTube) { Text("YouTube", fontSize = 12.sp) }
        TextButton(onClick = onCustomStream) { Text("RTMP", fontSize = 12.sp) }
        Text(
            "THERMAL ${AstraDeviceMonitor.label(thermalStatus)}",
            color = when {
                thermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE -> MaterialTheme.colorScheme.error
                thermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE -> Color(0xFFFFB74D)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        if (isStreaming) Text("● LIVE", color = Color(0xFF33CC66), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
        if (isRecording) Text("● REC", color = Color(0xFFFF4444), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
    }
}
