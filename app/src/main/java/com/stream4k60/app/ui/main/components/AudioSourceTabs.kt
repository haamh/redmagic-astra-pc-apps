package com.stream4k60.app.ui.main.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stream4k60.app.ui.main.SourceItem

/**
 * Header of the audio-sources window: one tab per audio source (Desktop Audio, Mic/Aux, inputs, media…) and a
 * Properties / Filters switch, so every audio source is managed from one place.
 */
@Composable
fun AudioSourceTabs(sources: List<SourceItem>, selectedId: String, mode: String, onSelect: (String) -> Unit, onMode: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = mode == "properties", onClick = { onMode("properties") }, label = { Text("Properties") })
            FilterChip(selected = mode == "filters", onClick = { onMode("filters") }, label = { Text("Filters") })
        }
        val index = sources.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        ScrollableTabRow(selectedTabIndex = index, edgePadding = 0.dp) {
            sources.forEach { src -> Tab(selected = src.id == selectedId, onClick = { onSelect(src.id) }, text = { Text(src.name, maxLines = 1) }) }
        }
        Spacer(Modifier.height(8.dp))
    }
}
