@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.tcgscanner.offline.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.tcgscanner.offline.AppContainer
import com.tcgscanner.offline.R
import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.Games
import com.tcgscanner.offline.data.db.CardWithPrices
import com.tcgscanner.offline.data.repo.AddSpec
import com.tcgscanner.offline.data.repo.SetProgress
import com.tcgscanner.offline.ui.appViewModel
import com.tcgscanner.offline.ui.components.AddCardSheet
import com.tcgscanner.offline.ui.components.CardImage
import com.tcgscanner.offline.ui.components.EmptyState
import com.tcgscanner.offline.ui.components.GameScaffold
import com.tcgscanner.offline.ui.currentGameFlow
import com.tcgscanner.offline.ui.nav.Routes
import com.tcgscanner.offline.ui.perGame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CatalogViewModel(private val c: AppContainer) : ViewModel() {
    private val masterMode = kotlinx.coroutines.flow.MutableStateFlow(false)
    val master: StateFlow<Boolean> = masterMode

    @OptIn(ExperimentalCoroutinesApi::class)
    val sets: StateFlow<List<SetProgress>> = c.currentGameFlow().flatMapLatest { id ->
        combine(
            c.catalog.observeSets(id),
            c.db.cards().observeMasterTotals(id.code),
            c.db.collection().observeOwnedBaseBySet(id.code),
            c.db.collection().observeOwnedMasterBySet(id.code),
            masterMode
        ) { sets, masterTotals, ownedBase, ownedMaster, isMaster ->
            val mt = masterTotals.associate { it.code to it.n }
            val ob = ownedBase.associate { it.code to it.n }
            val om = ownedMaster.associate { it.code to it.n }
            sets.map { s ->
                if (isMaster) {
                    val total = mt[s.code] ?: s.total
                    SetProgress(s.code, s.name, total, (om[s.code] ?: 0).coerceAtMost(total), s.releaseDate)
                } else {
                    SetProgress(s.code, s.name, s.total, (ob[s.code] ?: 0).coerceAtMost(s.total), s.releaseDate)
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setMaster(v: Boolean) { masterMode.value = v }

    fun addCustom(game: GameId, name: String, set: String, number: String, usd: Double?) {
        viewModelScope.launch { c.catalog.addCustomCard(game, name, set, number, usd) }
    }
}

@Composable
fun CatalogScreen(nav: NavController) {
    val vm = appViewModel { CatalogViewModel(it) }
    val sets by vm.sets.collectAsStateWithLifecycle()
    val master by vm.master.collectAsStateWithLifecycle()
    val container = com.tcgscanner.offline.ui.LocalContainer.current
    val settings by container.settingsState.collectAsStateWithLifecycle()
    val game = settings?.currentGame?.let { Games[it] }
    var adding by remember { mutableStateOf(false) }

    GameScaffold(
        nav, title = stringResource(R.string.tab_catalog),
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_custom_card))
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !master, onClick = { vm.setMaster(false) }, label = { Text(stringResource(R.string.base_set)) })
                FilterChip(selected = master, onClick = { vm.setMaster(true) }, label = { Text(stringResource(R.string.master_set)) })
            }
            if (sets.isEmpty()) {
                EmptyState(Icons.Filled.GridView, stringResource(R.string.catalog_empty_title), stringResource(R.string.catalog_empty_message))
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(sets, key = { it.code }) { s ->
                        SetRow(s) { nav.navigate(Routes.set(s.code)) }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
    if (adding && game != null) {
        CustomCardDialog(onDismiss = { adding = false }) { name, set, number, usd ->
            vm.addCustom(game.id, name, set, number, usd)
            adding = false
        }
    }
}

@Composable
private fun SetRow(s: SetProgress, onClick: () -> Unit) {
    val complete = s.total > 0 && s.owned >= s.total
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${s.percent}%", style = MaterialTheme.typography.titleMedium, color = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        LinearProgressIndicator(
            progress = { s.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "${s.name} ${s.percent}%" }
        )
        Text("${s.owned} / ${s.total} · ${s.code}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CustomCardDialog(onDismiss: () -> Unit, onConfirm: (name: String, set: String, number: String, usd: Double?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var set by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_custom_card)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.card_name)) }, singleLine = true)
                OutlinedTextField(set, { set = it.take(60) }, label = { Text(stringResource(R.string.set_name)) }, singleLine = true)
                OutlinedTextField(number, { number = it.take(20) }, label = { Text(stringResource(R.string.card_number)) }, singleLine = true)
                OutlinedTextField(
                    price, { price = it.take(10) }, label = { Text(stringResource(R.string.manual_price_optional)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name, set, number, price.replace(',', '.').toDoubleOrNull()) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

// ---------------------------------------------------------------------------------------------------------

data class SetDetailState(
    val cards: List<CardWithPrices> = emptyList(),
    val ownedQty: Map<String, Int> = emptyMap(),
    val ownedVariants: Map<String, Set<String>> = emptyMap(),
    val game: GameDef? = null
)

class SetDetailViewModel(private val c: AppContainer, private val setCode: String) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<SetDetailState> = c.currentGameFlow().flatMapLatest { id ->
        combine(
            c.db.cards().observeSetCards(id.code, setCode),
            c.db.collection().observeOwnedQuantities(id.code),
            c.db.collection().observeOwnedVariants(id.code)
        ) { cards, qty, variants ->
            SetDetailState(
                cards, qty.associate { it.cardId to it.qty },
                variants.groupBy({ it.cardId }, { it.variant }).mapValues { it.value.toSet() },
                Games[id]
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetDetailState())

    fun add(game: GameId, spec: AddSpec) { viewModelScope.launch { c.collection.add(game, spec) } }
}

private enum class OwnFilter(val labelRes: Int) { ALL(R.string.filter_all), MISSING(R.string.filter_missing), OWNED(R.string.filter_owned) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetDetailScreen(setCode: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "set:$setCode") { SetDetailViewModel(it, setCode) }
    val state by vm.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(OwnFilter.ALL) }
    var selected by remember { mutableStateOf<CardWithPrices?>(null) }
    val game = state.game

    val ownedCount = state.cards.count { (state.ownedQty[it.card.id] ?: 0) > 0 }
    val shown = state.cards.filter {
        val owned = (state.ownedQty[it.card.id] ?: 0) > 0
        when (filter) { OwnFilter.ALL -> true; OwnFilter.MISSING -> !owned; OwnFilter.OWNED -> owned }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(state.cards.firstOrNull()?.card?.setName ?: setCode) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } }
        )
    }) { padding ->
        Column(Modifier.padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val frac = if (state.cards.isEmpty()) 0f else ownedCount.toFloat() / state.cards.size
                Text(
                    stringResource(R.string.set_progress, ownedCount, state.cards.size, (frac * 100).toInt()),
                    style = MaterialTheme.typography.titleMedium
                )
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OwnFilter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(stringResource(f.labelRes)) })
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                gridItems(shown, key = { it.card.id }) { cw ->
                    val qty = state.ownedQty[cw.card.id] ?: 0
                    val owned = qty > 0
                    val owningText = stringResource(if (owned) R.string.owned else R.string.missing)
                    Box(
                        Modifier
                            .aspectRatio(0.716f)
                            .clickable { selected = cw }
                            .semantics { contentDescription = "${cw.card.name}, #${cw.card.number}, $owningText" }
                    ) {
                        CardImage(cw.card.imageUrl, cw.card.name, Modifier.matchParentSize(), owned = owned, contentDescription = null)
                        if (owned) {
                            Surface(
                                shape = RoundedCornerShape(topStart = 8.dp, bottomEnd = 6.dp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) { Text("×$qty", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge) }
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                        ) { Text("#${cw.card.number}", Modifier.padding(horizontal = 4.dp), maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        }
    }

    selected?.let { cw ->
        if (game != null) {
            AddCardSheet(
                game = game, card = cw, otherPrints = emptyList(),
                onPickPrint = {}, onDismiss = { selected = null },
                onAdd = { spec -> vm.add(game.id, spec); selected = null }
            )
        }
    }
}
