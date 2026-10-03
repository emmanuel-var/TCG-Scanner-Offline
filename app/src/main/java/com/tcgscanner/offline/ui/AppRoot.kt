package com.tcgscanner.offline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.tcgscanner.offline.ads.BannerAd
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
    val canShowAds by container.ads.canRequestAds.collectAsStateWithLifecycle()

    // The banner sits below the app, never over it: the navigation area is its own box, so no screen content is covered.
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // The banner takes over the bottom system inset, so screens must not pad for it a second time.
                .then(if (canShowAds) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)
        ) {
            AppNavHost(nav, back)
        }
        if (canShowAds) BannerAd(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, back: () -> Unit) {
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
