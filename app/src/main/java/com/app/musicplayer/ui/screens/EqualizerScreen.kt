package com.app.musicplayer.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.musicplayer.audio.SonyAudioEffects
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val REVERB_NAMES = listOf("Off", "Small room", "Medium room", "Large room", "Medium hall", "Large hall", "Plate")

@Composable
fun EqualizerScreen(fx: SonyAudioEffects) {
    val s by fx.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var clarity by rememberSaveable { mutableStateOf(prefs.getFloat("enh_clarity", 0f)) }
    var crossfeed by rememberSaveable { mutableStateOf(prefs.getFloat("enh_crossfeed", 0f)) }
    var width by rememberSaveable { mutableStateOf(prefs.getFloat("enh_width", 1f)) }
    var balance by rememberSaveable { mutableStateOf(prefs.getFloat("enh_balance", 0f)) }
    var mono by rememberSaveable { mutableStateOf(prefs.getBoolean("enh_mono", false)) }
    var limiter by rememberSaveable { mutableStateOf(prefs.getBoolean("enh_limiter", false)) }
    var profileName by rememberSaveable { mutableStateOf("") }
    val on = s.enabled && s.available

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF21133E), Color(0xFF17152F), MaterialTheme.colorScheme.background))).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Studio Equalizer", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(
                    if (s.available) "${s.centerFreqsHz.size}-band engine · ${s.activePreset}"
                    else "Start a song to attach the audio effects",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = s.enabled, onCheckedChange = { fx.setEnabled(it) })
        }

        // Presets + saved profiles
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SonyAudioEffects.PRESETS.keys.toList()) { name ->
                FilterChip(selected = s.activePreset == name, onClick = { fx.applyPreset(name) }, enabled = s.available, label = { Text(name) })
            }
            items(s.customProfiles) { name ->
                FilterChip(
                    selected = s.activePreset == name,
                    onClick = { fx.applyPreset(name) },
                    enabled = s.available,
                    label = { Text(name) },
                    trailingIcon = {
                        Icon(Icons.Rounded.Close, "Delete profile", Modifier.size(16.dp).clickable { fx.deleteProfile(name) })
                    },
                )
            }
        }

        // EQ bands (-12..+12 dB)
        GlassCard(Modifier.fillMaxWidth()) {
            if (s.bandLevelsDb.isEmpty()) {
                Text("No equalizer bands available yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth().height(260.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    s.bandLevelsDb.forEachIndexed { i, db ->
                        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("%+.0f".format(db), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            VerticalBand(
                                db, s.minDb, s.maxDb, on,
                                onChange = { fx.setBandLevel(i, it) },
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
                            )
                            Text(freqLabel(s.centerFreqsHz[i]), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Knobs
        GlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("Clear Bass", s.bassStrength, "${(s.bassStrength * 100).roundToInt()}%", on && s.bassSupported) { fx.setClearBass(it) }
                Knob("Surround 3D", s.virtualizerStrength, "${(s.virtualizerStrength * 100).roundToInt()}%", on && s.virtSupported) { fx.setVirtualizerStrength(it) }
                Knob("Loudness", s.loudnessGain, "+%.1f dB".format(s.loudnessGain * 10f), on && s.loudnessSupported) { fx.setLoudnessGain(it) }
            }
            Text(
                "Hardware equalizer band count varies by device. Drag a knob up / right to increase. Loudness adds up to +10 dB of limiter-protected gain.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        // Software DSP controls are applied live by the service-side EnhancerController.
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Advanced DSP Studio", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text("Live stereo processing · 16-bit / float PCM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DspSlider("Clarity", clarity, 0f..1f, { clarity = it; prefs.edit().putFloat("enh_clarity", it).apply() }, "\${(clarity * 100).roundToInt()}%")
                DspSlider("Headphone crossfeed", crossfeed, 0f..1f, { crossfeed = it; prefs.edit().putFloat("enh_crossfeed", it).apply() }, "\${(crossfeed * 100).roundToInt()}%")
                DspSlider("Stereo width", width, 0f..2f, { width = it; prefs.edit().putFloat("enh_width", it).apply() }, "\${(width * 100).roundToInt()}%")
                DspSlider("Left / right balance", balance, -1f..1f, { balance = it; prefs.edit().putFloat("enh_balance", it).apply() }, when { balance < -0.01f -> "L \${(kotlin.math.abs(balance) * 100).roundToInt()}%"; balance > 0.01f -> "R \${(balance * 100).roundToInt()}%"; else -> "Center" })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mono compatibility", style = MaterialTheme.typography.titleSmall)
                        Text("Blend left and right channels", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = mono, onCheckedChange = { mono = it; prefs.edit().putBoolean("enh_mono", it).apply() })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Soft limiter", style = MaterialTheme.typography.titleSmall)
                        Text("Reduce peaks when DSP boosts the signal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = limiter, onCheckedChange = { limiter = it; prefs.edit().putBoolean("enh_limiter", it).apply() })
                }
                Text("Tip: keep stereo width near 100% for accurate imaging. Strong clarity or width boosts can change the original mix.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Reverb
        Text("Reverb", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(REVERB_NAMES.size) { i ->
                FilterChip(
                    selected = s.reverbPreset == i,
                    onClick = { fx.setReverbPreset(i) },
                    enabled = s.available && s.reverbSupported,
                    label = { Text(REVERB_NAMES[i]) },
                )
            }
        }

        // Save custom profile
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = profileName, onValueChange = { profileName = it },
                label = { Text("Save current EQ as…") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { fx.saveProfile(profileName); profileName = "" },
                enabled = profileName.isNotBlank() && s.bandLevelsDb.isNotEmpty(),
            ) { Text("Save") }
        }
    }
}

private fun freqLabel(hz: Int): String =
    if (hz >= 1000) "%.1fk".format(hz / 1000f).replace(".0k", "k") else "${hz}Hz"

private fun yToDb(y: Float, height: Float, min: Float, max: Float): Float =
    max - (y / height).coerceIn(0f, 1f) * (max - min)

@Composable
private fun DspSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
    valueLabel: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range), onValueChange = onValue, valueRange = range)
    }
}

