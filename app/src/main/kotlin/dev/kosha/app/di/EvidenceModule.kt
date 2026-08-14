package dev.kosha.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kosha.core.database.repo.OriginalMessageSource
import dev.kosha.feature.ingest.sms.InboxMessageSource
import javax.inject.Singleton

/**
 * Binds message read-back to the inbox implementation.
 *
 * Only the app module sees both `:core:database` (which declares the need) and
 * `:feature:ingest:sms` (which owns the SMS permission), so the wiring belongs
 * here. The same binding is correct in the lite build: `InboxMessageSource`
 * checks `SmsCapability` first and returns null when SMS is not available,
 * rather than the feature module having to know which flavor it is in.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EvidenceModule {

    @Binds
    @Singleton
    abstract fun bindOriginalMessageSource(impl: InboxMessageSource): OriginalMessageSource
}
