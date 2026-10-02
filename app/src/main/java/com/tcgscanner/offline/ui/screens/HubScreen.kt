@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.tcgscanner.offline.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tcgscanner.offline.ui.pluralText
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tcgscanner.offline.AppContainer
import com.tcgscanner.offline.R
import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.Games
import com.tcgscanner.offline.data.remote.CatalogUrls
import com.tcgscanner.offline.data.repo.PortfolioSummary
import com.tcgscanner.offline.ui.appViewModel
import com.tcgscanner.offline.ui.components.EmptyState
import com.tcgscanner.offline.ui.components.GameEmblem
import com.tcgscanner.offline.ui.moneyText
import com.tcgscanner.offline.work.SyncScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HubTile(val game: GameDef, val summary: PortfolioSummary, val catalogCards: Int, val needsSource: Boolean = false)

class HubViewModel(private val c: AppContainer) : ViewModel() {
    val active: StateFlow<Set<GameId>> =
        c.settingsState.filterNotNull().map { it.activeGames }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    val tiles: StateFlow<List<HubTile>> = active.flatMapLatest { ids ->
        if (ids.isEmpty()) flowOf(emptyList())
        else combine(ids.sortedBy { it.ordinal }.map { id ->
            combine(c.portfolio.observeSummary(id), c.catalog.observeCount(id), c.settingsState) { s, n, settings ->
                // No built-in URL for the game, none pasted yet and nothing imported: ask the user.
                val needs = n == 0 && CatalogUrls.requiresUserSource(id) && settings?.catalogUrls?.containsKey(id) != true
                HubTile(Games[id], s, n, needs)
            }
        }) { it.toList() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun open(game: GameId, then: () -> Unit) {
        viewModelScope.launch {
            c.settings.setCurrentGame(game)
            then()
        }
    }

    fun setActive(games: Set<GameId>) {
        viewModelScope.launch {
            val before = c.settings.current()
            c.settings.setActiveGames(games)
            // First run for a newly added game: download its catalog in the background (WorkManager).
            val added = games - before.activeGames
            SyncScheduler.enqueueNow(c.app, added, before.wifiOnlySync, manual = false)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(onOpenGame: () -> Unit, onOpenSettings: () -> Unit) {
    val vm = appViewModel { HubViewModel(it) }
    val tiles by vm.tiles.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            )
        }
    ) { padding ->
        if (active.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.SportsEsports,
                title = stringResource(R.string.hub_empty_title),
                message = stringResource(R.string.hub_empty_message),
                modifier = Modifier.padding(padding),
                actionLabel = stringResource(R.string.choose_games),
                onAction = { picking = true }
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.hub_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(tiles, key = { it.game.id.code }) { tile ->
                    GameTile(tile) { vm.open(tile.game.id, onOpenGame) }
                }
                item {
                    AddGameTile { picking = true }
                }
            }
        }
    }

    if (picking) {
        GamePickerDialog(
            selected = active,
            onDismiss = { picking = false },
            onConfirm = { vm.setActive(it); picking = false }
        )
    }
}

@Composable
private fun GameTile(tile: HubTile, onClick: () -> Unit) {
    val name = stringResource(tile.game.collectionNameRes)
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GameEmblem(tile.game, 56.dp)
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text(moneyText(tile.summary.totalUsd), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                pluralText(R.plurals.cards_count, tile.summary.copies),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (tile.needsSource) {
                Text(stringResource(R.string.needs_source_short), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
            } else if (tile.catalogCards == 0) {
                Text(stringResource(R.string.not_synced), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun AddGameTile(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.manage_games), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun GamePickerDialog(selected: Set<GameId>, onDismiss: () -> Unit, onConfirm: (Set<GameId>) -> Unit) {
    var chosen by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_games)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Games.all.forEach { g ->
                    val checked = g.id in chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Checkbox) { chosen = if (checked) chosen - g.id else chosen + g.id }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        GameEmblem(g, 32.dp)
                        Text(stringResource(g.nameRes), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(chosen) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
