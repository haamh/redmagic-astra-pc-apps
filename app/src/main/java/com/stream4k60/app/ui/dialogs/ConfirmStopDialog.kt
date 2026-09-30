package com.stream4k60.app.ui.dialogs

import com.stream4k60.app.ui.common.ClosableTitle

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun ConfirmStopDialog(
    actionType: String = "streaming/recording",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(16.dp)) {
                ClosableTitle("Confirm Stop", onDismiss)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Are you sure you want to stop $actionType?")
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("No") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onConfirm) { Text("Yes") }
                }
            }
        }
    }
}
