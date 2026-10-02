@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.tcgscanner.offline.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf as stateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.tcgscanner.offline.AppContainer
import com.tcgscanner.offline.R
import com.tcgscanner.offline.core.CardCategory
import com.tcgscanner.offline.core.DeckZone
import com.tcgscanner.offline.core.Text as TextUtil
import com.tcgscanner.offline.data.db.CardWithPrices
import com.tcgscanner.offline.data.db.DeckEntity
import com.tcgscanner.offline.data.repo.DeckLine
import com.tcgscanner.offline.data.repo.DeckView
import com.tcgscanner.offline.ui.appViewModel
import com.tcgscanner.offline.ui.components.CardImage
import com.tcgscanner.offline.ui.components.EmptyState
import com.tcgscanner.offline.ui.currentGameFlow
import com.tcgscanner.offline.ui.moneyText
import com.tcgscanner.offline.ui.nav.Routes
import com.tcgscanner.offline.ui.perGame
import com.tcgscanner.offline.ui.theme.Semantic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DeckListViewModel(private val c: AppContainer) : ViewModel() {
    val decks: StateFlow<List<DeckEntity>> = c.perGame { c.decks.observeDecks(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sizes: StateFlow<Map<Long, Int>> = c.decks.observeSizes()
        .map { list -> list.associate { it.deckId to it.n } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun create(name: String, then: (Long) -> Unit) {
        viewModelScope.launch {
            val game = c.settingsState.value?.currentGame ?: return@launch
            then(c.decks.create(game, name))
        }
    }

    fun delete(id: Long) { viewModelScope.launch { c.decks.delete(id) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckListScreen(nav: NavController, onBack: () -> Unit) {
    val vm = appViewModel { DeckListViewModel(it) }
    val decks by vm.decks.collectAsStateWithLifecycle()
    val sizes by vm.sizes.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DeckEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.my_decks)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_deck)) }
        }
    ) { padding ->
        if (decks.isEmpty()) {
            EmptyState(Icons.Filled.Layers, stringResource(R.string.decks_empty_title), stringResource(R.string.decks_empty_message), Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(decks, key = { it.id }) { d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav.navigate(Routes.deck(d.id)) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.deck_cards_count, sizes[d.id] ?: 0), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { deleting = d }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_deck)) }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.new_deck)) },
            text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text(stringResource(R.string.deck_name)) }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { creating = false; vm.create(name) { nav.navigate(Routes.deck(it)) } }) { Text(stringResource(R.string.create)) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_deck)) },
            text = { Text(stringResource(R.string.delete_deck_confirm, d.name)) },
            confirmButton = { TextButton(onClick = { vm.delete(d.id); deleting = null }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

// ---------------------------------------------------------------------------------------------------------

class DeckViewModel(private val c: AppContainer, private val deckId: Long) : ViewModel() {
    val deck: StateFlow<DeckView?> = c.decks.observeDeck(deckId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val query = MutableStateFlow("")
    fun setQuery(q: String) { query.value = q }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<CardWithPrices>> = combine(c.currentGameFlow(), query.debounce(200)) { g, q -> g to q }
        .flatMapLatest { (g, q) ->
            val key = TextUtil.nameKey(q)
            if (key.length < 2) flowOf(emptyList()) else flow {
                val ids = c.db.cards().search(g.code, key, 30).map { it.id }
                val byId = c.db.cards().getManyWithPrices(ids).associateBy { it.card.id }
                emit(ids.mapNotNull { byId[it] })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun change(cardId: String, zone: DeckZone, delta: Int) { viewModelScope.launch { c.decks.changeQuantity(deckId, cardId, zone, delta) } }
    fun move(cardId: String, from: DeckZone, to: DeckZone) { viewModelScope.launch { c.decks.moveZone(deckId, cardId, from, to) } }
    fun rename(deck: DeckEntity, name: String) { viewModelScope.launch { c.decks.rename(deck, name) } }
}

private const val ADD = "add:"
private const val MOVE = "move:"

@Composable
fun DeckScreen(deckId: Long, onBack: () -> Unit) {
    val vm = appViewModel(key = "deck:$deckId") { DeckViewModel(it, deckId) }
    val view by vm.deck.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(view?.deck?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            actions = { TextButton(onClick = { renaming = true }) { Text(stringResource(R.string.rename)) } }
        )
    }) { padding ->
        val v = view
        val drag = remember(deckId) { DeckDragState { payload, zone -> handleDrop(vm, payload, zone) } }
        var rootOffset by remember { stateOf(Offset.Zero) }
        androidx.compose.runtime.CompositionLocalProvider(LocalDeckDrag provides drag) {
        Box(Modifier.padding(padding).fillMaxSize().onGloballyPositioned { rootOffset = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            if (v != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.deck_total, v.totalCards), style = MaterialTheme.typography.titleMedium)
                    if (v.wishlist.isNotEmpty()) {
                        Text(
                            stringResource(R.string.deck_wishlist_summary, v.wishlist.sumOf { it.missing }, moneyText(v.wishlistUsd)),
                            style = MaterialTheme.typography.bodyMedium, color = Semantic.loss
                        )
                    }
                    Text(stringResource(R.string.drag_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ---- deck contents (drop target: main deck) -------------------------------------------------
            val hovered = if (drag.payload != null) drag.zoneAt(drag.position) else null

            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .dropZone(drag, DeckZone.MAIN)
                    .then(if (hovered == DeckZone.MAIN) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                val grouped = v?.grouped.orEmpty()
                DeckZone.entries.forEach { zone ->
                    val categories = grouped[zone].orEmpty()
                    item(key = "zone-${zone.code}") {
                        ZoneHeader(zone, categories.values.sumOf { l -> l.sumOf { it.needed } }, if (zone == DeckZone.SIDE) Modifier.dropZone(drag, DeckZone.SIDE).then(if (hovered == DeckZone.SIDE) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier) else Modifier)
                    }
                    categories.forEach { (cat, lines) ->
                        item(key = "cat-${zone.code}-${cat.code}") {
                            Text(
                                stringResource(cat.labelRes) + " (${lines.sumOf { it.needed }})",
                                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary
                            )
                        }
                        items(lines, key = { "${zone.code}-${it.card.id}" }) { line ->
                            DeckLineRow(line, onPlus = { vm.change(line.card.id, zone, 1) }, onMinus = { vm.change(line.card.id, zone, -1) }, onMove = { vm.move(line.card.id, zone, if (zone == DeckZone.MAIN) DeckZone.SIDE else DeckZone.MAIN) })
                        }
                    }
                }
            }

            HorizontalDivider()
            // ---- card search (drag results into the deck, or tap +) -----------------------------------------
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query, { query = it; vm.setQuery(it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.search_hint)) }
                )
                if (results.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(results, key = { it.card.id }) { cw -> SearchTile(cw, onAdd = { vm.change(cw.card.id, DeckZone.MAIN, 1) }) }
                    }
                }
            }
        }
        // Floating chip that follows the finger while a card is being dragged.
        drag.payload?.let {
            val hoverLabel = drag.zoneAt(drag.position)?.let { z -> stringResource(z.labelRes) } ?: "…"
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 6.dp,
                modifier = Modifier.offset { IntOffset((drag.position.x - rootOffset.x).toInt() - 40, (drag.position.y - rootOffset.y).toInt() - 90) }
            ) {
                Text("+ $hoverLabel", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
            }
        }
        }
        }
    }

    if (renaming && view != null) {
        var name by remember { mutableStateOf(view!!.deck.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(view!!.deck, name); renaming = false }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

private fun handleDrop(vm: DeckViewModel, payload: String, zone: DeckZone) {
    when {
        payload.startsWith(ADD) -> vm.change(payload.removePrefix(ADD), zone, 1)
        payload.startsWith(MOVE) -> {
            val parts = payload.removePrefix(MOVE).split('|')
            if (parts.size == 2) vm.move(parts[0], DeckZone.fromCode(parts[1]), zone)
        }
    }
}

/**
 * Drag and drop for the deck builder, built on plain pointer gestures (long-press, then drag) so it does not depend on
 * the experimental Compose drag-and-drop API, whose signature changes between Compose versions. Drop zones register
 * their on-screen bounds; the state tracks the finger in root coordinates and resolves the zone on release.
 */
private class DeckDragState(private val onDrop: (payload: String, zone: DeckZone) -> Unit) {
    var payload by stateOf<String?>(null)
    var position by stateOf(Offset.Zero)
    val zones = HashMap<DeckZone, Rect>()

    /** The side-board header is checked first because it sits inside the main list's bounds. */
    fun zoneAt(point: Offset): DeckZone? = when {
        zones[DeckZone.SIDE]?.contains(point) == true -> DeckZone.SIDE
        zones[DeckZone.MAIN]?.contains(point) == true -> DeckZone.MAIN
        else -> null
    }

    fun finish() {
        val p = payload
        val zone = zoneAt(position)
        payload = null
        if (p != null && zone != null) onDrop(p, zone)
    }

    fun cancel() { payload = null }
}

private val LocalDeckDrag = compositionLocalOf<DeckDragState> { error("DeckDragState not provided") }

private fun Modifier.dragSource(state: DeckDragState, payload: String): Modifier = composed {
    var origin by remember { stateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(payload) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset -> state.payload = payload; state.position = origin + offset },
                onDrag = { change, amount -> change.consume(); state.position += amount },
                onDragEnd = { state.finish() },
                onDragCancel = { state.cancel() }
            )
        }
}

private fun Modifier.dropZone(state: DeckDragState, zone: DeckZone): Modifier =
    onGloballyPositioned { state.zones[zone] = it.boundsInRoot() }

@Composable
private fun ZoneHeader(zone: DeckZone, count: Int, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Text(
            stringResource(zone.labelRes) + " · $count",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun DeckLineRow(line: DeckLine, onPlus: () -> Unit, onMinus: () -> Unit, onMove: () -> Unit) {
    val missing = line.missing > 0
    val drag = LocalDeckDrag.current
    val warn = Semantic.loss
    Row(
        Modifier
            .fillMaxWidth()
            .dragSource(drag, "$MOVE${line.card.id}|${line.zone.code}")
            .background(if (missing) warn.copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CardImage(line.card.imageUrl, line.card.name, Modifier.size(width = 36.dp, height = 50.dp))
        Column(Modifier.weight(1f)) {
            Text(line.card.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            if (missing) {
                // Red + text: colour is never the only signal.
                Text(
                    stringResource(R.string.wishlist_missing, line.missing, moneyText(line.missingUsd)),
                    color = warn, style = MaterialTheme.typography.labelLarge
                )
            } else {
                Text(stringResource(R.string.deck_owned, line.owned), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onMinus) { Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.decrease)) }
        Text(line.needed.toString(), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onPlus) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.increase)) }
        IconButton(onClick = onMove) { Icon(Icons.Filled.SwapVert, contentDescription = stringResource(R.string.move_zone)) }
    }
}

@Composable
private fun SearchTile(cw: CardWithPrices, onAdd: () -> Unit) {
    val drag = LocalDeckDrag.current
    Column(
        Modifier
            .width(84.dp)
            .dragSource(drag, "$ADD${cw.card.id}")
            .clickable(onClick = onAdd)
            .semantics { contentDescription = "${cw.card.name}, ${cw.card.setName}" },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CardImage(cw.card.imageUrl, cw.card.name, Modifier.size(width = 84.dp, height = 117.dp))
        Text(cw.card.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
    }
}
