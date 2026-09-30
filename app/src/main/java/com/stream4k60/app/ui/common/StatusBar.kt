package com.stream4k60.app.ui.common

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
fun StatusBar(
    isLive: Boolean = false,
    isRecording: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(Color(0xFF2A2A2A))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isLive) {
                Text("LIVE: 00:12:34", color = Color.Green, fontSize = 11.sp)
                Text(" | ", color = Color.Gray, fontSize = 11.sp)
            }
            if (isRecording) {
                Text("REC: 00:05:21", color = Color.Red, fontSize = 11.sp)
                Text(" | ", color = Color.Gray, fontSize = 11.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("CPU: 3.2%", color = Color.LightGray, fontSize = 11.sp)
            Text("60.00 FPS", color = Color.LightGray, fontSize = 11.sp)
            Text("6000 kb/s", color = Color.LightGray, fontSize = 11.sp)
            Text("0 dropped (0.0%)", color = Color.LightGray, fontSize = 11.sp)
        }
    }
}
