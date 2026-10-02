@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.tcgscanner.offline.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tcgscanner.offline.R
import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.trade.TradeItem
import com.tcgscanner.offline.trade.TradePhase
import com.tcgscanner.offline.trade.TradeState
import com.tcgscanner.offline.trade.Verdict
import com.tcgscanner.offline.ui.LocalContainer
import com.tcgscanner.offline.ui.components.CardImage
import com.tcgscanner.offline.ui.components.EmptyState
import com.tcgscanner.offline.ui.moneyText
import com.tcgscanner.offline.ui.relativeTime
import com.tcgscanner.offline.ui.theme.Semantic
import kotlinx.coroutines.launch

private fun requiredPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES
    )
    Build.VERSION.SDK_INT >= 31 -> arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION
    )
    else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val trade = container.trade
    val state by trade.state.collectAsStateWithLifecycle()
    val settings by container.settingsState.collectAsStateWithLifecycle()
    val game = settings?.currentGame
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) { onDispose { trade.stop() } }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it } && game != null) trade.start(game)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.trade_nearby)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } }
        )
    }) { padding ->
        Column(Modifier.padding(padding)) {
            when (state.phase) {
                TradePhase.IDLE -> EmptyState(
                    Icons.Filled.SwapHoriz, stringResource(R.string.trade_intro_title), stringResource(R.string.trade_intro_message),
                    actionLabel = stringResource(R.string.trade_start),
                    onAction = { permissionLauncher.launch(requiredPermissions()) }
                )
                TradePhase.SEARCHING, TradePhase.CONNECTING -> Searching(state, onConnect = trade::connect, onStop = trade::stop)
                TradePhase.CONNECTED, TradePhase.DONE -> Table(
                    state, trade::setGive, trade::setGet, trade::accept,
                    onApply = { scope.launch { trade.applyToCollection(container.collection) } }, onClose = onBack
                )
                TradePhase.ERROR -> EmptyState(
                    Icons.Filled.SwapHoriz, stringResource(R.string.trade_error_title),
                    if (state.error == "DISCONNECTED") stringResource(R.string.trade_disconnected) else (state.error ?: ""),
                    actionLabel = stringResource(R.string.trade_retry),
                    onAction = { permissionLauncher.launch(requiredPermissions()) }
                )
            }
        }
    }

    state.pendingAuth?.let { p ->
        AlertDialog(
            onDismissRequest = trade::rejectPairing,
            title = { Text(stringResource(R.string.trade_pairing_title, p.name)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(p.digits, style = MaterialTheme.typography.headlineLarge)
                    Text(stringResource(R.string.trade_pairing_message), textAlign = TextAlign.Center)
                }
            },
            confirmButton = { TextButton(onClick = trade::confirmPairing) { Text(stringResource(R.string.trade_pairing_match)) } },
            dismissButton = { TextButton(onClick = trade::rejectPairing) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun Searching(state: TradeState, onConnect: (com.tcgscanner.offline.trade.Peer) -> Unit, onStop: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.trade_searching), style = MaterialTheme.typography.titleMedium)
        }
        Text(stringResource(R.string.trade_binder_shared_note, state.myItems.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.peers.forEach { peer ->
            Card(
                onClick = { onConnect(peer) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(peer.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.trade_connect))
                }
            }
        }
        OutlinedButton(onClick = onStop) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun Table(
    state: TradeState,
    onGive: (String, Int) -> Unit,
    onGet: (String, Int) -> Unit,
    onAccept: () -> Unit,
    onApply: () -> Unit,
    onClose: () -> Unit
) {
    val bal = state.balance
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(R.string.trade_with, state.remoteNick), style = MaterialTheme.typography.titleLarge)
            val mine = state.myPricesAt?.let { relativeTime(it) } ?: stringResource(R.string.never_synced)
            val theirs = state.remotePricesAt?.let { relativeTime(it) } ?: stringResource(R.string.never_synced)
            Text(stringResource(R.string.trade_prices_dates, mine, theirs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.trade_local_prices_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.trade_offer_vs, moneyText(bal.giveUsd), moneyText(bal.getUsd)),
                        style = MaterialTheme.typography.titleLarge
                    )
                    val (text, color) = when (bal.verdict) {
                        Verdict.EMPTY -> stringResource(R.string.trade_verdict_empty) to MaterialTheme.colorScheme.onPrimaryContainer
                        Verdict.FAIR -> stringResource(R.string.trade_verdict_fair) to Semantic.gain
                        Verdict.YOU_GIVE_MORE -> stringResource(R.string.trade_verdict_give_more, moneyText(-bal.difference)) to Semantic.warn
                        Verdict.YOU_GET_MORE -> stringResource(R.string.trade_verdict_get_more, moneyText(bal.difference)) to Semantic.gain
                    }
                    Text(text, style = MaterialTheme.typography.titleMedium, color = color)
                }
            }
        }
        item {
            when {
                state.phase == TradePhase.DONE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.trade_done), style = MaterialTheme.typography.titleMedium, color = Semantic.gain)
                    if (!state.applied) Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.trade_apply)) }
                    else Text(stringResource(R.string.trade_applied))
                    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.close)) }
                }
                state.acceptedByMe -> Text(stringResource(R.string.trade_waiting_them, state.remoteNick), style = MaterialTheme.typography.bodyLarge)
                else -> Column {
                    if (state.acceptedByThem) Text(stringResource(R.string.trade_they_accepted, state.remoteNick), color = Semantic.gain)
                    Button(onClick = onAccept, enabled = state.iGive.isNotEmpty() || state.iGet.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                        Text(stringResource(R.string.trade_accept), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        item { Text(stringResource(R.string.trade_you_give), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        if (state.myItems.isEmpty()) item { Text(stringResource(R.string.trade_no_items), style = MaterialTheme.typography.bodyMedium) }
        items(state.myItems, key = { "m" + it.key }) { it ->
            TradeRow(it, state.iGive[it.key] ?: 0, enabled = state.phase == TradePhase.CONNECTED) { q -> onGive(it.key, q) }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text(stringResource(R.string.trade_you_get, state.remoteNick), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        if (state.theirItems.isEmpty()) item { Text(stringResource(R.string.trade_no_items_them), style = MaterialTheme.typography.bodyMedium) }
        items(state.theirItems, key = { "t" + it.key }) { it ->
            TradeRow(it, state.iGet[it.key] ?: 0, enabled = state.phase == TradePhase.CONNECTED) { q -> onGet(it.key, q) }
        }
    }
}

@Composable
private fun TradeRow(item: TradeItem, selected: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(if (selected > 0) 0 else 1) },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CardImage(item.imageUrl, item.name, Modifier.size(width = 40.dp, height = 56.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            val variant = stringResource(CardVariant.fromCode(item.variant).labelRes)
            Text(
                "${item.setName} · #${item.number} · $variant" + if (item.grade.isNotEmpty()) " · ${item.grade}" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2
            )
            Text(moneyText(item.usd) + "  ×${item.qty}", style = MaterialTheme.typography.labelLarge)
        }
        Stepper(label = "", value = selected, min = 0, max = if (enabled) item.qty else selected, onChange = onChange)
    }
}
