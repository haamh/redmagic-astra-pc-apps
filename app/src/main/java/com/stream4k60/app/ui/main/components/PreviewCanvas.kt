package com.stream4k60.app.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PreviewCanvas(isStudioMode: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color(0xFF1E1E1E))) {
        if (isStudioMode) {
            Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color.Black)) {
                    Text("PREVIEW", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopCenter).padding(4.dp))
                }
                Spacer(modifier = Modifier.width(16.dp))
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color.Black)) {
                    Text("PROGRAM", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopCenter).padding(4.dp))
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(32.dp).background(Color.Black)) {
                // Main canvas content
            }
        }
    }
}
