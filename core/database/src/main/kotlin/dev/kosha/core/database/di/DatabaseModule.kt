package dev.kosha.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kosha.core.database.KoshaDatabase
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
            // Pre-Phase-2 only; replaced by tested Migrations once the schema
            // freezes (spec B5 migration policy).
            .fallbackToDestructiveMigration()
            .build()
    }
}
