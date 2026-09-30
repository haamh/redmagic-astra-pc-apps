package com.stream4k60.app.ui.main.components

import com.stream4k60.app.ui.common.ClosableTitle
import com.stream4k60.app.ui.common.CloseButton

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun AddSourceDialog(
    onDismiss: () -> Unit = {},
    onSourceSelected: (String) -> Unit = {}
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                ClosableTitle("Add Source", onDismiss)
                Spacer(modifier = Modifier.height(16.dp))
                
                Column {
                    SourceTypeItem("Camera", Icons.Default.Videocam, onSourceSelected)
                    SourceTypeItem("Screen Capture", Icons.Default.DesktopWindows, onSourceSelected)
                    SourceTypeItem("Image", Icons.Default.Image, onSourceSelected)
                    SourceTypeItem("Text", Icons.Default.TextFields, onSourceSelected)
                    SourceTypeItem("Audio Input Capture", Icons.Default.Mic, onSourceSelected)
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceTypeItem(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: (String) -> Unit) {
    TextButton(
        onClick = { onClick(name) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = name)
            Spacer(Modifier.width(16.dp))
            Text(name)
        }
    }
}

@Preview
@Composable
fun AddSourceDialogPreview() {
    MaterialTheme {
        AddSourceDialog()
    }
}
