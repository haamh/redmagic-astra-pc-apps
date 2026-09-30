package com.stream4k60.app.ui.profiles

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stream4k60.app.data.local.entity.ProfileEntity
import com.stream4k60.app.data.repository.ProfileRepository
import com.stream4k60.app.data.repository.SceneRepository
import com.stream4k60.app.profile.ImportSelection
import com.stream4k60.app.profile.ObsProjectImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ProfileManagerViewModel @Inject constructor(
    app: Application,
    private val profilesRepo: ProfileRepository,
    private val scenesRepo: SceneRepository
) : AndroidViewModel(app) {
    data class ImportReport(
        val profileName: String,
        val collectionNames: List<String>,
        val sceneCount: Int,
        val sourceCount: Int,
        val warnings: List<String>
    )

    private val _importReport = MutableStateFlow<ImportReport?>(null)
    val importReport = _importReport.asStateFlow()

    val profiles: StateFlow<List<ProfileEntity>> = profilesRepo.all()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun activate(id: String) {
        viewModelScope.launch { profilesRepo.activate(id) }
    }

    fun import(uri: Uri, done: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { ObsProjectImporter(getApplication()).importUri(uri) } }
                .onSuccess { result ->
                    profilesRepo.save(result.profile)
                    result.collections.forEach { imported ->
                        scenesRepo.saveCollection(imported.collection)
                        imported.scenes.forEach { scenesRepo.saveScene(it) }
                        scenesRepo.saveSources(imported.sources)
                        scenesRepo.saveFilters(imported.filters)
                    }
                    // Switch to the imported canvas/output settings and first collection so positions line up.
                    profilesRepo.activate(result.profile.id)
                    result.collections.firstOrNull()?.let { ImportSelection.requestedCollectionId.value = it.collection.id }
                    _importReport.value = ImportReport(
                        profileName = result.profile.name,
                        collectionNames = result.collections.map { it.collection.name },
                        sceneCount = result.collections.sumOf { it.scenes.size },
                        sourceCount = result.collections.sumOf { it.sources.size },
                        warnings = result.warnings
                    )
                    val warningText = if (result.warnings.isEmpty()) "" else " with ${result.warnings.size} warnings"
                    done("Imported ${result.profile.name}$warningText")
                }
                .onFailure { done("Import failed: ${it.message}") }
        }
    }

    fun dismissImportReport() { _importReport.value = null }

}
