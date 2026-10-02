@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.tcgscanner.offline.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tcgscanner.offline.R
import com.tcgscanner.offline.scanner.ModelState

/**
 * Translucent notice over the camera preview. It only describes the optional visual engine and never blocks
 * scanning. Rendered purely from [state]: Missing / Failed show the Download button, Downloading swaps it for a
 * progress indicator, Ready renders nothing (the caller also animates it away).
 */
@Composable
fun ModelPromptCard(
    state: ModelState,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    @androidx.annotation.StringRes promptRes: Int = R.string.model_prompt,
    @androidx.annotation.StringRes downloadingRes: Int = R.string.model_downloading
) {
    if (state is ModelState.Ready) return
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (state) {
                is ModelState.Downloading -> {
                    val progress = state.progress
                    if (progress != null) CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    else CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(downloadingRes), style = MaterialTheme.typography.bodyMedium)
                        if (progress != null) {
                            Text(stringResource(R.string.model_percent, (progress * 100).toInt()), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                is ModelState.Failed -> {
                    Text(stringResource(R.string.model_failed), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onDownload) { Text(stringResource(R.string.model_retry)) }
                }
                else -> {
                    Text(stringResource(promptRes), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onDownload) { Text(stringResource(R.string.model_download)) }
                }
            }
        }
    }
}
