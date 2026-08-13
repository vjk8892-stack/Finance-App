package dev.kosha.feature.ledger.query

import dev.kosha.core.database.dao.LedgerRow
import dev.kosha.core.engine.query.QueryAnswer
import dev.kosha.core.engine.query.QueryFilter

data class QueryUiState(
    val text: String = "",
    val answer: QueryAnswer? = null,
    val rows: List<LedgerRow> = emptyList(),
    /** True when the NLU could not fully parse and handed off to the builder. */
    val fellBackToBuilder: Boolean = false,
    val partialFilter: QueryFilter? = null,
    val builderOpen: Boolean = false,
) {
    val isFiltering: Boolean get() = answer != null
}
