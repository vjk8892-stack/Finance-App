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
        if (dao.count() > 0) return
        dao.insertAll(seedCategories())
    }

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
