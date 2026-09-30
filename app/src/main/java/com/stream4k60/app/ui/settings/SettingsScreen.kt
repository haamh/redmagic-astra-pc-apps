package com.stream4k60.app.ui.settings

import com.stream4k60.app.ui.common.CloseButton

import com.stream4k60.app.ui.common.ClosableTitle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stream4k60.app.ui.util.showImeOnFocus

private data class SettingSearchEntry(
    val name: String,
    val category: String,
    val description: String,
    val caution: String? = null,
    val example: String? = null,
    val keywords: String = ""
)

private val settingSearchEntries = listOf(
    SettingSearchEntry("Video bitrate", "Output", "Sets the profile's target video data rate up to 100,000 Kbps. Higher values use more upload bandwidth.", caution = "Stay within your service's ingest limit and verify the encoder supports the chosen rate.", example = "80,000 Kbps", keywords = "kbps rate bandwidth upload cbr 4k120"),
    SettingSearchEntry("Video encoder", "Output", "Selects H.264 or HEVC for the stream.", caution = "The device encoder and streaming service must both support HEVC; H.264 is broadly compatible.", example = "H.264", keywords = "codec h265 h265 avc hevc"),
    SettingSearchEntry("Base canvas resolution", "Video", "Sets the scene coordinate space and preview canvas size.", caution = "Larger canvases use more GPU memory even when output is scaled down.", example = "3840 × 2160", keywords = "base canvas scene resolution width height 4k uhd"),
    SettingSearchEntry("Canvas width", "Video", "Sets the scene coordinate width in pixels.", caution = "Very wide canvases can exceed GPU memory limits.", example = "3840 px", keywords = "base scene custom dimension horizontal"),
    SettingSearchEntry("Canvas height", "Video", "Sets the scene coordinate height in pixels.", caution = "Very tall canvases can exceed GPU memory limits.", example = "2160 px", keywords = "base scene custom dimension vertical"),
    SettingSearchEntry("Output resolution", "Video", "Sets the encoded stream size independently of the scene canvas.", caution = "The encoder and streaming service must support the selected dimensions.", example = "1920 × 1080", keywords = "scaled output stream recording width height"),
    SettingSearchEntry("Output width", "Video", "Sets the encoded output width in pixels.", caution = "The selected encoder and service must support this width.", example = "1920 px", keywords = "scaled stream recording custom dimension horizontal"),
    SettingSearchEntry("Output height", "Video", "Sets the encoded output height in pixels.", caution = "The selected encoder and service must support this height.", example = "1080 px", keywords = "scaled stream recording custom dimension vertical"),
    SettingSearchEntry("Frame rate", "Video", "Sets the requested compositor and encoder frame rate up to 120 FPS.", caution = "Astra MediaCodec must expose the exact mode and the ingest service must accept it. YouTube Live is limited to 60 FPS.", example = "120 FPS", keywords = "fps frames per second pacing common integer 4k120"),
    SettingSearchEntry("Integer FPS", "Video", "Sets a custom integer frame rate through 120 FPS for the compositor and encoder.", caution = "The exact hardware mode and ingest service must support the selected rate. YouTube Live is limited to 60 FPS.", example = "120 FPS", keywords = "fps custom frame rate integer 4k120")
)

private val settingsCategories = listOf("General", "Stream", "Output", "Audio", "Video", "Hotkeys", "Advanced", "Accessibility")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    initialCategory: String = "General",
    viewModel: SettingsViewModel = hiltViewModel()
) {
    var selectedCategory by remember(initialCategory) {
        mutableStateOf(initialCategory.takeIf { it in settingsCategories } ?: "General")
    }
    var search by remember { mutableStateOf("") }
    var showResetConfirmation by remember { mutableStateOf(false) }
    val matches = remember(search) {
        val query = search.trim().lowercase()
        val terms = query.split(Regex("\\s+")).filter(String::isNotBlank)
        if (terms.isEmpty()) emptyList() else settingSearchEntries.filter { entry ->
            val searchable = listOf(entry.name, entry.category, entry.description, entry.keywords)
                .joinToString(" ").lowercase()
            terms.all { term -> searchable.contains(term) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to studio")
                    }
                },
                actions = {
                    if (selectedCategory == "Video" || selectedCategory == "Output") {
                        TextButton(onClick = { showResetConfirmation = true }) { Text("Reset category") }
                    }
                    Button(onClick = onNavigateBack, modifier = Modifier.padding(end = 8.dp)) {
                        Text("Done")
                    }
                    CloseButton(onNavigateBack)
                }
            )
        }
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .weight(0.3f)
                    .fillMaxHeight()
                    .padding(10.dp)
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .showImeOnFocus()
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Escape && search.isNotEmpty()) {
                                search = ""
                                true
                            } else false
                        },
                    singleLine = true,
                    label = { Text("Search settings") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    supportingText = { Text("Search a setting, value, or category") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                )
                if (search.isBlank()) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(settingsCategories) { category ->
                            val selected = category == selectedCategory
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clickable { selectedCategory = category },
                                shape = MaterialTheme.shapes.small,
                                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    category,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (matches.isEmpty()) {
                            item {
                                Text(
                                    "No settings found. Try a category such as Video or a term such as bitrate.",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(matches) { entry ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        selectedCategory = entry.category
                                        search = ""
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(entry.name, style = MaterialTheme.typography.labelLarge)
                                    Text(entry.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Text(entry.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    entry.caution?.let {
                                        Text("Caution: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                    entry.example?.let {
                                        Text("Example: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            VerticalDivider(modifier = Modifier.fillMaxHeight())

            Column(
                modifier = Modifier
                    .weight(0.7f)
                    .fillMaxHeight()
                    .padding(horizontal = 16.dp)
            ) {
                Text(selectedCategory, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
                when (selectedCategory) {
                    "General" -> GeneralSettingsPage(viewModel)
                    "Stream" -> StreamSettingsPage(viewModel)
                    "Output" -> OutputSettingsPage(viewModel)
                    "Audio" -> AudioSettingsPage(viewModel)
                    "Video" -> VideoSettingsPage(viewModel)
                    "Hotkeys" -> HotkeysSettingsPage(viewModel)
                    "Advanced" -> AdvancedSettingsPage(viewModel)
                    "Accessibility" -> AccessibilitySettingsPage(viewModel)
                }
            }
        }
    }

    if (showResetConfirmation) {
        val resetDescription = if (selectedCategory == "Video") {
            "Restore canvas/output resolution and frame rate defaults. Your Output bitrate and encoder will be kept."
        } else {
            "Restore the default bitrate and H.264 encoder. Your Video resolution and frame rate will be kept."
        }
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { ClosableTitle("Reset $selectedCategory settings?", { showResetConfirmation = false }) },
            text = { Text(resetDescription) },
            confirmButton = {
                TextButton(onClick = {
                    if (selectedCategory == "Video") viewModel.resetVideoSettings()
                    else viewModel.resetOutputSettings()
                    showResetConfirmation = false
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) { Text("Cancel") }
            }
        )
    }
}
