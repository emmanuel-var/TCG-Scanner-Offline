@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.tcgscanner.offline.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.tcgscanner.offline.ui.pluralText
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.tcgscanner.offline.AppContainer
import com.tcgscanner.offline.R
import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.core.GradingCompany
import com.tcgscanner.offline.data.repo.CollectionEntry
import com.tcgscanner.offline.ui.appViewModel
import com.tcgscanner.offline.ui.components.CardImage
import com.tcgscanner.offline.ui.components.EmptyState
import com.tcgscanner.offline.ui.components.GameScaffold
import com.tcgscanner.offline.ui.gradeLabel
import com.tcgscanner.offline.ui.moneyText
import com.tcgscanner.offline.ui.nav.Routes
import com.tcgscanner.offline.ui.perGame
import com.tcgscanner.offline.ui.unitText
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BinderViewModel(private val c: AppContainer) : ViewModel() {
    val items: StateFlow<List<CollectionEntry>> = c.perGame { c.portfolio.observeEntries(it) }
        .map { list -> list.filter { it.item.tradeQuantity > 0 }.sortedByDescending { it.tradeUsd } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setTrade(id: Long, qty: Int) = viewModelScope.launch { c.collection.setTradeQuantity(id, qty) }
}

/** Trade Binder: the cards set aside for trading, shown as a carousel with the running total. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BinderScreen(nav: NavController) {
    val vm = appViewModel { BinderViewModel(it) }
    val items by vm.items.collectAsStateWithLifecycle()

    GameScaffold(nav, title = stringResource(R.string.tab_binder)) { padding ->
        if (items.isEmpty()) {
            EmptyState(
                Icons.Filled.Inventory2, stringResource(R.string.binder_empty_title), stringResource(R.string.binder_empty_message),
                Modifier.padding(padding)
            )
            return@GameScaffold
        }
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.binder_total), style = MaterialTheme.typography.labelLarge)
                    Text(moneyText(items.sumOf { it.tradeUsd }), style = MaterialTheme.typography.headlineLarge)
                    val copies = items.sumOf { it.item.tradeQuantity }
                    Text(pluralText(R.plurals.cards_count, copies), style = MaterialTheme.typography.bodyMedium)
                }
            }

            val pager = rememberPagerState { items.size }
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = 64.dp),
                pageSpacing = 16.dp,
                key = { items[it].item.id },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                BinderPage(items[page], onQty = { vm.setTrade(items[page].item.id, it) })
            }
            Text(
                "${pager.currentPage + 1} / ${items.size}",
                Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge
            )
            FilledTonalButton(
                onClick = { nav.navigate(Routes.TRADE) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
            ) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = null)
                Text(stringResource(R.string.trade_nearby), Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun BinderPage(e: CollectionEntry, onQty: (Int) -> Unit) {
    val card = e.card
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CardImage(card?.imageUrl, card?.name ?: e.item.cardId, Modifier.weight(1f))
            Text(card?.name ?: e.item.cardId, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2)
            val company = GradingCompany.fromCode(e.item.gradeCompany)
            val variant = stringResource(CardVariant.fromCode(e.item.variant).labelRes)
            Text(
                if (company.isGraded) "$variant · ${gradeLabel(company, e.item.gradeX10)}" else variant,
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary
            )
            Text(unitText(e.unit) + "  ×${e.item.tradeQuantity}", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onQty(e.item.tradeQuantity - 1) }) {
                    Text(stringResource(if (e.item.tradeQuantity <= 1) R.string.remove_from_binder else R.string.one_less))
                }
                if (e.item.tradeQuantity < e.item.quantity) {
                    OutlinedButton(onClick = { onQty(e.item.tradeQuantity + 1) }) { Text(stringResource(R.string.one_more)) }
                }
            }
        }
    }
}
