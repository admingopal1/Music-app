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

/** Accent colours available in Settings; the default follows the app's premium violet identity. */
val ACCENT_CHOICES = listOf(
    Color(0xFFFF8800), // orange
    Color(0xFFE53935), // red
    Color(0xFF1E88E5), // blue
    Color(0xFF43A047), // green
    Color(0xFF8E7CFF), // purple
    Color(0xFFEC407A), // pink
)
val DefaultAccent = ACCENT_CHOICES[4]

private val TextHigh = Color(0xFFEDEAF6)
private val TextMid = Color(0xFFB9B4C8)

private val AppTypography = Typography().let { base ->
    Typography(
        headlineLarge = base.headlineLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
    )
}

/** Dark theme. [accent] comes from Settings or the album art (Palette) and cross-fades smoothly. */
@Composable
fun MusicTheme(accent: Color = DefaultAccent, amoled: Boolean = true, content: @Composable () -> Unit) {
    val a by animateColorAsState(accent, tween(700), label = "accent")
    val onAccent = if (a.luminance() > 0.5f) Color.Black else Color.White
    val base = if (amoled) Color(0xFF050505) else Color(0xFF15151D)
    val scheme = darkColorScheme(
        primary = a,
        onPrimary = onAccent,
        primaryContainer = lerp(base, a, 0.35f),
        onPrimaryContainer = Color.White,
        secondary = lerp(a, Color.White, 0.25f),
        onSecondary = Color.Black,
        background = lerp(base, a, 0.03f),
        onBackground = TextHigh,
        surface = lerp(base, a, 0.07f),
        onSurface = TextHigh,
        surfaceVariant = lerp(base, a, 0.14f),
        onSurfaceVariant = TextMid,
        outline = Color.White.copy(alpha = 0.18f),
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
