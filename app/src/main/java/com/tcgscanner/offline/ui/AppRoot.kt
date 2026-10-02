package com.tcgscanner.offline.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tcgscanner.offline.ui.nav.Routes
import com.tcgscanner.offline.ui.screens.AboutScreen
import com.tcgscanner.offline.ui.screens.BinderScreen
import com.tcgscanner.offline.ui.screens.CatalogScreen
import com.tcgscanner.offline.ui.screens.CollectionScreen
import com.tcgscanner.offline.ui.screens.DashboardScreen
import com.tcgscanner.offline.ui.screens.DeckListScreen
import com.tcgscanner.offline.ui.screens.DeckScreen
import com.tcgscanner.offline.ui.screens.HubScreen
import com.tcgscanner.offline.ui.screens.ScannerScreen
import com.tcgscanner.offline.ui.screens.SetDetailScreen
import com.tcgscanner.offline.ui.screens.SettingsScreen
import com.tcgscanner.offline.ui.screens.TradeScreen
import com.tcgscanner.offline.ui.screens.ValuableScreen

@Composable
fun AppRoot() {
    val container = LocalContainer.current
    val settings by container.settingsState.collectAsStateWithLifecycle()
    // Wait for the first DataStore read so the hub never flashes an empty state.
    if (settings == null) return

    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }

    NavHost(navController = nav, startDestination = Routes.HUB) {
        composable(Routes.HUB) {
            HubScreen(
                onOpenGame = { nav.navigate(Routes.DASHBOARD) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.DASHBOARD) { DashboardScreen(nav) }
        composable(Routes.COLLECTION) { CollectionScreen(nav) }
        composable(Routes.SCAN) { ScannerScreen(nav) }
        composable(Routes.CATALOG) { CatalogScreen(nav) }
        composable(Routes.BINDER) { BinderScreen(nav) }
        composable(Routes.DECKS) { DeckListScreen(nav, onBack = back) }
        composable(Routes.DECK, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            DeckScreen(deckId = entry.arguments?.getLong("id") ?: 0L, onBack = back)
        }
        composable(Routes.VALUABLE) { ValuableScreen(onBack = back) }
        composable(Routes.SET, arguments = listOf(navArgument("code") { type = NavType.StringType })) { entry ->
            SetDetailScreen(setCode = entry.arguments?.getString("code").orEmpty(), onBack = back)
        }
        composable(Routes.TRADE) { TradeScreen(onBack = back) }
        composable(Routes.SETTINGS) { SettingsScreen(nav, onBack = back) }
        composable(Routes.ABOUT) { AboutScreen(onBack = back) }
    }
}
