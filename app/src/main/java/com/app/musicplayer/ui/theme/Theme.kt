package com.app.musicplayer.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

val DefaultAccent = Color(0xFF8E7CFF)
private val Base = Color(0xFF0B0B12)
private val TextHigh = Color(0xFFEDEAF6)
private val TextMid = Color(0xFFB9B4C8)

/** Expressive dark theme. [accent] comes from the album art (Palette) and cross-fades smoothly. */
@Composable
fun MusicTheme(accent: Color = DefaultAccent, content: @Composable () -> Unit) {
    val a by animateColorAsState(accent, tween(700), label = "accent")
    val onAccent = if (a.luminance() > 0.5f) Color.Black else Color.White
    val scheme = darkColorScheme(
        primary = a,
        onPrimary = onAccent,
        primaryContainer = lerp(Base, a, 0.35f),
        onPrimaryContainer = Color.White,
        secondary = lerp(a, Color.White, 0.25f),
        onSecondary = Color.Black,
        background = lerp(Base, a, 0.10f),
        onBackground = TextHigh,
        surface = lerp(Base, a, 0.14f),
        onSurface = TextHigh,
        surfaceVariant = lerp(Base, a, 0.22f),
        onSurfaceVariant = TextMid,
        outline = Color.White.copy(alpha = 0.18f),
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
