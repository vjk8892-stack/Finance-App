package dev.kosha.core.database.repo

import dev.kosha.core.database.dao.AccountDao
import dev.kosha.core.database.model.AccountEntity
import dev.kosha.core.database.model.AccountType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao,
) {
    fun observeActive(): Flow<List<AccountEntity>> = accountDao.observeActive()

    suspend fun create(
        name: String,
        type: AccountType,
        last4: String? = null,
        openingBalancePaise: Long = 0,
    ): Long {
        // Round-robin auto-assignment from the 8-swatch palette (spec G3).
        val colorToken = accountDao.count() % 8
        return accountDao.insert(
            AccountEntity(
                name = name,
                type = type,
                last4 = last4?.takeIf { it.isNotBlank() },
                openingBalancePaise = openingBalancePaise,
                currentBalancePaise = openingBalancePaise,
                colorToken = colorToken,
            ),
        )
    }

    suspend fun update(account: AccountEntity) = accountDao.update(account)

    suspend fun deactivate(id: Long) = accountDao.deactivate(id)
}
