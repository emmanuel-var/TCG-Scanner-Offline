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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.tcgscanner.offline.AppContainer
import com.tcgscanner.offline.R
import com.tcgscanner.offline.core.CardCondition
import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.core.GradingCompany
import com.tcgscanner.offline.core.Money
import com.tcgscanner.offline.data.repo.CollectionEntry
import com.tcgscanner.offline.ui.appViewModel
import com.tcgscanner.offline.ui.components.CardImage
import com.tcgscanner.offline.ui.components.EmptyState
import com.tcgscanner.offline.ui.components.GameScaffold
import com.tcgscanner.offline.ui.currentGameFlow
import com.tcgscanner.offline.ui.gradeLabel
import com.tcgscanner.offline.ui.moneyText
import com.tcgscanner.offline.ui.perGame
import com.tcgscanner.offline.ui.unitText
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CollectionViewModel(private val c: AppContainer) : ViewModel() {
    val entries: StateFlow<List<CollectionEntry>> = c.perGame { c.portfolio.observeEntries(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuantity(id: Long, q: Int) = viewModelScope.launch { c.collection.setQuantity(id, q) }
    fun setTrade(id: Long, q: Int) = viewModelScope.launch { c.collection.setTradeQuantity(id, q) }
    fun setPrice(id: Long, usd: Double?) = viewModelScope.launch { c.collection.setManualPrice(id, usd) }
    fun delete(id: Long) = viewModelScope.launch { c.collection.delete(id) }
}

private enum class Filter(val labelRes: Int) { ALL(R.string.filter_all), RAW(R.string.raw), GRADED(R.string.graded_slab) }

@Composable
fun CollectionScreen(nav: NavController) {
    val vm = appViewModel { CollectionViewModel(it) }
    val entries by vm.entries.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var editing by remember { mutableStateOf<CollectionEntry?>(null) }

    val shown = entries.filter {
        when (filter) { Filter.ALL -> true; Filter.RAW -> !it.isGraded; Filter.GRADED -> it.isGraded }
    }
    GameScaffold(nav, title = stringResource(R.string.tab_collection)) { padding ->
        Column(Modifier.padding(padding)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(stringResource(f.labelRes)) })
                }
            }
            if (shown.isEmpty()) {
                EmptyState(Icons.Filled.Folder, stringResource(R.string.collection_empty_title), stringResource(R.string.collection_empty_message))
            } else {
                Text(
                    stringResource(R.string.collection_total, moneyText(shown.sumOf { it.totalUsd })),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.titleMedium
                )
                EntryList(shown, onClick = { editing = it })
            }
        }
    }
    editing?.let { e ->
        EntryEditDialog(
            entry = e,
            onDismiss = { editing = null },
            onSave = { qty, trade, price ->
                vm.setQuantity(e.item.id, qty)
                if (qty > 0) { vm.setTrade(e.item.id, trade); vm.setPrice(e.item.id, price) }
                editing = null
            },
            onDelete = { vm.delete(e.item.id); editing = null }
        )
    }
}

@Composable
fun EntryList(entries: List<CollectionEntry>, onClick: (CollectionEntry) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        items(entries, key = { it.item.id }) { e ->
            EntryRow(e, onClick = { onClick(e) })
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
fun EntryRow(e: CollectionEntry, onClick: () -> Unit) {
    val card = e.card
    // If the card row is missing (source changed, not re-linked yet) show the snapshot saved with the item.
    val name = card?.name ?: e.item.cardName.ifBlank { e.item.cardId }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CardImage(card?.imageUrl, name, Modifier.size(width = 48.dp, height = 67.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                card?.let { "${it.setName} · #${it.number}" }
                    ?: if (e.item.setName.isNotBlank()) "${e.item.setName} · #${e.item.cardNumber}" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val company = GradingCompany.fromCode(e.item.gradeCompany)
            val variantLabel = stringResource(CardVariant.fromCode(e.item.variant).labelRes)
            val second = if (company.isGraded) gradeLabel(company, e.item.gradeX10) else stringResource(CardCondition.fromCode(e.item.condition).labelRes)
            val detail = "$variantLabel · $second"
            Text(detail, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(unitText(e.unit), style = MaterialTheme.typography.titleMedium)
            Text(
                "×${e.item.quantity}" + if (e.item.tradeQuantity > 0) " · ⇄${e.item.tradeQuantity}" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun EntryEditDialog(
    entry: CollectionEntry,
    onDismiss: () -> Unit,
    onSave: (qty: Int, trade: Int, price: Double?) -> Unit,
    onDelete: () -> Unit
) {
    var qty by remember { mutableIntStateOf(entry.item.quantity) }
    var trade by remember { mutableIntStateOf(entry.item.tradeQuantity) }
    var price by remember { mutableStateOf(entry.item.manualPriceUsd?.let { Money.plain(it) }.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.card?.name ?: entry.item.cardId, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stepper(stringResource(R.string.quantity), qty, 0, 999) { qty = it; trade = trade.coerceAtMost(it) }
                Stepper(stringResource(R.string.in_trade_binder), trade, 0, qty) { trade = it }
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.take(10) },
                    label = { Text(stringResource(R.string.manual_price_optional)) },
                    supportingText = { Text(stringResource(R.string.manual_price_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                TextButton(onClick = onDelete) { Text(stringResource(R.string.remove_from_collection), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(qty, trade, price.replace(',', '.').toDoubleOrNull()) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }, enabled = value > min) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.decrease))
        }
        Text(value.toString(), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onChange((value + 1).coerceAtMost(max)) }, enabled = value < max) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.increase))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ValuableScreen(onBack: () -> Unit) {
    val vm = appViewModel { CollectionViewModel(it) }
    val entries by vm.entries.collectAsStateWithLifecycle()
    val top = entries.sortedByDescending { it.unit.usd }.take(50)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.most_valuable)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } }
        )
    }) { padding ->
        if (top.isEmpty()) {
            EmptyState(Icons.Filled.Star, stringResource(R.string.collection_empty_title), stringResource(R.string.collection_empty_message), Modifier.padding(padding))
        } else {
            EntryList(top, onClick = {}, modifier = Modifier.padding(padding))
        }
    }
}
