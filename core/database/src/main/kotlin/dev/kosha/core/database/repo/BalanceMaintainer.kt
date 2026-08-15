package dev.kosha.core.database.repo

import dev.kosha.core.database.dao.AccountDao
import dev.kosha.core.database.settings.TrackingWindow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only way an account balance is recomputed.
 *
 * The tracking boundary has to reach the balance sum, and there are a dozen
 * places that recompute a balance — every commit, edit, delete, undo, split
 * and recurring materialisation. Threading the boundary through all of them
 * individually is how one of them ends up not having it, leaving a single
 * account quietly computed over a different window from the rest.
 */
@Singleton
class BalanceMaintainer @Inject constructor(
    private val accountDao: AccountDao,
    private val trackingWindow: TrackingWindow,
) {
    suspend fun recompute(accountId: Long) {
        accountDao.recomputeBalance(accountId, trackingWindow.startMillisNow())
    }

    suspend fun recomputeAll(accountIds: Collection<Long>) {
        val from = trackingWindow.startMillisNow()
        accountIds.distinct().forEach { accountDao.recomputeBalance(it, from) }
    }

    /** After the boundary moves, every account's balance is stale. */
    suspend fun recomputeEverything() {
        recomputeAll(accountDao.allAccounts().map { it.id })
    }
}
