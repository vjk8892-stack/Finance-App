package dev.kosha.feature.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.database.dao.VaultDao
import dev.kosha.core.database.model.VaultEntryEntity
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A revealed field with the countdown that will re-mask it. */
data class RevealedField(
    val entryId: Long,
    val fieldName: String,
    val value: String,
    val secondsRemaining: Int,
)

data class VaultUiState(
    val unlocked: Boolean = false,
    val entries: List<VaultEntryEntity> = emptyList(),
    val revealed: RevealedField? = null,
    val keyInvalidated: Boolean = false,
)

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val vaultDao: VaultDao,
    private val crypto: VaultCrypto,
) : ViewModel() {

    private val _state = MutableStateFlow(VaultUiState())
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    val entries: StateFlow<List<VaultEntryEntity>> = vaultDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Called only after BiometricPrompt succeeds — nothing renders before. */
    fun onUnlocked() {
        _state.value = _state.value.copy(unlocked = true)
    }

    fun lock() {
        _state.value = VaultUiState()
    }

    /**
     * Per-field reveal with a 20s re-mask countdown (spec B4/C6). Reveal is
     * never global: exactly one field is visible at a time.
     */
    fun reveal(entry: VaultEntryEntity, fieldName: String) {
        viewModelScope.launch {
            val fields = try {
                crypto.decrypt(entry.fieldsEncrypted)
            } catch (e: VaultCrypto.VaultKeyInvalidated) {
                _state.value = _state.value.copy(keyInvalidated = true)
                return@launch
            } catch (e: Exception) {
                // Auth window lapsed — the UI re-prompts.
                _state.value = _state.value.copy(revealed = null)
                return@launch
            }
            val value = fields[fieldName] ?: return@launch

            for (remaining in VaultCrypto.AUTH_VALIDITY_SECONDS downTo 1) {
                if (_state.value.revealed?.fieldName != fieldName &&
                    _state.value.revealed != null && remaining < VaultCrypto.AUTH_VALIDITY_SECONDS
                ) {
                    return@launch // another field took over
                }
                _state.value = _state.value.copy(
                    revealed = RevealedField(entry.id, fieldName, value, remaining),
                )
                delay(1_000)
            }
            // Auto re-mask.
            if (_state.value.revealed?.fieldName == fieldName) {
                _state.value = _state.value.copy(revealed = null)
            }
        }
    }

    fun remask() {
        _state.value = _state.value.copy(revealed = null)
    }

    suspend fun fieldNames(entry: VaultEntryEntity): List<String> = try {
        crypto.decrypt(entry.fieldsEncrypted).keys.toList()
    } catch (e: Exception) {
        emptyList()
    }

    fun addEntry(label: String, kind: String, fields: Map<String, String>) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            vaultDao.insert(
                VaultEntryEntity(
                    label = label,
                    kind = kind,
                    fieldsEncrypted = crypto.encrypt(fields),
                    createdAtMillis = now,
                    updatedAtMillis = now,
                ),
            )
        }
    }

    fun delete(entry: VaultEntryEntity) {
        viewModelScope.launch { vaultDao.delete(entry) }
    }
}
