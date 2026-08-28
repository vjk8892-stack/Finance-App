package dev.kosha.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kosha.core.database.KoshaDatabase
import dev.kosha.core.database.MIGRATION_1_2
import dev.kosha.core.database.security.DbKeyManager
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDbKeyManager(@ApplicationContext context: Context): DbKeyManager =
        DbKeyManager(context)

    @Provides
    @Singleton
    fun provideKoshaDatabase(
        @ApplicationContext context: Context,
        keyManager: DbKeyManager,
    ): KoshaDatabase {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(keyManager.getOrCreateDbPassphrase())
        return Room.databaseBuilder(context, KoshaDatabase::class.java, KoshaDatabase.NAME)
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2)
            // Remaining safety net for any version this build has no explicit
            // path for; Room always prefers an explicit Migration when one is
            // registered for the exact hop, so this never shadows MIGRATION_1_2.
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun provideAccountDao(db: KoshaDatabase) = db.accountDao()
    @Provides fun provideCategoryDao(db: KoshaDatabase) = db.categoryDao()
    @Provides fun provideTransactionDao(db: KoshaDatabase) = db.transactionDao()
    @Provides fun providePlanningDao(db: KoshaDatabase) = db.planningDao()
    @Provides fun provideGoalsDao(db: KoshaDatabase) = db.goalsDao()
    @Provides fun provideMetaDao(db: KoshaDatabase) = db.metaDao()
    @Provides fun provideVaultDao(db: KoshaDatabase) = db.vaultDao()
}
