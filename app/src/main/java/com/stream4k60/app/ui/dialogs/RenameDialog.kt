package com.stream4k60.app.ui.dialogs

import com.stream4k60.app.ui.common.ClosableTitle

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.stream4k60.app.ui.util.showImeOnFocus

@Composable
fun RenameDialog(
    initialName: String,
    title: String = "Rename",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialName) }
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(16.dp)) {
                ClosableTitle(title, onDismiss)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().showImeOnFocus()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onConfirm(text) }) { Text("OK") }
                }
            }
        }
    }
}
