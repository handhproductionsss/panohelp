package com.example.ui.sessions

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.export.ExportResult
import com.example.data.export.PackageExporter
import com.example.data.local.AppPreferences
import com.example.data.local.PanoDatabase
import com.example.data.local.SceneWithTargets
import com.example.data.repository.SceneRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SessionsActionState(
    val isExporting: Boolean = false,
    val exportSuccess: ExportResult? = null,
    val pendingShareIntent: Intent? = null,
    val errorMessage: String? = null
)

class SessionsViewModel(application: Application) : AndroidViewModel(application) {

    private val database = PanoDatabase.getInstance(application)
    private val repository = SceneRepository(application, database.panoDao())
    private val preferences = AppPreferences(application)
    private val exporter = PackageExporter(application)

    val scenes: StateFlow<List<SceneWithTargets>> = repository.allScenes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val simulatedMode: StateFlow<Boolean> = preferences.simulatedModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _actionState = MutableStateFlow(SessionsActionState())
    val actionState: StateFlow<SessionsActionState> = _actionState.asStateFlow()

    fun setSimulatedMode(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setSimulatedMode(enabled)
        }
    }

    fun createScene(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val isSim = simulatedMode.value
            val sceneId = repository.createScene(name = name, isSimulated = isSim)
            onCreated(sceneId)
        }
    }

    fun deleteScene(sceneId: String) {
        viewModelScope.launch {
            repository.deleteScene(sceneId)
        }
    }

    fun exportAndShareScene(sceneWithTargets: SceneWithTargets) {
        if (_actionState.value.isExporting) return
        viewModelScope.launch {
            _actionState.value = SessionsActionState(isExporting = true)
            runCatching {
                val result = exporter.exportScene(sceneWithTargets)
                repository.markExported(
                    sceneId = sceneWithTargets.scene.id,
                    exportUri = result.downloadsUriString,
                    exportDisplayName = result.zipFileName
                )
                val shareIntent = exporter.createShareIntent(result.shareUri, sceneWithTargets.scene.name)
                result to shareIntent
            }.onSuccess { (result, shareIntent) ->
                _actionState.value = SessionsActionState(
                    isExporting = false,
                    exportSuccess = result,
                    pendingShareIntent = shareIntent
                )
            }.onFailure { err ->
                _actionState.value = SessionsActionState(
                    isExporting = false,
                    errorMessage = err.message ?: "Export failed"
                )
            }
        }
    }

    fun consumeShareIntent() {
        _actionState.value = _actionState.value.copy(pendingShareIntent = null)
    }

    fun dismissDialog() {
        _actionState.value = SessionsActionState()
    }
}
