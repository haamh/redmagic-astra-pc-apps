package com.stream4k60.app.ui.audio

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun AdvancedAudioDialog() {
    Column {
        Text("Advanced Audio Properties")
        Text("Table/List of all audio sources")
        Text("Columns: Name, Volume, Mono, Balance, Sync, Monitor Type, Tracks")
    }
}
