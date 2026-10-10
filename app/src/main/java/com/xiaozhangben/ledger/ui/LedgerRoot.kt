package com.xiaozhangben.ledger.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xiaozhangben.ledger.R
import com.xiaozhangben.ledger.ui.add.AddTransactionSheet
import com.xiaozhangben.ledger.ui.daily.DailyScreen
import com.xiaozhangben.ledger.ui.home.HomeScreen
import com.xiaozhangben.ledger.ui.imports.ImportScreen
import com.xiaozhangben.ledger.ui.plan.PlanScreen
import com.xiaozhangben.ledger.ui.settings.CategoriesScreen
import com.xiaozhangben.ledger.ui.settings.RulesScreen
import com.xiaozhangben.ledger.ui.settings.SettingsScreen
import com.xiaozhangben.ledger.ui.stats.StatsScreen
import com.xiaozhangben.ledger.ui.transactions.TransactionsScreen
import com.xiaozhangben.ledger.ui.theme.LedgerType
import com.xiaozhangben.ledger.ui.theme.Motion
import com.xiaozhangben.ledger.ui.theme.rememberAnimationsEnabled

object Routes {
    const val HOME = "home"
    const val DAILY = "daily"
    const val TRANSACTIONS = "transactions"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val PLAN = "plan"
    const val IMPORT = "import"
    const val CATEGORIES = "categories"
    const val RULES = "rules"

    val topLevel = setOf(HOME, DAILY, TRANSACTIONS, STATS, SETTINGS)

    fun title(route: String): String = when (route) {
        PLAN -> "预算与每日类别"
        IMPORT -> "导入账单"
        CATEGORIES -> "分类管理"
        RULES -> "归类规则"
        else -> ""
    }
}

private enum class Tab(val route: String, val labelRes: Int, val icon: ImageVector) {
    HOME(Routes.HOME, R.string.tab_home, Icons.Outlined.Home),
    DAILY(Routes.DAILY, R.string.tab_daily, Icons.Outlined.DateRange),
    TRANSACTIONS(Routes.TRANSACTIONS, R.string.tab_transactions, Icons.AutoMirrored.Outlined.ReceiptLong),
    STATS(Routes.STATS, R.string.tab_stats, Icons.Outlined.BarChart),
    SETTINGS(Routes.SETTINGS, R.string.tab_settings, Icons.Outlined.Person),
}

/**
 * App shell: four tabs, a prominent "记一笔" action, and the sub-screens that hang off
 * them.
 *
 * Transitions are a short fade with a small vertical offset rather than a full page
 * slide. A whole-screen slide reads as sluggish for a tool that gets opened dozens of
 * times a day, and the brief explicitly asks for short, fluid transitions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerRoot(
    initialTransactionId: String? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route ?: Routes.HOME
    val isTopLevel = route in Routes.topLevel

    val animationsEnabled = rememberAnimationsEnabled()
    var showAddSheet by rememberSaveable { mutableStateOf(false) }

    // When the sheet is opened from a meal card on the 每日 page, the meal and its
    // category come pre-filled — the whole point is recording lunch from the lunch card.
    var presetSlotId by rememberSaveable { mutableStateOf<String?>(null) }
    var presetCategoryKey by rememberSaveable { mutableStateOf<String?>(null) }

    fun openAddSheet(slotId: String? = null, categoryKey: String? = null) {
        presetSlotId = slotId
        presetCategoryKey = categoryKey
        showAddSheet = true
    }

    // Tapping the capture notification lands on the transaction list; the row itself is
    // opened from there, which is also the fastest path to correcting its category.
    //
    // Consumed immediately: a deep link left pending would re-navigate on every later
    // recomposition or activity recreation and would fight the user's own tab taps.
    LaunchedEffect(initialTransactionId) {
        if (initialTransactionId != null) {
            navController.navigate(Routes.TRANSACTIONS) { launchSingleTop = true }
            onDeepLinkHandled()
        }
    }

    Scaffold(
        topBar = {
            if (!isTopLevel) {
                TopAppBar(
                    title = { Text(Routes.title(route), style = LedgerType.sectionTitle) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
        bottomBar = {
            if (isTopLevel) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                if (route != tab.route) {
                                    // Deliberately NO saveState/restoreState here.
                                    //
                                    // The "canonical" bottom-nav snippet
                                    // (popUpTo(startDestination) { saveState = true } +
                                    // restoreState = true) made the start destination
                                    // unreachable: once another tab had been visited,
                                    // tapping 首页 restored a stale saved back stack and
                                    // landed on 流水 instead. The user reported it as
                                    // "首页和流水是一个页面".
                                    //
                                    // These five tabs hold short lists, so losing
                                    // per-tab scroll position is a fair price for a tab
                                    // bar that always does exactly what its label says.
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id)
                                        launchSingleTop = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (isTopLevel) {
                FloatingActionButton(onClick = { openAddSheet() }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add_transaction))
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                if (animationsEnabled) {
                    fadeIn(tween(Motion.MEDIUM)) + slideInVertically(tween(Motion.MEDIUM)) { it / 28 }
                } else {
                    fadeIn(tween(0))
                }
            },
            exitTransition = {
                if (animationsEnabled) fadeOut(tween(Motion.SHORT)) else fadeOut(tween(0))
            },
            popEnterTransition = {
                if (animationsEnabled) fadeIn(tween(Motion.MEDIUM)) else fadeIn(tween(0))
            },
            popExitTransition = {
                if (animationsEnabled) {
                    fadeOut(tween(Motion.SHORT)) + slideOutVertically(tween(Motion.SHORT)) { it / 28 }
                } else {
                    fadeOut(tween(0))
                }
            },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenPlan = { navController.navigate(Routes.PLAN) },
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                    onOpenTriage = { navController.navigate(Routes.TRANSACTIONS) },
                    onOpenDiagnostics = { navController.navigate(Routes.SETTINGS) },
                    onAddTransaction = { openAddSheet() },
                )
            }
            composable(Routes.DAILY) {
                DailyScreen(
                    onAddForSlot = { slotId, categoryKey -> openAddSheet(slotId, categoryKey) },
                    onOpenPlan = { navController.navigate(Routes.PLAN) },
                )
            }
            composable(Routes.TRANSACTIONS) { backStackEntry ->
                TransactionsScreen(
                    onAddTransaction = { openAddSheet() },
                    focusTransactionId = backStackEntry.arguments?.getString("txnId"),
                )
            }
            composable(Routes.STATS) { StatsScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenCategories = { navController.navigate(Routes.CATEGORIES) },
                    onOpenRules = { navController.navigate(Routes.RULES) },
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                )
            }
            composable(Routes.PLAN) { PlanScreen() }
            composable(Routes.IMPORT) { ImportScreen() }
            composable(Routes.CATEGORIES) { CategoriesScreen() }
            composable(Routes.RULES) { RulesScreen() }
        }
    }

    if (showAddSheet) {
        AddTransactionSheet(
            onDismiss = {
                showAddSheet = false
                presetSlotId = null
                presetCategoryKey = null
            },
            onSaved = {
                showAddSheet = false
                presetSlotId = null
                presetCategoryKey = null
                if (route != Routes.TRANSACTIONS) {
                    navController.navigate(Routes.TRANSACTIONS) { launchSingleTop = true }
                }
            },
            presetCategoryKey = presetCategoryKey,
            presetSlotId = presetSlotId,
        )
    }
}

/** Kept for the (currently unused) deep-link entry; navigation-compose would own this. */
internal fun NavHostController.navigateToTransaction(txnId: String) {
    navigate(Routes.TRANSACTIONS) { launchSingleTop = true }
}
