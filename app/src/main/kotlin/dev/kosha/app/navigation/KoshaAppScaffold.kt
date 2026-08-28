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
import android.net.Uri
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
import dev.kosha.app.settings.PermissionsScreen
import dev.kosha.app.settings.SettingsScreen
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
import dev.kosha.feature.ingest.sms.CaptureNotifier
import dev.kosha.feature.ingest.sms.SmsScanScreen
import dev.kosha.feature.insights.home.HomeScreen
import dev.kosha.feature.insights.hub.InsightsScreen
import dev.kosha.feature.ledger.LedgerScreen
import dev.kosha.feature.ledger.accounts.AccountStatementScreen
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
const val ROUTE_STATEMENT = "statement"
const val ARG_ACCOUNT_ID = "accountId"
const val ROUTE_REVIEW = "review"
const val ROUTE_BUDGETS = "budgets"
const val ROUTE_INCOME = "income"
const val ROUTE_RECURRING = "recurring"
const val ROUTE_EXPORT = "export"
const val ROUTE_GOALS = "goals"
const val ROUTE_SMS_SCAN = "sms-scan"
const val ROUTE_PERMISSIONS = "permissions"
const val ROUTE_SETTINGS = "settings"
const val ARG_EXPORT_FOCUS_BACKUP = "focusBackup"
const val ARG_QUICK_CATEGORY = "quickCategoryId"

/** Chart → ledger deep links, so a slice can show the rows behind it. */
const val ARG_LEDGER_CATEGORY = "ledgerCategory"
const val ARG_LEDGER_MONTH = "ledgerMonth"
const val ARG_LEDGER_FROM = "ledgerFrom"
const val ARG_LEDGER_TO = "ledgerTo"
const val ARG_LEDGER_SEARCH = "ledgerSearch"

