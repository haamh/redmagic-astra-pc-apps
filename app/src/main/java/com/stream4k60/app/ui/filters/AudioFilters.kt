package com.stream4k60.app.ui.filters

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun AudioFilters() {
    Column {
        Text("Noise Suppression")
        Text("Noise Gate")
        Text("Compressor")
        Text("Limiter")
        Text("Expander")
        Text("Gain")
        Text("Invert Polarity")
        Text("3-Band Equalizer")
        Text("VST 2.x Plugin")
    }
}
