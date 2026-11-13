package com.app.musicplayer.ui.screens

import com.app.musicplayer.ui.theme.GlassDialogShape
import com.app.musicplayer.ui.theme.GlassDialogColor
import com.app.musicplayer.ui.theme.glass
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.musicplayer.share.Direction
import com.app.musicplayer.share.OutFile
import com.app.musicplayer.share.Peer
import com.app.musicplayer.share.QrUtil
import com.app.musicplayer.share.ShareUi
import com.app.musicplayer.share.Status
import com.app.musicplayer.share.TransferItem
import com.app.musicplayer.ui.PlayerViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ShareScreen(vm: PlayerViewModel) {
    val share = vm.share
    val ui by share.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var target by remember { mutableStateOf<Pair<Peer, String>?>(null) }   // receiver we are connected to
    var pinPeer by remember { mutableStateOf<Peer?>(null) }
    var showManual by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        share.startDiscovery()
        onDispose { share.stopDiscovery() }
    }
    LaunchedEffect(ui.sessionRunning) {
        while (ui.sessionRunning) {
            share.refreshNetwork()
            delay(4000)
        }
    }
    LaunchedEffect(ui.libraryChanged) {
        if (ui.libraryChanged > 0) { vm.loadLibrary(); vm.loadVideos() }
    }
    LaunchedEffect(ui.message) {
        ui.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); share.clearMessage() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { u ->
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        if (uris.isNotEmpty()) share.addOutgoing(uris)
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r ->
        val text = r.contents
        if (text != null) {
            val parsed = share.parseConnect(text)
            if (parsed != null) target = parsed
            else Toast.makeText(ctx, "That isn't a Music Player share code.", Toast.LENGTH_SHORT).show()
        }
    }
    val hotspotPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.isNotEmpty() && res.values.all { it }) share.startHotspot()
        else Toast.makeText(ctx, "Permission is needed to create a hotspot.", Toast.LENGTH_LONG).show()
    }

    fun pick(vararg mimes: String) = picker.launch(arrayOf(*mimes))
    fun scan() = scanner.launch(
        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
            .setOrientationLocked(false).setPrompt("Scan the QR code on the receiving phone"),
    )
    fun startHotspot() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) share.startHotspot()
        else hotspotPerm.launch(arrayOf(perm))
    }

    val accent = MaterialTheme.colorScheme.primary

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("Share", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Send music, videos and files to any phone or PC. No internet needed.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            RadarHero(ui.peers, ui.sessionRunning) { pinPeer = it }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigAction(
                    "Send", "Pick files", Icons.Rounded.Upload,
                    listOf(Color(0xFF9656E8), Color(0xFF6639B5)), Modifier.weight(1f),
                ) { pick("*/*") }
                if (ui.sessionRunning) {
                    BigAction(
                        "Stop", "Receiving is on", Icons.Rounded.Close,
                        listOf(Color(0xFFE53935), Color(0xFFB71C1C)), Modifier.weight(1f),
                    ) { share.stopSession() }
                } else {
                    BigAction(
                        "Receive", "Show QR and PIN", Icons.Rounded.Download,
                        listOf(Color(0xFFB04CE8), Color(0xFF7543C6)), Modifier.weight(1f),
                    ) { share.startSession() }
                }
            }
        }

        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryChip(Icons.Rounded.MusicNote, "Music") { pick("audio/*") }
                CategoryChip(Icons.Rounded.VideoLibrary, "Videos") { pick("video/*") }
                CategoryChip(Icons.Rounded.Image, "Photos") { pick("image/*") }
                CategoryChip(
                    Icons.Rounded.Description, "Documents",
                ) { pick("application/pdf", "text/*", "application/msword", "application/vnd.openxmlformats-officedocument.*", "application/vnd.ms-excel") }
                CategoryChip(Icons.Rounded.Share, "Apps (APK)") { pick("application/vnd.android.package-archive") }
                CategoryChip(Icons.Rounded.Link, "Any file") { pick("*/*") }
            }
        }

        target?.let { (peer, pin) ->
            item {
                ShareCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF00BFA5))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text("Connected to ${peer.name}", fontWeight = FontWeight.SemiBold)
                            Text(
                                "${peer.host}:${peer.port}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { target = null }) { Icon(Icons.Rounded.Close, "Disconnect") }
                    }
                    if (ui.outgoing.isEmpty()) {
                        Button(onClick = { pick("*/*") }, modifier = Modifier.fillMaxWidth()) { Text("Add files to send") }
                    } else {
                        Button(
                            onClick = { share.sendOutgoingTo(peer, pin) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Send ${ui.outgoing.size} file${if (ui.outgoing.size == 1) "" else "s"} to ${peer.name}") }
                    }
                }
            }
        }

        if (ui.outgoing.isNotEmpty()) {
            item {
                OutgoingCard(
                    files = ui.outgoing, fmt = share::fmtSize,
                    onRemove = share::removeOutgoing, onClear = share::clearOutgoing, onAdd = { pick("*/*") },
                    hint = if (target == null) "Now pick a nearby phone above, scan its QR code, or share a link." else null,
                )
            }
        }

        if (ui.sessionRunning) {
            item {
                SessionCard(
                    ui,
                    onCopy = { clipboard.setText(AnnotatedString(it)); Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show() },
                    onShareLink = { shareText(ctx, it) },
                )
            }
        }

        ui.hotspot?.let { hs ->
            item { HotspotCard(hs.ssid, hs.password, onStop = share::stopHotspot) }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolTile(Icons.Rounded.QrCodeScanner, "Scan QR", Modifier.weight(1f)) { scan() }
                ToolTile(Icons.Rounded.Wifi, "Enter code", Modifier.weight(1f)) { showManual = true }
                ToolTile(Icons.Rounded.WifiTethering, if (ui.hotspot != null) "Hotspot on" else "Hotspot", Modifier.weight(1f)) {
                    if (ui.hotspot != null) share.stopHotspot() else startHotspot()
                }
                ToolTile(Icons.Rounded.Computer, "PC / link", Modifier.weight(1f)) {
                    share.startSession()
                    if (ui.outgoing.isEmpty()) pick("*/*")
                }
            }
        }

        if (ui.items.isEmpty() && !ui.sessionRunning) {
            item { HowItWorks() }
        }

        if (ui.items.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Transfers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = share::clearHistory) {
                        Icon(Icons.Rounded.DeleteSweep, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear")
                    }
                }
            }
            items(ui.items, key = { it.id }) { item ->
                TransferRow(
                    item, fmt = share::fmtSize,
                    onCancel = { share.cancel(item.id) },
                    onOpen = { openFile(ctx, item) },
                    onShare = { shareFile(ctx, item) },
                )
            }
        }
    }

    pinPeer?.let { peer ->
        PinDialog(
            title = "Send to ${peer.name}", fileCount = ui.outgoing.size,
            onPickFiles = { pick("*/*") },
            onDismiss = { pinPeer = null },
        ) { pin ->
            target = peer to pin
            if (ui.outgoing.isNotEmpty()) share.sendOutgoingTo(peer, pin)
            pinPeer = null
        }
    }
    if (showManual) {
        ManualDialog(onDismiss = { showManual = false }) { host, port, pin ->
            target = Peer(host, host, port) to pin
            showManual = false
        }
    }
    // accent is read so the screen recomposes with the theme colour
    if (accent == Color.Unspecified) Spacer(Modifier.height(0.dp))
}

