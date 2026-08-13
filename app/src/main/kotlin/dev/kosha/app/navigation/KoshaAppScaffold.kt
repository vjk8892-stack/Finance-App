package dev.kosha.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.kosha.app.R
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaType
import dev.kosha.feature.budget.BudgetScreen
import dev.kosha.feature.budget.recurring.RecurringScreen
import dev.kosha.feature.export.ExportScreen
import dev.kosha.feature.goals.GoalsScreen
import dev.kosha.feature.income.IncomeScreen
import dev.kosha.feature.ingest.ocr.ImportScreen
import dev.kosha.feature.ingest.ocr.ScanScreen
import dev.kosha.feature.ingest.review.ReviewQueueScreen
import dev.kosha.feature.insights.home.HomeScreen
import dev.kosha.feature.insights.hub.InsightsScreen
import dev.kosha.feature.ledger.LedgerScreen
import dev.kosha.feature.ledger.accounts.AccountsScreen
import dev.kosha.feature.ledger.add.AddScreen
import dev.kosha.feature.vault.VaultScreen
import dev.kosha.feature.widgets.KoshaDeepLinks

/**
 * Bottom nav per spec C1: Home · Ledger · Add (center, camera-first) ·
 * Insights · Vault. Budgets, Income and Recurring are reached from Home.
 */
enum class KoshaDestination(val route: String, val labelRes: Int, val icon: ImageVector) {
    HOME("home", R.string.nav_home, Icons.Outlined.Home),
    LEDGER("ledger", R.string.nav_ledger, Icons.Outlined.AccountBalanceWallet),
    ADD("add", R.string.nav_add, Icons.Outlined.PhotoCamera),
    INSIGHTS("insights", R.string.nav_insights, Icons.Outlined.Insights),
    VAULT("vault", R.string.nav_vault, Icons.Outlined.Lock),
}

const val ROUTE_ACCOUNTS = "accounts"
const val ROUTE_REVIEW = "review"
const val ROUTE_BUDGETS = "budgets"
const val ROUTE_INCOME = "income"
const val ROUTE_RECURRING = "recurring"
const val ROUTE_EXPORT = "export"
const val ROUTE_GOALS = "goals"
const val ARG_QUICK_CATEGORY = "quickCategoryId"

@Composable
fun KoshaAppScaffold(startAction: String? = null) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination

    // Widget / QS tile / icon-shortcut entry points (spec G11) land directly
    // on their destination rather than on Home.
    androidx.compose.runtime.LaunchedEffect(startAction) {
        when (startAction) {
            KoshaDeepLinks.ACTION_QUICK_ADD, KoshaDeepLinks.ACTION_SCAN ->
                navController.navigate(KoshaDestination.ADD.route)
            KoshaDeepLinks.ACTION_VAULT ->
                navController.navigate(KoshaDestination.VAULT.route)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = KoshaColors.CharcoalRaised) {
                KoshaDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route?.startsWith(destination.route) == true } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(KoshaDestination.HOME.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(destination.icon, contentDescription = stringResource(destination.labelRes))
                        },
                        label = { Text(stringResource(destination.labelRes), style = KoshaType.Caption) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = KoshaColors.OffWhite,
                            selectedTextColor = KoshaColors.OffWhite,
                            unselectedIconColor = KoshaColors.OffWhiteFaint,
                            unselectedTextColor = KoshaColors.OffWhiteFaint,
                            indicatorColor = KoshaColors.CharcoalOverlay,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = KoshaDestination.HOME.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(KoshaDestination.HOME.route) {
                HomeScreen(
                    onOpenBudgets = { navController.navigate(ROUTE_BUDGETS) },
                    onOpenIncome = { navController.navigate(ROUTE_INCOME) },
                    onOpenRecurring = { navController.navigate(ROUTE_RECURRING) },
                    onOpenExport = { navController.navigate(ROUTE_EXPORT) },
                    onOpenGoals = { navController.navigate(ROUTE_GOALS) },
                    onQuickAdd = { categoryId ->
                        navController.navigate("${KoshaDestination.ADD.route}?$ARG_QUICK_CATEGORY=$categoryId")
                    },
                    onOpenReview = { navController.navigate(ROUTE_REVIEW) },
                )
            }
            composable(KoshaDestination.LEDGER.route) {
                LedgerScreen(
                    onOpenAccounts = { navController.navigate(ROUTE_ACCOUNTS) },
                    onOpenReview = { navController.navigate(ROUTE_REVIEW) },
                )
            }
            composable(
                route = "${KoshaDestination.ADD.route}?$ARG_QUICK_CATEGORY={$ARG_QUICK_CATEGORY}",
                arguments = listOf(
                    navArgument(ARG_QUICK_CATEGORY) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                ),
            ) { entry ->
                val quickCategoryId = entry.arguments?.getLong(ARG_QUICK_CATEGORY)?.takeIf { it > 0 }
                AddScreen(
                    quickCategoryId = quickCategoryId,
                    scanTab = { ScanScreen() },
                    importTab = { ImportScreen() },
                )
            }
            composable(KoshaDestination.INSIGHTS.route) {
                InsightsScreen()
            }
            composable(KoshaDestination.VAULT.route) {
                VaultScreen()
            }
            composable(ROUTE_ACCOUNTS) {
                AccountsScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_REVIEW) {
                ReviewQueueScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_BUDGETS) {
                BudgetScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_INCOME) {
                IncomeScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_RECURRING) {
                RecurringScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_EXPORT) {
                ExportScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_GOALS) {
                GoalsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
