package com.stream4k60.app.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** Title row with an X in the corner; every dialog and page uses it so there is always an obvious way out. */
@Composable
fun ClosableTitle(text: String, onClose: () -> Unit, style: TextStyle = MaterialTheme.typography.titleLarge, color: Color = Color.Unspecified) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = style, color = color, modifier = Modifier.weight(1f))
        CloseButton(onClose)
    }
}

@Composable
fun CloseButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClose, modifier = modifier.size(36.dp)) { Icon(Icons.Default.Close, contentDescription = "Close") }
}
