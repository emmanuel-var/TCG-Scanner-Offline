@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.tcgscanner.offline.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Teal = Color(0xFF00897B)
private val TealDark = Color(0xFF4DB6AC)
private val Navy = Color(0xFF0F1B2D)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF3949AB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE1FF),
    onSecondaryContainer = Color(0xFF00105C),
    tertiary = Color(0xFFB26A00),
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE3EAEA),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFB8C3FF),
    onSecondary = Color(0xFF16277A),
    secondaryContainer = Color(0xFF2E3F92),
    onSecondaryContainer = Color(0xFFDDE1FF),
    tertiary = Color(0xFFFFB95C),
    background = Navy,
    surface = Color(0xFF16233A),
    surfaceVariant = Color(0xFF22324A),
    error = Color(0xFFFFB4AB)
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp)
)

/** Colours used for gains / losses; never rely on colour alone (arrows + signs are shown too). */
object Semantic {
    val gain = Color(0xFF2E9E5B)
    val loss = Color(0xFFD64545)
    val warn = Color(0xFFE69A00)
}

@Composable
fun TcgTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
