package com.stream4k60.app.ui.filters

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun VideoFilters() {
    Column {
        Text("Color Correction properties")
        Text("Chroma Key properties")
        Text("Color Key properties")
        Text("LUT properties")
        Text("Crop/Pad properties")
        Text("Sharpen properties")
        Text("Scroll properties")
        Text("Image Mask/Blend properties")
        Text("Render Delay properties")
    }
}