// ---------------------------------------------------------------------- hero

@Composable
private fun RadarHero(peers: List<Peer>, running: Boolean, onPeer: (Peer) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val t = rememberInfiniteTransition(label = "radar")
    val p by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (running) 1800 else 3200, easing = LinearEasing)),
        label = "pulse",
    )
    val shown = peers.take(6)
    Box(Modifier.fillMaxWidth().height(290.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(290.dp)) {
            val maxR = size.minDimension / 2f
            drawCircle(accent.copy(alpha = 0.05f), radius = maxR * 0.95f)
            drawCircle(accent.copy(alpha = 0.08f), radius = maxR * 0.62f)
            for (i in 0..2) {
                val q = (p + i / 3f) % 1f
                drawCircle(accent.copy(alpha = (1f - q) * 0.35f), radius = maxR * q, style = Stroke(width = 2.dp.toPx()))
            }
        }
        Box(
            Modifier.size(86.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.55f)))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Wifi, null, tint = Color.White, modifier = Modifier.size(38.dp)) }

        shown.forEachIndexed { i, peer ->
            val ang = Math.toRadians(-90.0 + i * (360.0 / shown.size))
            val dx = (112 * cos(ang)).toInt()
            val dy = (112 * sin(ang)).toInt()
            Column(
                Modifier.offset(dx.dp, dy.dp).clip(RoundedCornerShape(16.dp)).clickable { onPeer(peer) }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(54.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(2.dp, accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(peer.name.firstOrNull()?.uppercase() ?: "?", fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                Text(
                    peer.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.width(72.dp),
                )
            }
        }
        if (shown.isEmpty()) {
            Text(
                if (running) "Waiting for the other phone…" else "Looking for nearby phones…",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun BigAction(
    label: String, sub: String, icon: ImageVector, colors: List<Color>, modifier: Modifier, onClick: () -> Unit,
) {
    Row(
        modifier.clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(colors))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(sub, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CategoryChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ToolTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier.glass(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun ShareCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

// ---------------------------------------------------------------------- cards

@Composable
private fun SessionCard(ui: ShareUi, onCopy: (String) -> Unit, onShareLink: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val url = ui.urls.firstOrNull()
    val qr = remember(url) { url?.let { QrUtil.encode(it, 480)?.asImageBitmap() } }
    ShareCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(150.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(8.dp)) {
                if (qr != null) Image(qr, "QR code", Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("Ready to receive", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(ui.deviceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("PIN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    ui.pin.chunked(3).joinToString(" "),
                    color = accent, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 2.sp,
                )
            }
        }
        if (url == null) {
            Text(
                "Waiting for a Wi-Fi or hotspot connection…",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row {
                TextButton(onClick = { onCopy(url) }) {
                    Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy link")
                }
                TextButton(onClick = { onShareLink(url) }) {
                    Icon(Icons.Rounded.Share, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share link")
                }
            }
        }
        Text(
            "Another phone with this app: tap Scan QR, or pick this phone from the radar and type the PIN.\n" +
                "PC or any phone browser: open the link and enter the PIN to send or download files.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HotspotCard(ssid: String, password: String, onStop: () -> Unit) {
    val qr = remember(ssid, password) {
        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
        QrUtil.encode("WIFI:T:WPA;S:${esc(ssid)};P:${esc(password)};;", 420)?.asImageBitmap()
    }
    ShareCard {
        Text("Offline hotspot is on", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(6.dp)) {
                if (qr != null) Image(qr, "Wi-Fi QR", Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("Wi-Fi name", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ssid, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Password", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(password, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(
            "On the other phone: join this Wi-Fi (scan the QR with the camera), then use Scan QR here.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onStop) { Text("Turn hotspot off") }
    }
}

@Composable
private fun OutgoingCard(
    files: List<OutFile>, fmt: (Long) -> String, onRemove: (Long) -> Unit, onClear: () -> Unit,
    onAdd: () -> Unit, hint: String?,
) {
    val total = files.sumOf { it.size.coerceAtLeast(0) }
    ShareCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ready to send", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${files.size} file${if (files.size == 1) "" else "s"} · ${fmt(total)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onAdd) { Text("Add") }
            TextButton(onClick = onClear) { Text("Clear") }
        }
        files.take(5).forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(f.mime), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(fmt(f.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onRemove(f.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, "Remove", modifier = Modifier.size(18.dp))
                }
            }
        }
        if (files.size > 5) {
            Text("+ ${files.size - 5} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun HowItWorks() {
    ShareCard {
        Text("How it works", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        listOf(
            "Put both phones on the same Wi-Fi, or tap Hotspot on one phone and join it from the other.",
            "On the receiving phone tap Receive. It shows a QR code and a PIN.",
            "On the sending phone tap Send, pick files, then scan the QR code (or tap the phone on the radar and type the PIN).",
            "No app on the other side? Open the shown link in any browser, on a phone or a PC.",
        ).forEachIndexed { i, step ->
            Row {
                Text("${i + 1}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                Text(step, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            "Received music and videos are saved in your Music and Movies folders and show up in the library.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TransferRow(
    item: TransferItem, fmt: (Long) -> String, onCancel: () -> Unit, onOpen: () -> Unit, onShare: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val active = item.status == Status.ACTIVE || item.status == Status.QUEUED
    ShareCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(iconFor(item.mime), null, tint = accent) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                val dir = if (item.direction == Direction.IN) "from" else "to"
                val peer = if (item.peer.isBlank()) "" else " $dir ${item.peer}"
                Text(
                    when (item.status) {
                        Status.QUEUED -> "Waiting$peer"
                        Status.ACTIVE -> "${fmt(item.done)} / ${fmt(item.size)}" +
                            (if (item.speedBps > 0) " · ${fmt(item.speedBps)}/s" else "") +
                            (if (item.etaSec >= 0) " · ${eta(item.etaSec)}" else "")
                        Status.DONE -> (if (item.direction == Direction.IN) "Received" else "Sent") + peer + " · ${fmt(item.size)}"
                        Status.FAILED -> "Failed" + (item.error?.let { ": $it" } ?: "")
                        Status.CANCELLED -> "Cancelled"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status == Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                active -> IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel") }
                item.status == Status.DONE -> Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF00BFA5))
                item.status == Status.FAILED -> Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error)
            }
        }
        if (active) {
            LinearProgressIndicator(
                progress = { item.progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = accent, trackColor = Color.White.copy(alpha = 0.1f),
            )
        }
        if (item.status == Status.DONE && item.direction == Direction.IN && item.uri != null) {
            Row {
                TextButton(onClick = onOpen) { Text("Open") }
                TextButton(onClick = onShare) { Text("Share") }
            }
        }
    }
}

// ---------------------------------------------------------------------- dialogs

@Composable
private fun PinDialog(
    title: String, fileCount: Int, onPickFiles: () -> Unit, onDismiss: () -> Unit, onConnect: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        containerColor = GlassDialogColor,
        shape = GlassDialogShape,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "Type the 6-digit PIN shown on that phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    placeholder = { Text("PIN") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (fileCount == 0) {
                    TextButton(onClick = onPickFiles) { Text("No files picked yet. Pick files") }
                } else {
                    Text(
                        "$fileCount file${if (fileCount == 1) "" else "s"} will be sent.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = pin.length == 6, onClick = { onConnect(pin) }) {
                Text(if (fileCount > 0) "Send" else "Connect")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ManualDialog(onDismiss: () -> Unit, onConnect: (String, Int, String) -> Unit) {
    var address by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    val host = address.substringBefore(':').trim()
    val port = address.substringAfter(':', "8765").trim().toIntOrNull() ?: 8765
    AlertDialog(
        containerColor = GlassDialogColor,
        shape = GlassDialogShape,
        onDismissRequest = onDismiss,
        title = { Text("Enter code manually") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Use the address shown under the QR code, like 192.168.1.5:8765.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = address, onValueChange = { address = it }, singleLine = true,
                    placeholder = { Text("192.168.1.5:8765") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    placeholder = { Text("PIN") }, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = host.isNotBlank() && pin.length == 6, onClick = { onConnect(host, port, pin) }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------- helpers

private fun iconFor(mime: String): ImageVector = when {
    mime.startsWith("audio/") -> Icons.Rounded.MusicNote
    mime.startsWith("video/") -> Icons.Rounded.VideoLibrary
    mime.startsWith("image/") -> Icons.Rounded.Image
    else -> Icons.Rounded.Description
}

private fun eta(sec: Long): String = if (sec >= 60) "${sec / 60}m ${sec % 60}s left" else "${sec}s left"

private fun shareText(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, "Share link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun openFile(ctx: Context, item: TransferItem) {
    val uri = item.uri ?: return
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app can open this file.", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(ctx: Context, item: TransferItem) {
    val uri = item.uri ?: return
    val i = Intent(Intent.ACTION_SEND).setType(item.mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Share file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
