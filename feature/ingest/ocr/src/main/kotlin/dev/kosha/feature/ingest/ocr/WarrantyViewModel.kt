package dev.kosha.feature.ingest.ocr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.database.dao.MetaDao
import dev.kosha.core.database.model.WarrantyItemEntity
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Warranties were captured (the OCR bill-scan prompt saves one on request)
 * but had no screen to view them on — design review finding. This is that
 * screen's ViewModel: list + delete, soonest-expiring first.
 */
@HiltViewModel
class WarrantyViewModel @Inject constructor(
    private val metaDao: MetaDao,
) : ViewModel() {

    val items: StateFlow<List<WarrantyItemEntity>> = metaDao.observeWarranties()
        .map { list -> list.sortedBy { it.expiryDateMillis } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(item: WarrantyItemEntity) {
        viewModelScope.launch { metaDao.deleteWarranty(item) }
    }
}
