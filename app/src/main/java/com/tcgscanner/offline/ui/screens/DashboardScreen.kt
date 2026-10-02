@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.tcgscanner.offline.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tcgscanner.offline.ui.pluralText
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.navigation.NavController
import com.tcgscanner.offline.AppContainer
import com.tcgscanner.offline.R
import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.Games
import com.tcgscanner.offline.data.db.PortfolioSnapshotEntity
import com.tcgscanner.offline.data.remote.CatalogUrls
import com.tcgscanner.offline.data.repo.PortfolioSummary
import com.tcgscanner.offline.data.repo.SyncUiState
import com.tcgscanner.offline.ui.appViewModel
import com.tcgscanner.offline.ui.components.ChartPoint
import com.tcgscanner.offline.ui.components.GameScaffold
import com.tcgscanner.offline.ui.components.ValueChart
import com.tcgscanner.offline.ui.currentGameFlow
import com.tcgscanner.offline.ui.dateTime
import com.tcgscanner.offline.ui.moneyText
import com.tcgscanner.offline.ui.nav.Routes
import com.tcgscanner.offline.ui.relativeTime
import com.tcgscanner.offline.ui.theme.Semantic
import com.tcgscanner.offline.work.SyncScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.math.abs

data class DashboardState(
    val game: GameDef? = null,
    val summary: PortfolioSummary = PortfolioSummary(),
    val snapshots: List<PortfolioSnapshotEntity> = emptyList(),
    val lastSync: Long? = null,
    val catalogCards: Int = 0,
    /** No built-in URL for this game, no pasted URL and no imported catalog yet. */
    val needsSource: Boolean = false
)

