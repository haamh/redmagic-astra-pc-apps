package com.stream4k60.app.ui.profiles

import com.stream4k60.app.ui.common.ClosableTitle

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ProfileManagerScreen(onClose: () -> Unit = {}, vm: ProfileManagerViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val profiles by vm.profiles.collectAsState()
    val report by vm.importReport.collectAsState()
    val openJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.import(it) { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() } }
    }
    val openArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.import(it) { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() } }
    }
    val openFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.import(it) { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() } }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ClosableTitle("Profiles & OBS imports", onClose, style = MaterialTheme.typography.headlineSmall)
        Text("Import OBS profile settings, scene collections, source layouts, filters and bundled media when present. Select Use to apply a profile; switch imported collections from the Studio Collection menu.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openJson.launch(arrayOf("application/json", "text/plain")) }) { Text("OBS profile / scene JSON") }
            Button(onClick = { openArchive.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("OBS ZIP bundle") }
            OutlinedButton(onClick = { openFolder.launch(null) }) { Text("OBS profile folder") }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(profiles, key = { it.id }) { p ->
                Card {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name)
                            Text(if (p.isActive) "Active" else "Imported profile", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { vm.activate(p.id) }) { Text(if (p.isActive) "Active" else "Use") }
                    }
                }
            }
        }
    }
    report?.let { imported ->
        AlertDialog(
            onDismissRequest = vm::dismissImportReport,
            title = { ClosableTitle("OBS import report", vm::dismissImportReport) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text("Profile: ${imported.profileName}")
                    Spacer(Modifier.height(4.dp))
                    Text("Imported ${imported.collectionNames.size} scene collections, ${imported.sceneCount} scenes and ${imported.sourceCount} sources.")
                    if (imported.collectionNames.isNotEmpty()) Text("Collections: ${imported.collectionNames.joinToString()}")
                    if (imported.warnings.isEmpty()) Text("No compatibility warnings were reported.")
                    else {
                        Spacer(Modifier.height(8.dp))
                        Text("Review these items after import:", style = MaterialTheme.typography.titleSmall)
                        imported.warnings.forEach { warning -> Text("• $warning", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = { Button(onClick = vm::dismissImportReport) { Text("Done") } }
        )
    }
}
