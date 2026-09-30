package com.stream4k60.app.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stream4k60.app.ui.main.SceneItem

@Composable
fun ScenePanel(
    scenes: List<SceneItem>,
    activeSceneId: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true
) {
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        if (showHeader) {
            Text("Scenes", fontSize = 12.sp, modifier = Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 4.dp), maxLines = 1)
            HorizontalDivider(thickness = 1.dp)
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(scenes, key = { it.id }) { scene ->
                val active = scene.id == activeSceneId
                Row(
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                        .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onSelect(scene.id) }.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { Text(scene.name, fontSize = 13.sp, maxLines = 1) }
            }
        }
        HorizontalDivider(thickness = 1.dp)
        Row(modifier = Modifier.height(28.dp)) {
            IconButton(onClick = onAdd, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Add, "Add", modifier = Modifier.size(16.dp)) }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Remove, "Remove", modifier = Modifier.size(16.dp)) }
        }
    }
}
