package com.stream4k60.app.ui.main.components

import com.stream4k60.app.ui.common.ClosableTitle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stream4k60.app.ui.main.SourceItem
import com.stream4k60.app.engine.AudioFilterChain
import com.stream4k60.app.engine.VideoFilterChain
import com.stream4k60.app.ui.util.showImeOnFocus

private val filterSourceTypes = VideoFilterChain.VIDEO_SOURCE_TYPES + AudioFilterChain.AUDIO_SOURCE_TYPES

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SourcePanel(
    sources: List<SourceItem>,
    sourceErrors: Map<String, String> = emptyMap(),
    onToggleVisibility: (String) -> Unit,
    onToggleLock: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onProperties: () -> Unit = {},
    onFilters: (String) -> Unit = {},
    onTransform: (String) -> Unit = {},
    onOpenProperties: (String) -> Unit = {},
    onRequestCapturePermission: (String) -> Unit = {},
    onRenameSource: (String, String) -> Unit = { _, _ -> },
    onDuplicateSource: (String) -> Unit = {},
    onDeleteSource: (String) -> Unit = {},
    onResetTransform: (String) -> Unit = {},
    onPasteTransform: (String, String) -> Unit = { _, _ -> },
    selectedSourceId: String? = null,
    onSelectSource: (String) -> Unit = {},
    onMoveSource: (String, Int) -> Unit = { _, _ -> },
    onMoveSourceToIndex: (String, Int) -> Unit = { _, _ -> },
    groupChildren: Map<String, List<SourceItem>> = emptyMap(),
    onMoveIntoGroup: (String, String) -> Unit = { _, _ -> },
    onMoveOutOfGroup: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    showHeader: Boolean = true
) {
    val listState = rememberLazyListState()
    var displayOrder by remember { mutableStateOf(sources.asReversed()) }
    var draggingSourceId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var contextMenuSourceId by remember { mutableStateOf<String?>(null) }
    var copiedTransform by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<SourceItem?>(null) }
    var errorDetail by remember { mutableStateOf<Pair<String, String>?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var collapsedGroups by remember { mutableStateOf(emptySet<String>()) }
    val groups = sources.filter { it.type.equals("GROUP", true) }
    // Rows: (source, parent group id or null). Group contents follow their group, front-most first;
    // they are hidden while a top-level row is being dragged so drag slots match top-level order.
    val rows = if (draggingSourceId != null) displayOrder.map { it to null } else displayOrder.flatMap { source ->
        listOf<Pair<SourceItem, String?>>(source to null) +
            if (source.type.equals("GROUP", true) && source.id !in collapsedGroups) groupChildren[source.id].orEmpty().asReversed().map { it to source.id } else emptyList()
    }
    LaunchedEffect(sources) {
        if (draggingSourceId == null) displayOrder = sources.asReversed()
    }
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        if (showHeader) {
            Text("Sources", fontSize = 12.sp, modifier = Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 4.dp))
            HorizontalDivider(thickness = 1.dp)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            // The top row is the front-most layer, matching OBS and the native compositor's z-order.
            items(rows, key = { it.first.id }) { (source, parentGroupId) ->
                val siblings = parentGroupId?.let { groupChildren[it].orEmpty() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .graphicsLayer {
                            if (draggingSourceId == source.id) {
                                translationY = dragOffset
                                alpha = .88f
                            }
                        }
                        .background(if (source.id == selectedSourceId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                        .pointerInput(source.id) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                        contextMenuSourceId = source.id
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed)) {
                                contextMenuSourceId = source.id
                                true
                            } else false
                        }
                        .combinedClickable(
                            onClick = { onSelectSource(source.id) },
                            onLongClick = { contextMenuSourceId = source.id }
                        )
                        .padding(start = if (parentGroupId != null) 20.dp else 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (source.type.equals("GROUP", true)) {
                        val collapsed = source.id in collapsedGroups
                        IconButton(
                            onClick = { collapsedGroups = if (collapsed) collapsedGroups - source.id else collapsedGroups + source.id },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(if (collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore, if (collapsed) "Expand group" else "Collapse group", modifier = Modifier.size(16.dp))
                        }
                    }
                    IconButton(onClick = { onToggleVisibility(source.id) }, modifier = Modifier.size(24.dp)) {
                        Icon(if (source.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, "Visibility", modifier = Modifier.size(15.dp))
                    }
                    IconButton(onClick = { onToggleLock(source.id) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Lock, "Lock", modifier = Modifier.size(14.dp), tint = if (source.isLocked) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(alpha = .45f))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(source.name, fontSize = 13.sp, maxLines = 1)
                        val sourceError = sourceErrors[source.id]
                        if (sourceError != null) {
                            // Tap for the whole message; Properties shows it too.
                            Text("⚠ $sourceError", fontSize = 9.sp, color = MaterialTheme.colorScheme.error, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { errorDetail = source.name to sourceError })
                        } else Text(source.type, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Box {
                        IconButton(onClick = { contextMenuSourceId = source.id }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.MoreVert, "Source actions", modifier = Modifier.size(16.dp))
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = contextMenuSourceId == source.id,
                            onDismissRequest = { contextMenuSourceId = null }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(if (source.isVisible) "Hide" else "Show") },
                                onClick = { contextMenuSourceId = null; onToggleVisibility(source.id) }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(if (source.isLocked) "Unlock" else "Lock") },
                                onClick = { contextMenuSourceId = null; onToggleLock(source.id) }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Transform…") },
                                enabled = !source.isLocked,
                                onClick = { contextMenuSourceId = null; onTransform(source.id) }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Properties…") },
                                onClick = { contextMenuSourceId = null; onOpenProperties(source.id) }
                            )
                            if(source.type.equals("SCREEN_CAPTURE",true)||source.type.equals("PLAYBACK_AUDIO",true)) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Request Android capture permission…") },
                                    onClick = { contextMenuSourceId = null; onRequestCapturePermission(source.id) }
                                )
                            }
                            if (source.type.uppercase() in filterSourceTypes) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Filters…") },
                                    onClick = { contextMenuSourceId = null; onFilters(source.id) }
                                )
                            }
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Rename…") },
                                onClick = { contextMenuSourceId = null; renameTarget = source; renameValue = source.name }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Duplicate") },
                                onClick = { contextMenuSourceId = null; onDuplicateSource(source.id) }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Copy Transform") },
                                onClick = { contextMenuSourceId = null; copiedTransform = source.transformJson }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Paste Transform") },
                                enabled = copiedTransform != null && !source.isLocked,
                                onClick = { contextMenuSourceId = null; copiedTransform?.let { onPasteTransform(source.id, it) } }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Reset Transform") },
                                enabled = !source.isLocked,
                                onClick = { contextMenuSourceId = null; onResetTransform(source.id) }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Move to Top") },
                                onClick = { contextMenuSourceId = null; onMoveSourceToIndex(source.id, 0) }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Move to Bottom") },
                                onClick = { contextMenuSourceId = null; onMoveSourceToIndex(source.id, (siblings?.size ?: displayOrder.size) - 1) }
                            )
                            if (parentGroupId != null) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Move out of group") },
                                    onClick = { contextMenuSourceId = null; onMoveOutOfGroup(source.id) }
                                )
                            } else if (!source.type.equals("GROUP", true)) {
                                // OBS groups cannot contain groups.
                                groups.forEach { group ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("Move into “${group.name}”") },
                                        onClick = { contextMenuSourceId = null; onMoveIntoGroup(source.id, group.id) }
                                    )
                                }
                            }
                            androidx.compose.material3.HorizontalDivider()
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                                onClick = { contextMenuSourceId = null; onDeleteSource(source.id) }
                            )
                        }
                    }
                    if (parentGroupId == null) Box(
                        modifier = Modifier
                            .width(28.dp)
                            .fillMaxHeight()
                            .pointerInput(source.id) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingSourceId = source.id
                                        dragOffset = 0f
                                    },
                                    onDragEnd = {
                                        val finalIndex = displayOrder.indexOfFirst { it.id == source.id }
                                        if (finalIndex >= 0) onMoveSourceToIndex(source.id, finalIndex)
                                        draggingSourceId = null
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingSourceId = null
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        if (draggingSourceId != source.id) return@detectDragGestures
                                        change.consume()
                                        dragOffset += amount.y
                                        val dragged = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == source.id } ?: return@detectDragGestures
                                        val centerY = dragged.offset + dragged.size / 2f + dragOffset
                                        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                            item.key != source.id && centerY >= item.offset && centerY < item.offset + item.size
                                        } ?: return@detectDragGestures
                                        val from = displayOrder.indexOfFirst { it.id == source.id }
                                        val to = target.index
                                        if (from >= 0 && to in displayOrder.indices && from != to) {
                                            val reordered = displayOrder.toMutableList()
                                            val moving = reordered.removeAt(from)
                                            reordered.add(to, moving)
                                            displayOrder = reordered
                                            // Keep the row attached to the pointer after the list slot changes.
                                            dragOffset = centerY - target.offset - dragged.size / 2f
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.DragHandle, "Drag to reorder", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        HorizontalDivider(thickness = 1.dp)
        Row(modifier = Modifier.height(28.dp)) {
            IconButton(onClick = onAdd, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Add, "Add", modifier = Modifier.size(16.dp)) }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Delete, "Remove", modifier = Modifier.size(15.dp)) }
            IconButton(onClick = onProperties, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Settings, "Properties", modifier = Modifier.size(15.dp)) }
            IconButton(onClick = { selectedSourceId?.let(onTransform) }, enabled = selectedSourceId != null, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.OpenWith, "Transform", modifier = Modifier.size(15.dp)) }
            IconButton(onClick = { selectedSourceId?.let { onMoveSource(it, -1) } }, enabled = selectedSourceId != null, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.ArrowUpward, "Move source forward", modifier = Modifier.size(15.dp))
            }
            IconButton(onClick = { selectedSourceId?.let { onMoveSource(it, 1) } }, enabled = selectedSourceId != null, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.ArrowDownward, "Move source backward", modifier = Modifier.size(15.dp))
            }
        }
    }
    errorDetail?.let { (name, message) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { errorDetail = null },
            title = { ClosableTitle("$name: problem", { errorDetail = null }) },
            text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(message) } },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { errorDetail = null }) { Text("OK") } }
        )
    }
    renameTarget?.let { source ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { ClosableTitle("Rename source", { renameTarget = null }) },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it.take(80) },
                    singleLine = true,
                    label = { Text("Source name") },
                    modifier = Modifier.showImeOnFocus()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = renameValue.trim()
                    if (value.isNotEmpty()) onRenameSource(source.id, value)
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }
}
