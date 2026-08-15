package dev.kosha.core.database.settings

import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * "Start counting from here."
 *
 * One place that answers "is this transaction inside the tracked window", so
 * the ledger, the totals, the charts, the balances, the exports and the SMS
 * importer cannot disagree about where history begins. A boundary that half
 * the app honours is worse than no boundary — it produces exactly the kind of
 * two-numbers-for-one-month disagreement that makes both figures untrustworthy.
 *
 * It is a FILTER, never a delete. Rows before the boundary keep their
 * categories, their edits and their transfer marks; moving the boundary back
 * reveals them exactly as they were left.
 */
@Singleton
class TrackingWindow @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    private val zone: ZoneId = ZoneId.systemDefault()

    /** Epoch millis of the first tracked instant; 0 means "track everything". */
    val startMillis: Flow<Long> = settingsRepository.settings.map { it.startMillis() }

    val startDate: Flow<LocalDate?> = settingsRepository.settings.map { it.trackingStartDate }

    suspend fun startMillisNow(): Long = settingsRepository.settings.first().startMillis()

    suspend fun startDateNow(): LocalDate? = settingsRepository.settings.first().trackingStartDate

    /**
     * Raises [from] to the boundary. Used wherever a window is already being
     * queried, so the tracked start wins over a period that begins earlier
     * without anything else having to know the boundary exists.
     */
    suspend fun clampFrom(from: Long): Long = maxOf(from, startMillisNow())

    private fun KoshaSettings.startMillis(): Long =
        trackingStartDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: 0L
}
