@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.tcgscanner.offline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.tcgscanner.offline.core.GameDef

/** Generated emblem (accent gradient + initials). No third-party logos or trademarks are bundled. */
@Composable
fun GameEmblem(game: GameDef, size: Dp, modifier: Modifier = Modifier) {
    val accent = Color(game.accent)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.55f)))) // decorative
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        Text(
            game.emblem,
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (size.value * if (game.emblem.length > 1) 0.34f else 0.5f).sp
        )
    }
}

private val grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * Card artwork loaded with Coil (disk-cached, so cards you have seen once still show offline).
 * Missing cards of a master set are rendered in greyscale.
 */
@Composable
fun CardImage(
    url: String?,
    name: String,
    modifier: Modifier = Modifier,
    owned: Boolean = true,
    contentDescription: String? = name
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (url.isNullOrBlank()) {
            NameFallback(name, owned)
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                colorFilter = if (owned) null else grayscale,
                alpha = if (owned) 1f else 0.55f,
                modifier = Modifier.fillMaxSize(),
                error = { NameFallback(name, owned) },
                loading = { Box(Modifier.fillMaxSize()) }
            )
        }
    }
}

@Composable
private fun NameFallback(name: String, owned: Boolean) {
    Text(
        name,
        modifier = Modifier
            .padding(6.dp)
            .alpha(if (owned) 1f else 0.5f),
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        maxLines = 4,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 20.dp)) { Text(actionLabel) }
        }
    }
}
