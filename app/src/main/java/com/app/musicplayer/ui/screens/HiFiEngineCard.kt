package com.app.musicplayer.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.musicplayer.audio.EngineAnalyzer
import com.app.musicplayer.audio.EngineMonitor
import com.app.musicplayer.audio.EnhancerSettings
import com.app.musicplayer.ui.PlayerViewModel

private val BAND_LABELS = listOf("63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

/** Controls and live analyser for the AI Hi-Fi engine (software DSP in front of the AudioTrack). */
@Composable
fun HiFiEngineCard(vm: PlayerViewModel) {
    val s by vm.enhancer.collectAsStateWithLifecycle()
    val stats by EngineMonitor.stats.collectAsStateWithLifecycle()
    LifecycleStartEffect(Unit) {
        EngineMonitor.listening = true
        onStopOrDispose { EngineMonitor.listening = false }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("AI Hi-Fi Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stats.scene.ifBlank { "Play a song to start the analyser" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Analyzer(stats.bandsDb, stats.correctionDb, Modifier.fillMaxWidth().height(120.dp).padding(top = 12.dp))
        Row(Modifier.fillMaxWidth()) {
            BAND_LABELS.forEach {
                Text(
                    it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        val info = buildList {
            if (s.aiEq) add("AI EQ active")
            if (stats.autoUpscale > 0.05f) add("Auto-upscale %.0f%%".format(stats.autoUpscale * 100))
            if (s.autoLevel) add("Smart volume %+.1f dB".format(stats.autoLevelDb))
            if (stats.gainReductionDb > 0.1f) add("Limiter −%.1f dB".format(stats.gainReductionDb))
        }
        if (info.isNotEmpty()) {
            Text(
                info.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp),
            )
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            items(EnhancerSettings.PRESETS.keys.toList()) { name ->
                FilterChip(selected = s.preset == name, onClick = { vm.applyEnginePreset(name) }, label = { Text(name) })
            }
            if (s.preset == EnhancerSettings.CUSTOM) {
                item { FilterChip(selected = true, onClick = {}, label = { Text(EnhancerSettings.CUSTOM) }) }
            }
        }

        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SettingSwitch("AI adaptive EQ (learns each song)", s.aiEq) { vm.tweakEnhancer(s.copy(aiEq = it)) }
            SettingSwitch("Smart volume (even loudness)", s.autoLevel) { vm.tweakEnhancer(s.copy(autoLevel = it)) }
            SettingSwitch("Night mode (soft dynamics)", s.night) { vm.tweakEnhancer(s.copy(night = it)) }
            EnhSlider("Hi-Res upscaler (restores lost treble)", pct(s.upscale), s.upscale, 0f..1f) { vm.tweakEnhancer(s.copy(upscale = it)) }
            EnhSlider("Deep bass (psychoacoustic)", pct(s.bass), s.bass, 0f..1f) { vm.tweakEnhancer(s.copy(bass = it)) }
            EnhSlider("Clarity (vocals & detail)", pct(s.clarity), s.clarity, 0f..1f) { vm.tweakEnhancer(s.copy(clarity = it)) }
            EnhSlider("Headphone crossfeed", pct(s.crossfeed), s.crossfeed, 0f..1f) { vm.tweakEnhancer(s.copy(crossfeed = it)) }
            EnhSlider(
                "Stereo width (bass stays centred)", if (s.width == 1f) "Normal" else pct(s.width), s.width, 0f..2f,
            ) { vm.tweakEnhancer(s.copy(width = it)) }
        }
        Text(
            "Runs on-device in 32-bit float with a look-ahead limiter, so boosts never clip. Works together with the equalizer below.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun pct(v: Float) = "%.0f%%".format(v * 100)

/** Octave-band level bars with the AI correction curve drawn on top. */
@Composable
private fun Analyzer(bandsDb: List<Float>, correctionDb: List<Float>, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val n = EngineAnalyzer.BANDS.size
    val levels = List(n) { i ->
        val target = ((bandsDb.getOrElse(i) { -90f } + 75f) / 65f).coerceIn(0f, 1f)
        animateFloatAsState(target, tween(110), label = "band$i").value
    }
    val corr = List(n) { i -> animateFloatAsState(correctionDb.getOrElse(i) { 0f }, tween(300), label = "corr$i").value }
    val showCurve = corr.any { kotlin.math.abs(it) > 0.05f }

    Canvas(modifier) {
        val slot = size.width / n
        val barW = slot * 0.62f
        levels.forEachIndexed { i, v ->
            val h = size.height * v
            val x = i * slot + (slot - barW) / 2f
            drawRoundRect(Color.White.copy(0.08f), Offset(x, 0f), Size(barW, size.height), CornerRadius(6.dp.toPx()))
            drawRoundRect(accent.copy(0.35f + 0.55f * v), Offset(x, size.height - h), Size(barW, h), CornerRadius(6.dp.toPx()))
        }
        val mid = size.height / 2f
        drawLine(Color.White.copy(0.25f), Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        if (showCurve) {
            val path = Path()
            corr.forEachIndexed { i, db ->
                val x = i * slot + slot / 2f
                val y = mid - (db / 8f) * (size.height / 2f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color.White, style = Stroke(2.5.dp.toPx()))
        }
    }
}
