package dev.kosha.core.database.seed

import dev.kosha.core.database.dao.CategoryDao
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.database.model.CategoryType
import dev.kosha.core.database.model.SystemCategoryKey

/**
 * Seed categories, spec G2. Icons are token names resolved by the design
 * system. System-reserved rows are non-deletable; Transfers and Cash
 * Withdrawal are excluded from budgets and all spend analytics (G12).
 */
object CategorySeeder {

    suspend fun ensureSeeded(dao: CategoryDao) {
        if (dao.count() == 0) {
            dao.insertAll(seedCategories())
            return
        }
        // Categories added after the first release would otherwise only ever
        // reach people installing fresh — an existing user would never see
        // them, with no way to tell why. Adding a ROW is not a schema change,
        // so this backfills by name rather than shipping a migration, and it
        // deliberately does not touch anything the user has renamed or
        // reordered.
        val existing = dao.observeAllOnce()
        val existingNames = existing.map { it.name }.toSet()
        val missing = seedCategories().filter { it.name in BACKFILL_NAMES && it.name !in existingNames }
        if (missing.isNotEmpty()) {
            val nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: 0) + 1
            dao.insertAll(
                missing.mapIndexed { index, category ->
                    category.copy(sortOrder = nextOrder + index)
                },
            )
        }
    }

    /** Seeded categories introduced after the first release. */
    private val BACKFILL_NAMES = setOf("Personal")

    fun seedCategories(): List<CategoryEntity> {
        var order = 0
        fun expense(name: String, icon: String) =
            CategoryEntity(name = name, type = CategoryType.EXPENSE, icon = icon, sortOrder = order++)

        val expenses = listOf(
            expense("Food & Dining", "restaurant"),
            expense("Groceries", "grocery"),
            expense("Transport", "transport"),
            expense("Fuel", "fuel"),
            expense("Shopping", "shopping"),
            expense("Bills & Utilities", "bills"),
            expense("Rent", "home"),
            expense("EMI & Loans", "emi"),
            expense("Health", "health"),
            expense("Insurance", "insurance"),
            expense("Education", "education"),
            expense("Entertainment", "entertainment"),
            expense("Subscriptions", "subscriptions"),
            expense("Travel", "travel"),
            expense("Personal Care", "personalcare"),
            expense("Personal", "personal"),
            expense("Construction & Home", "construction"),
        )

        val systemReserved = listOf(
            CategoryEntity(
                name = "Transfers", type = CategoryType.EXPENSE, icon = "transfer",
                isSystem = true, systemKey = SystemCategoryKey.TRANSFERS, sortOrder = order++,
            ),
            CategoryEntity(
                name = "Cash Withdrawal", type = CategoryType.EXPENSE, icon = "atm",
                isSystem = true, systemKey = SystemCategoryKey.CASH_WITHDRAWAL, sortOrder = order++,
            ),
            CategoryEntity(
                name = "Uncategorized", type = CategoryType.EXPENSE, icon = "uncategorized",
                isSystem = true, systemKey = SystemCategoryKey.UNCATEGORIZED, sortOrder = order++,
            ),
        )

        fun income(name: String, icon: String) =
            CategoryEntity(name = name, type = CategoryType.INCOME, icon = icon, sortOrder = order++)

        val incomes = listOf(
            income("Salary", "salary"),
            income("Business", "business"),
            income("Interest & Dividends", "interest"),
            income("Refunds & Cashback", "refund"),
            income("Other Income", "otherincome"),
        )

        return expenses + systemReserved + incomes
    }
}