class DashboardViewModel(private val c: AppContainer) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<DashboardState> = c.currentGameFlow().flatMapLatest { id ->
        combine(
            c.portfolio.observeSummary(id), c.portfolio.observeSnapshots(id),
            c.catalog.observeLastSync(id), c.catalog.observeCount(id), c.settingsState
        ) { summary, snaps, last, count, settings ->
            val needs = count == 0 && CatalogUrls.requiresUserSource(id) && settings?.catalogUrls?.containsKey(id) != true
            DashboardState(Games[id], summary, snaps, last, count, needs)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    val sync: StateFlow<SyncUiState> = c.sync.state

    private val _messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: kotlinx.coroutines.flow.SharedFlow<String> = _messages

    /** "Import a catalog JSON" from the activation prompt; works offline and accepts any card-shaped JSON. */
    fun importCatalog(context: android.content.Context, uri: android.net.Uri, game: GameDef) {
        viewModelScope.launch {
            val n = runCatching {
                val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                c.catalog.importCatalogJson(game, text)
            }
            _messages.emit(
                if (n.getOrDefault(0) > 0) context.getString(R.string.import_done, n.getOrDefault(0)) else context.getString(R.string.import_failed)
            )
        }
    }

    fun syncNow(game: GameId) = SyncScheduler.enqueueNow(c.app, listOf(game), wifiOnly = false, manual = true)
}

private enum class Range(val labelRes: Int, val days: Int?) {
    WEEK(R.string.range_7d, 7), MONTH(R.string.range_30d, 30), QUARTER(R.string.range_90d, 90), YEAR(R.string.range_1y, 365), ALL(R.string.range_all, null)
}

@Composable
fun DashboardScreen(nav: NavController) {
    val vm = appViewModel { DashboardViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val game = state.game
    var range by rememberSaveable { mutableStateOf(Range.MONTH) }
    var scrubbed by remember { mutableStateOf<ChartPoint?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null && game != null) vm.importCatalog(context, uri, game) }

    GameScaffold(
        nav, title = game?.let { stringResource(it.collectionNameRes) } ?: stringResource(R.string.app_name), snackbarHost = snackbar
    ) { padding ->
        if (game == null) return@GameScaffold
        // Chart series: stored snapshots plus the live value, so the line always ends "now".
        val now = System.currentTimeMillis()
        val all = state.snapshots.map { ChartPoint(it.timestamp, it.totalUsd) }.toMutableList()
        if (all.isEmpty() || abs(all.last().value - state.summary.totalUsd) > 0.004) {
            if (state.summary.copies > 0 || all.isNotEmpty()) all.add(ChartPoint(now, state.summary.totalUsd))
        }
        val cutoff = range.days?.let { now - it * 86_400_000L }
        val visible = if (cutoff == null) all else {
            val inside = all.filter { it.timeMs >= cutoff }
            val before = all.lastOrNull { it.timeMs < cutoff }
            if (before != null) listOf(before.copy(timeMs = cutoff)) + inside else inside
        }
        val shown = scrubbed ?: visible.lastOrNull()
        val first = visible.firstOrNull()
        val delta = if (shown != null && first != null) shown.value - first.value else 0.0
        val pct = if (first != null && first.value > 0) delta / first.value * 100.0 else 0.0
        val up = delta >= 0

        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text(stringResource(R.string.portfolio_value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(moneyText(shown?.value ?: state.summary.totalUsd), style = MaterialTheme.typography.headlineLarge)
                if (first != null && visible.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            if (up) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                            contentDescription = null,
                            tint = if (up) Semantic.gain else Semantic.loss
                        )
                        Text(
                            (if (up) "+" else "−") + moneyText(abs(delta)) + String.format(java.util.Locale.US, " (%+.1f%%)", pct),
                            color = if (up) Semantic.gain else Semantic.loss,
                            style = MaterialTheme.typography.titleMedium
                        )
                        scrubbed?.let { Text("· " + dateTime(it.timeMs), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }

            if (visible.size >= 2) {
                ValueChart(
                    points = visible,
                    lineColor = if (up) Semantic.gain else Semantic.loss,
                    gridColor = MaterialTheme.colorScheme.outlineVariant,
                    description = stringResource(R.string.chart_description, moneyText(state.summary.totalUsd)),
                    onSelect = { scrubbed = it }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Range.entries.forEach { r ->
                        FilterChip(selected = range == r, onClick = { range = r }, label = { Text(stringResource(r.labelRes)) })
                    }
                }
            } else {
                Text(stringResource(R.string.chart_not_enough), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(stringResource(R.string.raw), moneyText(state.summary.rawUsd), Modifier.weight(1f))
                StatCard(stringResource(R.string.graded_slab), moneyText(state.summary.gradedUsd), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(stringResource(R.string.copies), state.summary.copies.toString(), Modifier.weight(1f))
                StatCard(stringResource(R.string.unique_cards), state.summary.uniqueCards.toString(), Modifier.weight(1f))
            }
            if (state.summary.estimatedEntries > 0 || state.summary.unpricedEntries > 0) {
                Text(
                    stringResource(R.string.value_notes, state.summary.estimatedEntries, state.summary.unpricedEntries),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.needsSource) {
                NeedsSourceCard(
                    onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    onSettings = { nav.navigate(Routes.SETTINGS) }
                )
            } else {
                SyncCard(state.lastSync, state.catalogCards, sync, game) { vm.syncNow(game.id) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { nav.navigate(Routes.DECKS) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Layers, contentDescription = null)
                    Text(stringResource(R.string.my_decks), modifier = Modifier.padding(start = 8.dp))
                }
                FilledTonalButton(onClick = { nav.navigate(Routes.VALUABLE) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Star, contentDescription = null)
                    Text(stringResource(R.string.most_valuable), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun SyncCard(lastSync: Long?, catalogCards: Int, sync: SyncUiState, game: GameDef, onSync: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.last_price_sync), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (lastSync != null) relativeTime(lastSync) else stringResource(R.string.never_synced),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (catalogCards > 0) {
                        Text(
                            pluralText(R.plurals.catalog_offline_cards, catalogCards),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                val busy = sync.running
                IconButton(onClick = onSync, enabled = !busy) {
                    Icon(if (lastSync == null) Icons.Filled.CloudDownload else Icons.Filled.Sync, contentDescription = stringResource(R.string.sync_prices))
                }
            }
            if (sync.running && sync.currentGame == game.id) {
                if (sync.fraction != null) LinearProgressIndicator(progress = { sync.fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (sync.message.isNotBlank()) Text(sync.message, style = MaterialTheme.typography.bodyMedium)
            }
            val result = sync.results[game.id]
            if (!sync.running && result != null && !result.success && result.error != com.tcgscanner.offline.data.repo.SyncResult.NO_SOURCE) {
                Text(
                    stringResource(R.string.sync_failed, result.error.orEmpty()),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium
                )
            }
            if (catalogCards == 0 && !sync.running) {
                Button(onClick = onSync) { Text(stringResource(R.string.download_catalog)) }
            }
        }
    }
}

/** Initial state of games that have no stable public catalog: invite the user to provide one. */
@Composable
private fun NeedsSourceCard(onImport: () -> Unit, onSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.needs_source_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.needs_source_message), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport) { Text(stringResource(R.string.import_catalog_file)) }
                androidx.compose.material3.OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.open_settings)) }
            }
        }
    }
}