/** Vertical slider drawn on a Canvas: drag or tap anywhere on the track. */
@Composable
private fun VerticalBand(
    valueDb: Float, minDb: Float, maxDb: Float, enabled: Boolean,
    onChange: (Float) -> Unit, modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val animated by animateFloatAsState(valueDb, spring(stiffness = Spring.StiffnessMedium), label = "band")
    val onChangeNow by rememberUpdatedState(onChange)

    Canvas(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .pointerInput(minDb, maxDb) {
                detectVerticalDragGestures(
                    onDragStart = { onChangeNow(yToDb(it.y, size.height.toFloat(), minDb, maxDb)) },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        onChangeNow(yToDb(change.position.y, size.height.toFloat(), minDb, maxDb))
                    },
                )
            }
            .pointerInput(minDb, maxDb) {
                detectTapGestures { onChangeNow(yToDb(it.y, size.height.toFloat(), minDb, maxDb)) }
            }
    ) {
        val cx = size.width / 2f
        val track = 6.dp.toPx()
        val range = maxDb - minDb
        val thumbY = (maxDb - animated) / range * size.height
        val zeroY = maxDb / range * size.height

        drawLine(Color.White.copy(0.15f), Offset(cx, 0f), Offset(cx, size.height), track, StrokeCap.Round)
        drawLine(Color.White.copy(0.35f), Offset(cx - 12.dp.toPx(), zeroY), Offset(cx + 12.dp.toPx(), zeroY), 2.dp.toPx())
        drawLine(accent, Offset(cx, zeroY), Offset(cx, thumbY), track, StrokeCap.Round)
        drawCircle(Color.White, 11.dp.toPx(), Offset(cx, thumbY))
        drawCircle(accent, 11.dp.toPx(), Offset(cx, thumbY), style = Stroke(3.dp.toPx()))
    }
}

/** Rotary dial (270° sweep). Drag up/right to increase, down/left to decrease. */
@Composable
private fun Knob(label: String, value: Float, display: String, enabled: Boolean, onChange: (Float) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val animated by animateFloatAsState(value, spring(stiffness = Spring.StiffnessMedium), label = "knob")
    val valueNow by rememberUpdatedState(value)
    val onChangeNow by rememberUpdatedState(onChange)

    Column(Modifier.alpha(if (enabled) 1f else 0.4f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(
                Modifier.size(92.dp).pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    var acc = 0f
                    detectDragGestures(
                        onDragStart = { acc = valueNow },
                        onDrag = { change, drag ->
                            change.consume()
                            acc = (acc + (-drag.y + drag.x) / 350f).coerceIn(0f, 1f)
                            onChangeNow(acc)
                        },
                    )
                }
            ) {
                val stroke = 8.dp.toPx()
                val inset = stroke / 2f + 4.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                drawArc(Color.White.copy(0.14f), 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(accent, 135f, 270f * animated, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                val ang = Math.toRadians((135f + 270f * animated).toDouble())
                val r = arcSize.width / 2f
                drawCircle(Color.White, 5.dp.toPx(), Offset((center.x + r * cos(ang)).toFloat(), (center.y + r * sin(ang)).toFloat()))
            }
            Text(display, style = MaterialTheme.typography.labelLarge)
        }
        Text(if (enabled || value > 0f) label else "$label (n/a)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
