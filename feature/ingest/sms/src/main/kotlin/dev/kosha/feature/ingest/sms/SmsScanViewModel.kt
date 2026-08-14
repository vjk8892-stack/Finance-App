package dev.kosha.feature.ingest.sms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.database.settings.SettingsRepository
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
    val retainRawSms: Boolean = false,
    /** Start of the day chosen in the date picker, if any. */
    val customStartMillis: Long? = null,
    val error: String? = null,
)

@HiltViewModel
class SmsScanViewModel @Inject constructor(
    private val importer: HistoricalSmsImporter,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SmsScanUiState())
    val state: StateFlow<SmsScanUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _state.value = _state.value.copy(retainRawSms = settings.retainRawSms)
            }
        }
    }

    /** Spec B4 debug toggle: keep the original message to diagnose mis-parses. */
    fun setRetainRawSms(retain: Boolean) {
        viewModelScope.launch { settingsRepository.setRetainRawSms(retain) }
    }

    fun onPermissionResult(granted: Boolean) {
        _state.value = _state.value.copy(permissionGranted = granted)
    }

    fun scan(monthsBack: Int) {
        runScan { onProgress -> importer.import(monthsBack, onProgress) }
    }

    /** "From this date" scan — [sinceMillis] is the start of the chosen day. */
    fun scanSince(sinceMillis: Long) {
        runScan { onProgress -> importer.importSince(sinceMillis, onProgress) }
    }

    fun setCustomStart(sinceMillis: Long?) {
        _state.value = _state.value.copy(customStartMillis = sinceMillis)
    }

    private fun runScan(
        block: suspend (onProgress: (Int, Int) -> Unit) -> HistoricalSmsImporter.ImportSummary,
    ) {
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true, summary = null, error = null)
        viewModelScope.launch {
            val result = runCatching {
                block { scanned, total ->
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
