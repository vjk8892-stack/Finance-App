package dev.kosha.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.repo.CategoryRepository
import dev.kosha.core.database.repo.PeriodRepository
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.feature.budget.BudgetAlertWorker
import dev.kosha.feature.budget.recurring.RecurringWorker
import dev.kosha.feature.widgets.WidgetRefreshWorker
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class KoshaApp : Application(), Configuration.Provider {

    @Inject lateinit var categoryRepository: CategoryRepository
    @Inject lateinit var periodRepository: PeriodRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var transactionDao: TransactionDao
    @Inject lateinit var workerFactory: HiltWorkerFactory

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            categoryRepository.ensureSeeded()
            // Auto-close on rollover (spec Phase 3): any elapsed period with
            // ledger activity gets its immutable summary written exactly once.
            val anchorDay = settingsRepository.settings.first().periodAnchorDay
            periodRepository.closeElapsedPeriods(anchorDay)
        }
        BudgetAlertWorker.schedule(this)
        RecurringWorker.schedule(this)
        WidgetRefreshWorker.schedule(this)

        // `refreshNow` carried a comment saying it ran after every commit. It
        // was never called from anywhere, so the widget only ever caught up on
        // its 30-minute timer: pay for lunch, glance at the home screen, and it
        // still shows the figure from before. A widget that is routinely half
        // an hour stale is worse than no widget, because it is believed.
        //
        // Hooked here rather than inside the committer because :core:database
        // cannot see :feature:widgets — this is the one place that sees both.
        // `drop(1)` skips the value the flow emits on subscribe: that is the
        // count as it already is, not a change.
        appScope.launch {
            transactionDao.observeTransactionCount()
                .drop(1)
                .distinctUntilChanged()
                .collect { WidgetRefreshWorker.refreshNow(this@KoshaApp) }
        }
    }
}
