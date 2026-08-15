package dev.kosha.feature.ingest.ocr

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.MetaDao
import dev.kosha.core.database.model.WarrantyItemEntity
import dev.kosha.core.database.repo.PipelineCommitter
import dev.kosha.core.engine.pipeline.TxnType
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CaptureUiState(
    val processing: Boolean = false,
    /** Pending previews — more than one when batch mode captured a run. */
    val queue: List<IngestPhotoUseCase.Preview> = emptyList(),
    val batchMode: Boolean = false,
    val batchCount: Int = 0,
    val unreadable: Boolean = false,
    /**
     * The camera itself failed to produce a file. Reported separately from
     * [unreadable]: 'the photo could not be taken' and 'the photo could not be
     * read' need different advice, and swallowing the first meant tapping the
     * shutter did nothing at all, forever, with no clue why.
     */
    val captureFailed: Boolean = false,
    val lastResult: PipelineCommitter.CommitResult? = null,
    val warrantyPrompt: WarrantyPrompt? = null,
) {
    val current: IngestPhotoUseCase.Preview? get() = queue.firstOrNull()
}

data class WarrantyPrompt(val itemName: String, val transactionId: Long?, val purchaseMillis: Long)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val ingestPhoto: IngestPhotoUseCase,
    private val metaDao: MetaDao,
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    fun toggleBatch() {
        _state.value = _state.value.copy(batchMode = !_state.value.batchMode, batchCount = 0)
    }

    /** The shutter was pressed; the file does not exist yet. */
    fun onCaptureStarted() {
        _state.value = _state.value.copy(processing = true, unreadable = false, captureFailed = false)
    }

    fun onCaptureFailed() {
        _state.value = _state.value.copy(processing = false, captureFailed = true)
    }

    fun onCaptured(uri: Uri, liveCapture: Boolean) {
        _state.value = _state.value.copy(processing = true, unreadable = false, captureFailed = false)
        viewModelScope.launch {
            val preview = ingestPhoto.preview(uri, liveCapture)
            _state.value = if (preview == null) {
                _state.value.copy(processing = false, unreadable = true)
            } else {
                _state.value.copy(
                    processing = false,
                    queue = _state.value.queue + preview,
                    batchCount = _state.value.batchCount + 1,
                )
            }
        }
    }

    fun editAmount(text: String) = editCurrent { it.copy(amount = Money.parseOrNull(text)) }

    fun editMerchant(text: String) = editCurrent { it.copy(merchant = text.takeIf { t -> t.isNotBlank() }) }

    fun setType(type: TxnType) = editCurrent { it.copy(type = type) }

    private fun editCurrent(edit: (IngestPhotoUseCase.Preview) -> IngestPhotoUseCase.Preview) {
        val queue = _state.value.queue
        if (queue.isEmpty()) return
        _state.value = _state.value.copy(queue = listOf(edit(queue.first())) + queue.drop(1))
    }

    fun confirmCurrent() {
        val preview = _state.value.current ?: return
        viewModelScope.launch {
            val result = ingestPhoto.commit(preview, confirmedByUser = true)
            val warranty = preview.warrantyCandidate?.let { item ->
                WarrantyPrompt(
                    itemName = item,
                    transactionId = (result as? PipelineCommitter.CommitResult.Committed)?.txnId,
                    purchaseMillis = preview.capturedAtMillis,
                )
            }
            _state.value = _state.value.copy(
                queue = _state.value.queue.drop(1),
                lastResult = result,
                warrantyPrompt = warranty,
            )
        }
    }

    fun discardCurrent() {
        _state.value = _state.value.copy(queue = _state.value.queue.drop(1), warrantyPrompt = null)
    }

    /** Warranty capture on a successful bill parse (spec Phase 4). */
    fun saveWarranty(months: Int) {
        val prompt = _state.value.warrantyPrompt ?: return
        viewModelScope.launch {
            val purchase = Instant.ofEpochMilli(prompt.purchaseMillis).atZone(ZoneId.systemDefault())
            metaDao.insertWarranty(
                WarrantyItemEntity(
                    transactionId = prompt.transactionId,
                    itemName = prompt.itemName,
                    purchaseDateMillis = prompt.purchaseMillis,
                    warrantyMonths = months,
                    expiryDateMillis = purchase.plusMonths(months.toLong()).toInstant().toEpochMilli(),
                ),
            )
            _state.value = _state.value.copy(warrantyPrompt = null)
        }
    }

    fun dismissWarranty() {
        _state.value = _state.value.copy(warrantyPrompt = null)
    }

    fun clearResult() {
        _state.value = _state.value.copy(lastResult = null, unreadable = false, captureFailed = false)
    }
}
