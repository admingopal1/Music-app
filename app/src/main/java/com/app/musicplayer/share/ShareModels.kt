package com.app.musicplayer.share

import android.net.Uri

enum class Direction { IN, OUT }
enum class Status { QUEUED, ACTIVE, DONE, FAILED, CANCELLED }

data class TransferItem(
    val id: Long,
    val name: String,
    val size: Long,
    val direction: Direction,
    val peer: String = "",
    val done: Long = 0L,
    val status: Status = Status.QUEUED,
    val speedBps: Long = 0L,
    val uri: Uri? = null,
    val mime: String = "application/octet-stream",
    val error: String? = null,
    val note: String? = null,
    val secure: Boolean = false,
    val time: Long = System.currentTimeMillis(),
) {
    val progress: Float get() = if (size > 0) (done.toFloat() / size).coerceIn(0f, 1f) else 0f
    val etaSec: Long get() = if (speedBps > 0 && size > done) (size - done) / speedBps else -1L
}

/** A file the user picked to send / offer for download. */
data class OutFile(val id: Long, val uri: Uri, val name: String, val size: Long, val mime: String)

/** [fp] = receiver key fingerprint from its QR code; when present the key exchange is authenticated against it. */
data class Peer(val name: String, val host: String, val port: Int, val fp: String = "")

/** A device asking to send files / open the web player; the user accepts or declines it once per session. */
data class ApprovalRequest(val ip: String, val from: String)

data class HotspotInfo(val ssid: String, val password: String)

data class ShareUi(
    val sessionRunning: Boolean = false,
    val urls: List<String> = emptyList(),
    val pin: String = "",
    val deviceName: String = "",
    val port: Int = 0,
    val peers: List<Peer> = emptyList(),
    val outgoing: List<OutFile> = emptyList(),
    val items: List<TransferItem> = emptyList(),
    val hotspot: HotspotInfo? = null,
    val message: String? = null,
    val libraryChanged: Int = 0,
    val approvals: List<ApprovalRequest> = emptyList(),
    val askBeforeReceive: Boolean = true,
    val playlistsReceived: Int = 0,
)
