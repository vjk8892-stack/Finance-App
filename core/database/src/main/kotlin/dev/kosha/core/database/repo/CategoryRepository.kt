package dev.kosha.core.database.repo

import dev.kosha.core.database.dao.CategoryDao
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.database.model.CategoryType
import dev.kosha.core.database.model.SystemCategoryKey
import dev.kosha.core.database.seed.CategorySeeder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
) {
    fun observeAll(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    fun observeByType(type: CategoryType): Flow<List<CategoryEntity>> =
        categoryDao.observeByType(type)

    suspend fun ensureSeeded() = CategorySeeder.ensureSeeded(categoryDao)

    suspend fun uncategorized(): CategoryEntity? =
        categoryDao.bySystemKey(SystemCategoryKey.UNCATEGORIZED)

    suspend fun create(category: CategoryEntity): Long = categoryDao.insert(category)

    suspend fun rename(id: Long, newName: String) {
        val existing = categoryDao.byId(id) ?: return
        categoryDao.update(existing.copy(name = newName))
    }

    suspend fun deleteNonSystem(id: Long) = categoryDao.deleteNonSystem(id)
}
