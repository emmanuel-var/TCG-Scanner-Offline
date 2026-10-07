package com.tcgscanner.offline.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tcgscanner.offline.R
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.Games
import com.tcgscanner.offline.scanner.SetupItem
import com.tcgscanner.offline.scanner.SetupKind
import com.tcgscanner.offline.scanner.SetupPhase
import com.tcgscanner.offline.scanner.SetupStatus
import com.tcgscanner.offline.scanner.SetupSummary
import com.tcgscanner.offline.ui.LocalContainer
import kotlinx.coroutines.delay

/**
 * Small progress tab shown while the app prepares itself for offline use (engines + catalogs). It appears by itself,
 * can be expanded for details, says "ready" for a few seconds when done and then disappears.
 * [onVisibleChange] lets the caller adapt system-bar padding.
 */
@Composable
fun SetupStrip(modifier: Modifier = Modifier, onVisibleChange: (Boolean) -> Unit = {}) {
    val setup = LocalContainer.current.setup
    val summary by setup.summary.collectAsStateWithLifecycle()

    var hadWork by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }
    var dismissedPhase by remember { mutableStateOf<SetupPhase?>(null) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(summary.phase) {
        if (summary.phase == SetupPhase.DONE) {
            if (hadWork) {
                showDone = true
                delay(DONE_VISIBLE_MS)
                showDone = false
            }
        } else {
            hadWork = true
            showDone = false
            if (dismissedPhase != null && dismissedPhase != summary.phase) dismissedPhase = null
        }
    }

    val visible = if (summary.phase == SetupPhase.DONE) showDone else dismissedPhase != summary.phase
    LaunchedEffect(visible) { onVisibleChange(visible) }

    AnimatedVisibility(visible = visible, enter = expandVertically(), exit = shrinkVertically(), modifier = modifier) {
        Surface(tonalElevation = 3.dp, shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (summary.phase == SetupPhase.DONE) R.string.setup_done else R.string.setup_title),
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f)
                    )
                    if (summary.phase != SetupPhase.DONE) {
                        summary.fraction?.let { Text(stringResource(R.string.model_percent, (it * 100).toInt()), style = MaterialTheme.typography.labelLarge) }
                        IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = stringResource(if (expanded) R.string.setup_collapse else R.string.setup_expand)
                            )
                        }
                    } else {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { dismissedPhase = summary.phase; showDone = false }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.setup_dismiss))
                    }
                }
                if (summary.phase != SetupPhase.DONE) {
                    val fraction = summary.fraction
                    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                when (summary.phase) {
                    SetupPhase.WAITING_WIFI -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.setup_waiting_wifi), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { setup.useMobileData() }) { Text(stringResource(R.string.setup_use_mobile_data)) }
                    }
                    SetupPhase.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.setup_failed), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { setup.retry() }) { Text(stringResource(R.string.model_retry)) }
                    }
                    else -> Unit
                }
                AnimatedVisibility(visible = expanded && summary.phase != SetupPhase.DONE) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { summary.items.forEach { ItemRow(it) } }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: SetupItem) {
    val label = when (item.kind) {
        SetupKind.SCAN_ENGINE -> stringResource(R.string.setup_scan_engine)
        SetupKind.ARTWORK_ENGINE -> stringResource(R.string.setup_artwork_engine)
        SetupKind.CATALOG -> stringResource(R.string.setup_catalog_item, GameId.fromCode(item.gameCode.orEmpty())?.let { stringResource(Games[it].nameRes) }.orEmpty())
    }
    val status = when (item.status) {
        SetupStatus.DONE -> stringResource(R.string.setup_item_done)
        SetupStatus.WORKING -> item.progress?.let { stringResource(R.string.model_percent, (it * 100).toInt()) } ?: "…"
        SetupStatus.WAITING -> stringResource(R.string.setup_item_waiting)
        SetupStatus.FAILED -> stringResource(R.string.setup_item_failed)
    }
    val color = if (item.status == SetupStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(status, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

private const val DONE_VISIBLE_MS = 3_000L
