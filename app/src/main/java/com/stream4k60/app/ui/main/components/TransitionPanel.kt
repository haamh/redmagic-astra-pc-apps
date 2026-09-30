package com.stream4k60.app.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TransitionPanel(
    isStudioMode: Boolean,
    selected: String,
    onSelect: (String) -> Unit,
    onTransition: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("Cut", "Fast Fade", "Fade", "Slow Fade")
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Scene Transitions", fontSize = 12.sp)
        }
        HorizontalDivider(thickness = 1.dp)
        Column(modifier = Modifier.padding(6.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selected) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { onSelect(value); expanded = false }) }
                }
            }
            Text(if (selected == "Cut") "Instant" else when(selected) { "Fast Fade" -> "180 ms"; "Slow Fade" -> "600 ms"; else -> "300 ms" }, fontSize = 11.sp)
            if (isStudioMode) Button(onClick = onTransition, modifier = Modifier.fillMaxWidth()) { Text("Transition") }
        }
    }
}
