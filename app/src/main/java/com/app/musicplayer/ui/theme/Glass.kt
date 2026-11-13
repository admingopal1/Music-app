package com.app.musicplayer.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/*
 * "Liquid glass" design kit shared by every screen:
 *   - Modifier.glass()          translucent frosted panel with a light edge and a specular highlight
 *   - Modifier.glassBackdrop()  soft, slowly drifting colour light behind the whole app
 *   - glassFieldColors()        translucent text fields
 * No extra libraries are needed. Panels are translucent, so the colour behind them shows through.
 */

/** Dark, slightly see-through background for dialogs. */
val GlassDialogColor = Color(0xFF1B1730).copy(alpha = 0.92f)
val GlassDialogShape = RoundedCornerShape(28.dp)

/** Frosted glass surface. [tint] colours the glass (for example the accent for the selected row). */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(22.dp),
    tint: Color = Color.Transparent,
): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.06f))))
    .background(tint)
    .drawBehind {
        // Thin specular highlight along the top edge, like light catching real glass.
        val w = size.width
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent)
            ),
            topLeft = Offset(w * 0.1f, 0f),
            size = Size(w * 0.8f, 1.dp.toPx()),
        )
    }
    .border(
        1.dp,
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.05f))),
        shape,
    )

/** Dark base with two accent-coloured lights that drift slowly; the glass panels sit on top of it. */
fun Modifier.glassBackdrop(accent: Color, accent2: Color): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "glass-backdrop")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val a by animateColorAsState(accent, tween(700), label = "backdrop-a")
    val b by animateColorAsState(accent2, tween(700), label = "backdrop-b")
    this.drawBehind {
        drawRect(Color(0xFF07060D))
        val w = size.width
        val h = size.height
        val r = maxOf(w, h) * 0.75f
        fun light(color: Color, cx: Float, cy: Float, alpha: Float) {
            val c = Offset(cx, cy)
            drawCircle(
                brush = Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center = c, radius = r),
                radius = r,
                center = c,
            )
        }
        light(a, w * (0.10f + 0.30f * drift), h * (0.08f + 0.06f * drift), 0.55f)
        light(b, w * (0.95f - 0.30f * drift), h * (0.62f + 0.10f * drift), 0.45f)
        light(Color(0xFF2B6CFF), w * (0.30f + 0.20f * drift), h * (0.98f - 0.12f * drift), 0.22f)
    }
}

@Composable
fun GlassBackdrop(
    accent: Color,
    accent2: Color,
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glassBackdrop(accent, accent2), content = content)
}

@Composable
fun glassFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White.copy(alpha = 0.10f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.07f),
    focusedBorderColor = Color.White.copy(alpha = 0.50f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
    cursorColor = MaterialTheme.colorScheme.primary,
)
