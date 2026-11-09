package com.app.musicplayer.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.musicplayer.transfer.FileTransferManager
import com.app.musicplayer.transfer.TransferPeer
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

@Composable
fun FileTransferScreen(manager: FileTransferManager) {
    val context = LocalContext.current
    val state by manager.state.collectAsStateWithLifecycle()
    val wifiDirectName = state.wifiDirectName
    val wifiDirectPassword = state.wifiDirectPassword
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var pairingCode by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val action = pendingAction
        pendingAction = null
        if (result.values.all { it }) {
            permissionMessage = null
            action?.invoke()
        } else {
            permissionMessage = "Nearby devices permission is required for Wi-Fi Direct."
        }
    }

    fun withP2pPermission(action: () -> Unit) {
        val requested = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = requested.filter {
            ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action() else {
            pendingAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) manager.sendFiles(uris, pairingCode)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("Nearby sharing", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Transfer files directly over Wi-Fi Direct. Your files stay on the local network.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            TransferCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { withP2pPermission(manager::startReceiver) },
                        modifier = Modifier.weight(1f),
                        enabled = !state.serverRunning,
                    ) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                        Text(" Receive")
                    }
                    OutlinedButton(
                        onClick = { withP2pPermission(manager::discoverReceivers) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                        Text(if (state.discovering) " Searching…" else " Find device")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                state.error?.takeIf { it != state.message }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                permissionMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.discovering) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Searching nearby…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.connected && !state.pairingUrl.isNullOrBlank()) {
                    state.pairingUrl?.let { url ->
                        Spacer(Modifier.height(8.dp))
                        if (!wifiDirectName.isNullOrBlank() && !wifiDirectPassword.isNullOrBlank()) {
                            Text("1. Scan to join this Wi-Fi Direct network", style = MaterialTheme.typography.titleSmall)
                            QrCode(
                                wifiQr(wifiDirectName, wifiDirectPassword),
                                Modifier.align(Alignment.CenterHorizontally),
                            )
                            Text(
                                "$wifiDirectName · $wifiDirectPassword",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                        }
                        Text("2. Scan to open the upload page", style = MaterialTheme.typography.titleSmall)
                        QrCode(url, Modifier.align(Alignment.CenterHorizontally))
                        state.pairingCode?.let { code ->
                            Text("App pairing code: $code", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                }
                if (state.connected && state.pairingUrl.isNullOrBlank()) {
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.filter(Char::isDigit).take(8) },
                        label = { Text("Receiver pairing code") },
                        supportingText = { Text("Enter the 8-digit code shown on the receiver.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilledTonalButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = pairingCode.length == 8,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                        Text(" Select files to send")
                    }
                }
                if (state.serverRunning || state.connected || state.discovering) {
                    TextButton(onClick = manager::stopSharing, modifier = Modifier.align(Alignment.End)) {
                        Icon(Icons.Rounded.Stop, contentDescription = null)
                        Text(" Stop sharing")
                    }
                }
            }
        }
        if (state.peers.isNotEmpty()) {
            item {
                Text("Nearby devices", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            items(state.peers, key = TransferPeer::address) { peer ->
                PeerCard(peer, onConnect = { manager.connectTo(peer) })
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Received files", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${state.receivedFiles.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.receivedFiles.isEmpty()) {
            item {
                Text(
                    "Files you receive will be kept in the app’s Received folder.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(state.receivedFiles, key = File::getAbsolutePath) { file ->
                TransferCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(formatBytes(file.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            try {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = android.webkit.MimeTypeMap.getSingleton()
                                        .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "Share received file"))
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, "No app can share this file.", Toast.LENGTH_SHORT).show()
                            } catch (e: SecurityException) {
                                Toast.makeText(context, "Could not grant access to this received file.", Toast.LENGTH_SHORT).show()
                            } catch (e: IllegalArgumentException) {
                                Toast.makeText(context, "Could not open this received file.", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Rounded.Share, contentDescription = "Share ${file.name}")
                        }
                    }
                }
            }
        }
        if (state.isTransferring) {
            item {
                TransferCard {
                    Text(state.message, style = MaterialTheme.typography.bodySmall)
                    if (state.progress == null) {
                        androidx.compose.material3.LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    } else {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { state.progress ?: 0f },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun PeerCard(peer: TransferPeer, onConnect: () -> Unit) {
    TransferCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Wi-Fi Direct", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onConnect) { Text("Connect") }
        }
    }
}

@Composable
private fun QrCode(value: String, modifier: Modifier = Modifier) {
    val bitmap = remember(value) {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 480, 480)
        val pixels = IntArray(480 * 480) { index ->
            if (matrix[index % 480, index / 480]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, 480, 0, 0, 480, 480)
        }
    }
    Box(
        modifier = modifier.size(224.dp).clip(RoundedCornerShape(18.dp)).background(androidx.compose.ui.graphics.Color.White).padding(10.dp),
    ) {
        Image(bitmap.asImageBitmap(), contentDescription = "File transfer pairing QR code", contentScale = ContentScale.FillBounds)
    }
}

private fun formatBytes(size: Long): String = when {
    size < 1_000L -> "$size B"
    size < 1_000_000L -> "%.1f KB".format(size / 1_000f)
    size < 1_000_000_000L -> "%.1f MB".format(size / 1_000_000f)
    else -> "%.2f GB".format(size / 1_000_000_000f)
}

private fun wifiQr(ssid: String, password: String): String {
    fun escape(value: String) = value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace(":", "\\:")
        .replace("\"", "\\\"")
    return "WIFI:T:WPA;S:${escape(ssid)};P:${escape(password)};;"
}
