package dev.kosha.feature.ingest.sms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SmsScanUiState(
    val scanning: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    val summary: HistoricalSmsImporter.ImportSummary? = null,
    val permissionGranted: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SmsScanViewModel @Inject constructor(
    private val importer: HistoricalSmsImporter,
) : ViewModel() {

    private val _state = MutableStateFlow(SmsScanUiState())
    val state: StateFlow<SmsScanUiState> = _state.asStateFlow()

    fun onPermissionResult(granted: Boolean) {
        _state.value = _state.value.copy(permissionGranted = granted)
    }

    fun scan(monthsBack: Int) {
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true, summary = null, error = null)
        viewModelScope.launch {
            val result = runCatching {
                importer.import(monthsBack) { scanned, total ->
                    _state.value = _state.value.copy(progress = scanned to total)
                }
            }
            _state.value = _state.value.copy(
                scanning = false,
                progress = null,
                summary = result.getOrNull(),
                error = result.exceptionOrNull()?.message,
            )
        }
    }
}
