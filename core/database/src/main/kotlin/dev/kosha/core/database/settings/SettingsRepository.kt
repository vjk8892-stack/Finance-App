package dev.kosha.core.database.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "kosha_settings")

data class KoshaSettings(
    val onboardingDone: Boolean = false,
    /** Ring 0 (spec B4): optional app lock via BiometricPrompt + device credential. */
    val appLockEnabled: Boolean = false,
    /** 0 = lock immediately; else background-grace in millis (1 min / 5 min). */
    val appLockTimeoutMillis: Long = 0,
    /** Monthly period anchor day, 1–28 (spec G1). */
    val periodAnchorDay: Int = 1,
    /** Debug: retain raw SMS bodies as evidence (spec B4, default OFF). */
    val retainRawSms: Boolean = false,
    /** Emergency-fund months target, 3–12 (spec G12). */
    val emergencyFundMonths: Int = 3,
    /**
     * The folder the user picked for backups, as a persisted SAF tree URI.
     * Asked for once and then reused, so backing up is a single tap and every
     * backup lands in the same place instead of wherever the save dialog last
     * happened to be pointing.
     */
    val backupFolderUri: String? = null,
    /** When the last successful backup was written, 0 if never. */
    val lastBackupAtMillis: Long = 0,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val appLockEnabled = booleanPreferencesKey("app_lock_enabled")
        val appLockTimeout = longPreferencesKey("app_lock_timeout_ms")
        val periodAnchorDay = intPreferencesKey("period_anchor_day")
        val retainRawSms = booleanPreferencesKey("retain_raw_sms")
        val emergencyFundMonths = intPreferencesKey("emergency_fund_months")
        val backupFolderUri = stringPreferencesKey("backup_folder_uri")
        val lastBackupAt = longPreferencesKey("last_backup_at")
    }

    val settings: Flow<KoshaSettings> = context.dataStore.data.map { p ->
        KoshaSettings(
            onboardingDone = p[Keys.onboardingDone] ?: false,
            appLockEnabled = p[Keys.appLockEnabled] ?: false,
            appLockTimeoutMillis = p[Keys.appLockTimeout] ?: 0,
            periodAnchorDay = (p[Keys.periodAnchorDay] ?: 1).coerceIn(1, 28),
            retainRawSms = p[Keys.retainRawSms] ?: false,
            emergencyFundMonths = (p[Keys.emergencyFundMonths] ?: 3).coerceIn(3, 12),
            backupFolderUri = p[Keys.backupFolderUri],
            lastBackupAtMillis = p[Keys.lastBackupAt] ?: 0,
        )
    }

    suspend fun setBackupFolderUri(uri: String) = context.dataStore.edit {
        it[Keys.backupFolderUri] = uri
    }

    suspend fun setLastBackupAt(millis: Long) = context.dataStore.edit {
        it[Keys.lastBackupAt] = millis
    }

    suspend fun setOnboardingDone() = context.dataStore.edit { it[Keys.onboardingDone] = true }

    suspend fun setAppLock(enabled: Boolean, timeoutMillis: Long) = context.dataStore.edit {
        it[Keys.appLockEnabled] = enabled
        it[Keys.appLockTimeout] = timeoutMillis
    }

    suspend fun setPeriodAnchorDay(day: Int) = context.dataStore.edit {
        it[Keys.periodAnchorDay] = day.coerceIn(1, 28)
    }

    /**
     * Opt-in raw SMS retention (spec B4). Off by default: normally only the
     * extracted fields are kept. Turning it on stores the original message
     * alongside the transaction so a mis-parse can actually be diagnosed.
     */
    suspend fun setRetainRawSms(retain: Boolean) = context.dataStore.edit {
        it[Keys.retainRawSms] = retain
    }
}