@Composable
fun KoshaAppScaffold(
    startAction: String? = null,
    /** Set when opened from a capture notification — the day to land on. */
    startLedgerDay: String? = null,
) {
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
            CaptureNotifier.ACTION_OPEN_TRANSACTION -> {
                // Filtered to the transaction's own day, so the row is on
                // screen rather than somewhere in a list. A notification that
                // opens the app wherever it was left is barely better than no
                // notification at all.
                val filter = startLedgerDay
                    ?.let { "?$ARG_LEDGER_FROM=$it&$ARG_LEDGER_TO=$it" }
                    .orEmpty()
                navController.navigate(KoshaDestination.LEDGER.route + filter)
            }
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
                            // No saveState/restoreState. With them, opening the
                            // ledger from an Insights chart put ledger on top of
                            // insights; tapping Insights then popped that stack,
                            // saved it, and immediately RESTORED it — landing
                            // back on the filtered ledger. A tab that does not go
                            // to its own tab is broken, and scroll position is not
                            // worth that. popUpTo leaves Home beneath, so the
                            // system back button still exits from any tab.
                            navController.navigate(destination.route) {
                                popUpTo(KoshaDestination.HOME.route)
                                launchSingleTop = true
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
                    onOpenLedger = { from, to ->
                        val args = listOfNotNull(
                            from?.let { "$ARG_LEDGER_FROM=$it" },
                            to?.let { "$ARG_LEDGER_TO=$it" },
                        ).joinToString("&")
                        navController.navigate(
                            KoshaDestination.LEDGER.route + if (args.isEmpty()) "" else "?$args",
                        )
                    },
                    onOpenBudgets = { navController.navigate(ROUTE_BUDGETS) },
                    onOpenIncome = { navController.navigate(ROUTE_INCOME) },
                    onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                    onOpenRecurring = { navController.navigate(ROUTE_RECURRING) },
                    onOpenGoals = { navController.navigate(ROUTE_GOALS) },
                    onQuickAdd = { categoryId ->
                        navController.navigate("${KoshaDestination.ADD.route}?$ARG_QUICK_CATEGORY=$categoryId")
                    },
                    onOpenReview = { navController.navigate(ROUTE_REVIEW) },
                )
            }
            composable(
                route = "${KoshaDestination.LEDGER.route}" +
                    "?$ARG_LEDGER_CATEGORY={$ARG_LEDGER_CATEGORY}&$ARG_LEDGER_MONTH={$ARG_LEDGER_MONTH}" +
                    "&$ARG_LEDGER_FROM={$ARG_LEDGER_FROM}&$ARG_LEDGER_TO={$ARG_LEDGER_TO}" +
                    "&$ARG_LEDGER_SEARCH={$ARG_LEDGER_SEARCH}",
                arguments = listOf(
                    navArgument(ARG_LEDGER_CATEGORY) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(ARG_LEDGER_MONTH) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(ARG_LEDGER_FROM) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(ARG_LEDGER_TO) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(ARG_LEDGER_SEARCH) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                LedgerScreen(
                    onOpenAccounts = { navController.navigate(ROUTE_ACCOUNTS) },
                    onOpenReview = { navController.navigate(ROUTE_REVIEW) },
                    onScanSms = { navController.navigate(ROUTE_SMS_SCAN) },
                    onOpenBudgets = { navController.navigate(ROUTE_BUDGETS) },
                    incomingCategory = entry.arguments?.getString(ARG_LEDGER_CATEGORY),
                    incomingMonth = entry.arguments?.getString(ARG_LEDGER_MONTH),
                    incomingFrom = entry.arguments?.getString(ARG_LEDGER_FROM),
                    incomingTo = entry.arguments?.getString(ARG_LEDGER_TO),
                    incomingSearch = entry.arguments?.getString(ARG_LEDGER_SEARCH),
                    onAddTransaction = { navController.navigate(KoshaDestination.ADD.route) },
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
                InsightsScreen(
                    onOpenLedger = { target ->
                        val args = listOfNotNull(
                            target.category?.let { "$ARG_LEDGER_CATEGORY=${Uri.encode(it)}" },
                            target.monthKey?.let { "$ARG_LEDGER_MONTH=$it" },
                            target.search?.let { "$ARG_LEDGER_SEARCH=${Uri.encode(it)}" },
                            target.from?.let { "$ARG_LEDGER_FROM=$it" },
                            target.to?.let { "$ARG_LEDGER_TO=$it" },
                        ).joinToString("&")
                        navController.navigate(
                            "${KoshaDestination.LEDGER.route}" + if (args.isEmpty()) "" else "?$args",
                        )
                    },
                )
            }
            composable(KoshaDestination.VAULT.route) {
                VaultScreen()
            }
            composable(ROUTE_ACCOUNTS) {
                AccountsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenStatement = { id -> navController.navigate("$ROUTE_STATEMENT/$id") },
                )
            }
            composable(
                route = "$ROUTE_STATEMENT/{$ARG_ACCOUNT_ID}",
                arguments = listOf(navArgument(ARG_ACCOUNT_ID) { type = NavType.LongType }),
            ) { entry ->
                AccountStatementScreen(
                    accountId = entry.arguments?.getLong(ARG_ACCOUNT_ID) ?: 0L,
                    onBack = { navController.popBackStack() },
                )
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
            composable(
                route = "$ROUTE_EXPORT?$ARG_EXPORT_FOCUS_BACKUP={$ARG_EXPORT_FOCUS_BACKUP}",
                arguments = listOf(
                    navArgument(ARG_EXPORT_FOCUS_BACKUP) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { entry ->
                ExportScreen(
                    onBack = { navController.popBackStack() },
                    // Settings offers export and backup as separate rows; both
                    // land here, so the one you asked for goes on top rather
                    // than making you hunt past the other.
                    focusBackup = entry.arguments?.getBoolean(ARG_EXPORT_FOCUS_BACKUP) == true,
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenExport = { navController.navigate(ROUTE_EXPORT) },
                    onOpenBackup = {
                        navController.navigate("$ROUTE_EXPORT?$ARG_EXPORT_FOCUS_BACKUP=true")
                    },
                    onOpenIncome = { navController.navigate(ROUTE_INCOME) },
                    onOpenBudgets = { navController.navigate(ROUTE_BUDGETS) },
                    onOpenRecurring = { navController.navigate(ROUTE_RECURRING) },
                    onOpenGoals = { navController.navigate(ROUTE_GOALS) },
                    onOpenPermissions = { navController.navigate(ROUTE_PERMISSIONS) },
                    onScanSms = { navController.navigate(ROUTE_SMS_SCAN) },
                    onOpenAccounts = { navController.navigate(ROUTE_ACCOUNTS) },
                )
            }
            composable(ROUTE_GOALS) {
                GoalsScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_SMS_SCAN) {
                SmsScanScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_PERMISSIONS) {
                PermissionsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
