package com.app.musicplayer.share

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.app.musicplayer.data.PlaylistStore
import com.app.musicplayer.data.Song
import com.app.musicplayer.service.ShareService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Local-network sharing (same Wi-Fi or a phone hotspot):
 *  - Receive: runs a PIN-protected web server (QR + nearby discovery). Another phone with this app, or any browser/PC, can send files.
 *  - Send: push files to a receiver (scan its QR / pick it from nearby / enter address) or offer them as downloads via a link.
 *  - App-to-app transfers run 3 at a time, are end-to-end encrypted (see [SecureChannel]), resume after a dropped
 *    connection, are verified with SHA-256 and skip files the receiver already has.
 */
class ShareManager private constructor(context: Context) {
    private val app: Context = context.applicationContext
    private val _ui = MutableStateFlow(ShareUi(deviceName = readDeviceName()))
    val ui: StateFlow<ShareUi> = _ui.asStateFlow()

    private val ids = AtomicLong(1)
    private val sendExec = Executors.newFixedThreadPool(PARALLEL_SENDS)
    private val prefs = app.getSharedPreferences("share", Context.MODE_PRIVATE)
    private val cancelled: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())
    private val connections = ConcurrentHashMap<Long, HttpURLConnection>()
    private val failures = HashMap<String, Pair<Int, Long>>()

    private var server: ShareServer? = null
    private var pin = ""
    @Volatile private var accepting = false

    /** Music library exposed to the web player; kept current by the view model. */
    @Volatile var library: List<Song> = emptyList()

    init {
        update { copy(askBeforeReceive = prefs.getBoolean("ask_before_receive", true), items = loadHistory()) }
    }

    // ------------------------------------------------------------------ small helpers

    fun deviceName(): String = _ui.value.deviceName

    private fun readDeviceName(): String {
        val n = try { Settings.Global.getString(app.contentResolver, "device_name") } catch (e: Exception) { null }
        return (n ?: Build.MODEL ?: "Android").take(30)
    }

    private fun update(f: ShareUi.() -> ShareUi) = _ui.update { it.f() }
    private fun msg(m: String?) = update { copy(message = m) }
    fun clearMessage() = msg(null)

    fun fmtSize(b: Long): String = when {
        b < 0 -> "?"
        b >= 1L shl 30 -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
        b >= 1L shl 20 -> "%.1f MB".format(b / (1024.0 * 1024))
        b >= 1L shl 10 -> "%d KB".format(b / 1024)
        else -> "$b B"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ session (receive / offer downloads)

    @Synchronized
    fun startSession() {
        if (server != null) {
            accepting = true
            refreshNetwork()
            return
        }
        var s: ShareServer? = null
        var port = 0
        for (p in 8765..8790) {
            try {
                val candidate = ShareServer(p, this)
                candidate.start(60_000, false)
                s = candidate
                port = p
                break
            } catch (e: IOException) {
                // port busy, try next
            }
        }
        if (s == null) {
            msg("Couldn't start sharing (no free port).")
            return
        }
        server = s
        accepting = true
        pin = String.format("%06d", SecureRandom().nextInt(1_000_000))
        update { copy(sessionRunning = true, port = port, pin = pin, message = null) }
        refreshNetwork()
        registerNsd(port)
        ShareService.start(app)
    }

    @Synchronized
    fun stopSession() {
        server?.stop()
        server = null
        accepting = false
        pin = ""
        unregisterNsd()
        stopHotspot()
        approved.clear()
        sessions.clear()
        waiting.values.forEach { it.countDown() }
        waiting.clear()
        ShareService.clearRequest(app)
        update { copy(sessionRunning = false, urls = emptyList(), pin = "", hotspot = null, approvals = emptyList()) }
        maybeStopService()
    }

    /** Re-reads the phone's IP addresses (they change when Wi-Fi / hotspot changes). */
    fun refreshNetwork() {
        val st = _ui.value
        if (!st.sessionRunning) return
        val ips = localAddresses()
        val urls = ips.map { "http://$it:${st.port}/?pin=${st.pin}&n=${enc(st.deviceName)}" }
        update { copy(urls = urls) }
        if (ips.isEmpty()) msg("Connect to Wi-Fi (or start the hotspot) so other devices can reach this phone.")
        else if (_ui.value.message?.startsWith("Connect to Wi-Fi") == true) msg(null)
    }

    private fun localAddresses(): List<String> {
        val out = ArrayList<Pair<Int, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                val n = ni.name.lowercase()
                val rank = when {
                    n.startsWith("wlan") || n.startsWith("swlan") -> 0
                    n.startsWith("ap") || n.startsWith("p2p") -> 1
                    n.startsWith("eth") -> 2
                    else -> 3
                }
                for (a in ni.inetAddresses.toList()) {
                    if (a is Inet4Address && a.isSiteLocalAddress) out.add(rank to (a.hostAddress ?: continue))
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return out.sortedBy { it.first }.map { it.second }.distinct()
    }

    /** 0 = ok, 1 = wrong pin, 2 = locked out after too many tries. */
    fun checkPin(given: String, ip: String): Int {
        synchronized(failures) {
            val now = System.currentTimeMillis()
            val f = failures[ip]
            val count = if (f != null && now - f.second < 60_000L) f.first else 0
            if (count >= 8) return 2
            if (pin.isNotEmpty() && given == pin) {
                failures.remove(ip)
                return 0
            }
            failures[ip] = (count + 1) to now
            return 1
        }
    }

    fun acceptingUploads(): Boolean = accepting

    // ------------------------------------------------------------------ approval (ask before receiving)

    private val approved = ConcurrentHashMap<String, Boolean>()
    private val waiting = ConcurrentHashMap<String, CountDownLatch>()

    fun setAskBeforeReceive(on: Boolean) {
        prefs.edit().putBoolean("ask_before_receive", on).apply()
        update { copy(askBeforeReceive = on) }
    }

    /**
     * true = allowed, false = declined, null = still waiting for the user.
     * With [block] the caller waits (up to a minute) for the user to answer the prompt on this phone.
     */
    fun approval(ip: String, from: String, block: Boolean): Boolean? {
        if (!_ui.value.askBeforeReceive) return true
        approved[ip]?.let { return it }
        val latch = waiting.computeIfAbsent(ip) {
            update { copy(approvals = approvals.filterNot { a -> a.ip == ip } + ApprovalRequest(ip, from.ifBlank { ip })) }
            ShareService.notifyRequest(app, from.ifBlank { ip })
            CountDownLatch(1)
        }
        if (!block) return null
        latch.await(60, TimeUnit.SECONDS)
        return approved[ip]
    }

    fun decide(ip: String, allow: Boolean) {
        approved[ip] = allow
        waiting.remove(ip)?.countDown()
        update { copy(approvals = approvals.filterNot { it.ip == ip }) }
        if (_ui.value.approvals.isEmpty()) ShareService.clearRequest(app)
    }

    // ------------------------------------------------------------------ secure, resumable receive (app to app)

    private val sessions = ConcurrentHashMap<String, ByteArray>()
    private val keyItems = ConcurrentHashMap<String, Long>()
    private val partialDir by lazy { File(app.cacheDir, "incoming").apply { mkdirs() } }

    private fun partial(key: String) = File(partialDir, key.filter { it.isLetterOrDigit() }.take(64).ifEmpty { "x" })

    /** Key exchange; returns the JSON reply for the sender. */
    fun hello(peerPub: String): String {
        val kp = SecureChannel.newKeyPair()
        val key = SecureChannel.deriveKey(kp, peerPub, pin)
        val sid = SecureChannel.hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        sessions[sid] = key
        return JSONObject().put("sid", sid).put("pub", SecureChannel.encodePub(kp)).put("name", deviceName()).toString()
    }

    fun hasSession(sid: String) = sessions.containsKey(sid)

    /** Bytes already received for [key] (to resume), or -1 when this phone already has the file. */
    fun status(key: String, name: String, size: Long): Long {
        if (alreadyHave(name, size)) return -1L
        val f = partial(key)
        return if (f.exists()) f.length().coerceAtMost(size) else 0L
    }

    private fun alreadyHave(name: String, size: Long): Boolean {
        if (size <= 0) return false
        val received = _ui.value.items.any {
            it.direction == Direction.IN && it.status == Status.DONE && it.name == name && it.size == size && it.uri != null &&
                runCatching { app.contentResolver.openFileDescriptor(it.uri, "r")?.use { true } }.getOrNull() == true
        }
        if (received) return true
        val mime = guessMime(name)
        val collection = when {
            mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> return false
        }
        return try {
            app.contentResolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.SIZE}=?", arrayOf(name, size.toString()), null,
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun itemForKey(key: String, name: String, size: Long, from: String, secure: Boolean): Long {
        val existing = keyItems[key]?.takeIf { id -> _ui.value.items.any { it.id == id } }
        if (existing != null) return existing
        val item = newItem(name, size, Direction.IN, from, null, guessMime(name))
        setItem(item.id) { copy(secure = secure) }
        keyItems[key] = item.id
        return item.id
    }

    /**
     * Appends one (encrypted) upload body to the partial file for [key], starting at plaintext [offset].
     * Returns the number of bytes now stored, or -1 for an unknown session.
     */
    fun receivePart(key: String, name: String, size: Long, offset: Long, sid: String?, input: InputStream, from: String): Long {
        val aes = sid?.let { sessions[it] ?: return -1L }
        val id = itemForKey(key, name, size, from, aes != null)
        ShareService.start(app)
        val f = partial(key)
        RandomAccessFile(f, "rw").use { it.setLength(offset.coerceAtMost(it.length())) }
        var done = f.length()
        if (done != offset) return done
        val tracker = Tracker(id, done)
        setItem(id) { copy(status = Status.ACTIVE, done = offset, error = null, note = null) }
        try {
            java.io.FileOutputStream(f, true).buffered(256 * 1024).use { out ->
                if (aes != null) {
                    val din = DataInputStream(input)
                    while (done < size) {
                        if (id in cancelled) throw IOException("cancelled")
                        val plain = SecureChannel.readFrame(aes, din) ?: break
                        out.write(plain)
                        done += plain.size
                        tracker.add(done)
                    }
                } else {
                    val buf = ByteArray(256 * 1024)
                    while (done < size) {
                        if (id in cancelled) throw IOException("cancelled")
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), size - done).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        tracker.add(done)
                    }
                }
            }
        } catch (e: Exception) {
            val wasCancelled = id in cancelled
            if (wasCancelled) f.delete()
            setItem(id) {
                copy(
                    status = if (wasCancelled) Status.CANCELLED else Status.QUEUED, speedBps = 0,
                    done = f.length(), note = if (wasCancelled) null else "Paused, will resume",
                )
            }
            maybeStopService()
            return f.length()
        }
        setItem(id) { copy(done = done) }
        return done
    }

    /** Verifies the SHA-256 of a fully received file and saves it. 0 = saved, 1 = checksum mismatch, 2 = incomplete. */
    fun finish(key: String, name: String, size: Long, sha: String, from: String): Int {
        val f = partial(key)
        val id = itemForKey(key, name, size, from, false)
        try {
            if (!f.exists() || f.length() != size) return 2
            val d = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(256 * 1024)
                while (true) { val n = ins.read(buf); if (n < 0) break; d.update(buf, 0, n) }
            }
            if (!SecureChannel.sha256Hex(d).equals(sha, ignoreCase = true)) {
                f.delete()
                setItem(id) { copy(status = Status.QUEUED, done = 0, note = "Checksum mismatch, resending") }
                return 1
            }
            if (name.endsWith(PLAYLIST_EXT)) {
                storePendingPlaylist(f.readText())
                setItem(id) { copy(status = Status.DONE, speedBps = 0, done = size, note = "Playlist will appear in Playlists") }
                update { copy(playlistsReceived = playlistsReceived + 1) }
                return 0
            }
            val mime = guessMime(name)
            val target = createIncoming(name, mime) ?: throw IOException("Can't create the file on this phone")
            try {
                target.out.use { out -> f.inputStream().use { it.copyTo(out, 256 * 1024) } }
                target.finish()
            } catch (e: Exception) {
                target.discard()
                throw e
            }
            f.delete()
            keyItems.remove(key)
            setItem(id) { copy(status = Status.DONE, uri = target.uri, speedBps = 0, done = size, note = "Verified (SHA-256)") }
            if (mime.startsWith("audio/") || mime.startsWith("video/")) update { copy(libraryChanged = libraryChanged + 1) }
            return 0
        } catch (e: Exception) {
            setItem(id) { copy(status = Status.FAILED, speedBps = 0, error = e.message) }
            return 2
        } finally {
            maybeStopService()
        }
    }

    // ------------------------------------------------------------------ files to send / offer

    fun addOutgoing(uris: List<Uri>) {
        val added = ArrayList<OutFile>()
        for (u in uris) {
            if (_ui.value.outgoing.any { it.uri == u }) continue
            var name = u.lastPathSegment?.substringAfterLast('/') ?: "file"
            var size = -1L
            try {
                app.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            } catch (e: Exception) {
                // use fallbacks
            }
            val mime = app.contentResolver.getType(u) ?: guessMime(name)
            added.add(OutFile(ids.getAndIncrement(), u, name, size, mime))
        }
        if (added.isNotEmpty()) update { copy(outgoing = outgoing + added) }
    }

    fun removeOutgoing(id: Long) = update { copy(outgoing = outgoing.filterNot { it.id == id }) }
    fun clearOutgoing() = update { copy(outgoing = emptyList()) }
    fun outFiles(): List<OutFile> = _ui.value.outgoing
    fun outFile(id: Long): OutFile? = _ui.value.outgoing.firstOrNull { it.id == id }
    fun openOut(f: OutFile): InputStream? = try { app.contentResolver.openInputStream(f.uri) } catch (e: Exception) { null }

    // ------------------------------------------------------------------ transfer bookkeeping

    private fun newItem(name: String, size: Long, dir: Direction, peer: String, uri: Uri?, mime: String): TransferItem {
        val item = TransferItem(ids.getAndIncrement(), name, size, dir, peer, uri = uri, mime = mime)
        update { copy(items = (listOf(item) + items).take(100)) }
        return item
    }

    private fun setItem(id: Long, f: TransferItem.() -> TransferItem) {
        var finished = false
        update {
            copy(items = items.map {
                if (it.id == id) it.f().also { n -> finished = n.status.terminal && (!it.status.terminal || n.note != it.note) } else it
            })
        }
        if (finished) saveHistory()
    }

    private val Status.terminal get() = this == Status.DONE || this == Status.FAILED || this == Status.CANCELLED

    private fun saveHistory() {
        val arr = JSONArray()
        _ui.value.items.filter { it.status.terminal }.take(100).forEach {
            arr.put(
                JSONObject().put("name", it.name).put("size", it.size).put("dir", it.direction.name).put("peer", it.peer)
                    .put("status", it.status.name).put("uri", it.uri?.toString() ?: "").put("mime", it.mime)
                    .put("error", it.error ?: "").put("note", it.note ?: "").put("secure", it.secure).put("time", it.time),
            )
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    private fun loadHistory(): List<TransferItem> = try {
        val arr = JSONArray(prefs.getString("history", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val size = o.optLong("size")
            val status = Status.valueOf(o.optString("status"))
            TransferItem(
                ids.getAndIncrement(), o.optString("name"), size, Direction.valueOf(o.optString("dir")), o.optString("peer"),
                done = if (status == Status.DONE) size else 0L, status = status,
                uri = o.optString("uri").takeIf { it.isNotEmpty() }?.let(Uri::parse), mime = o.optString("mime"),
                error = o.optString("error").ifEmpty { null }, note = o.optString("note").ifEmpty { null },
                secure = o.optBoolean("secure"), time = o.optLong("time"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun cancel(id: Long) {
        cancelled.add(id)
        connections[id]?.disconnect()
        setItem(id) { if (status == Status.QUEUED || status == Status.ACTIVE) copy(status = Status.CANCELLED, speedBps = 0) else this }
    }

    fun clearHistory() {
        update { copy(items = items.filter { it.status == Status.ACTIVE || it.status == Status.QUEUED }) }
        saveHistory()
    }

    private fun maybeStopService() {
        val u = _ui.value
        val busy = u.items.any { it.status == Status.ACTIVE || it.status == Status.QUEUED }
        if (!u.sessionRunning && !busy) ShareService.stop(app)
    }

    /** Updates an item's progress at most ~4 times a second and measures speed. */
    private inner class Tracker(private val id: Long, startAt: Long = 0L) {
        private var lastTime = System.currentTimeMillis()
        private var lastBytes = startAt
        fun add(done: Long) {
            val now = System.currentTimeMillis()
            val dt = now - lastTime
            if (dt < 250) return
            val speed = ((done - lastBytes) * 1000L) / dt
            lastTime = now
            lastBytes = done
            setItem(id) { copy(done = done, speedBps = speed, status = Status.ACTIVE) }
        }
    }

    // ------------------------------------------------------------------ receiving

    private class Incoming(val uri: Uri, val out: OutputStream, val finish: () -> Unit, val discard: () -> Unit)

    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun createIncoming(name: String, mime: String): Incoming? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val (collection, folder) = when {
                    mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Music/MusicPlayer"
                    mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies/MusicPlayer"
                    mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Pictures/MusicPlayer"
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/MusicPlayer"
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = app.contentResolver
                val uri = resolver.insert(collection, values) ?: return null
                val out = resolver.openOutputStream(uri) ?: return null
                Incoming(
                    uri, out,
                    finish = {
                        val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        resolver.update(uri, v, null, null)
                    },
                    discard = { runCatching { resolver.delete(uri, null, null) } },
                )
            } else {
                val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "Received").apply { mkdirs() }
                var f = File(dir, name)
                var n = 1
                val base = name.substringBeforeLast('.', name)
                val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
                while (f.exists()) f = File(dir, "$base ($n)$ext").also { n++ }
                val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", f)
                Incoming(uri, f.outputStream(), finish = {}, discard = { f.delete() })
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Called by the server for every uploaded file. Returns true when the file was saved. */
    fun receiveStream(name: String, length: Long, input: InputStream, from: String): Boolean {
        val mime = guessMime(name)
        val item = newItem(name, length, Direction.IN, from, null, mime)
        ShareService.start(app)
        val target = createIncoming(name, mime)
        if (target == null) {
            setItem(item.id) { copy(status = Status.FAILED, error = "Can't create the file on this phone") }
            return false
        }
        val tracker = Tracker(item.id)
        var ok = false
        try {
            target.out.use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    if (item.id in cancelled) throw IOException("cancelled")
                    val want = if (length >= 0) minOf(buf.size.toLong(), length - done).toInt() else buf.size
                    if (want <= 0) break
                    val n = input.read(buf, 0, want)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    tracker.add(done)
                }
                if (length >= 0 && done < length) throw IOException("Connection lost")
                setItem(item.id) { copy(done = done) }
            }
            target.finish()
            setItem(item.id) { copy(status = Status.DONE, uri = target.uri, speedBps = 0, done = if (size > 0) size else done) }
            if (mime.startsWith("audio/") || mime.startsWith("video/")) update { copy(libraryChanged = libraryChanged + 1) }
            ok = true
        } catch (e: Exception) {
            target.discard()
            val wasCancelled = item.id in cancelled
            setItem(item.id) {
                copy(status = if (wasCancelled) Status.CANCELLED else Status.FAILED, speedBps = 0, error = if (wasCancelled) null else e.message)
            }
        } finally {
            maybeStopService()
        }
        return ok
    }

    // ------------------------------------------------------------------ browser / link downloads (we are the sender)

    fun countingStream(raw: InputStream, f: OutFile, ip: String, startAt: Long): InputStream {
        val item = newItem(f.name, f.size, Direction.OUT, ip, f.uri, f.mime)
        ShareService.start(app)
        setItem(item.id) { copy(status = Status.ACTIVE, done = startAt) }
        val tracker = Tracker(item.id, startAt)
        return object : FilterInputStream(raw) {
            var done = startAt
            override fun read(): Int {
                val b = super.read()
                if (b >= 0) { done++; tracker.add(done) }
                return b
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) { done += n; tracker.add(done) }
                return n
            }
            override fun close() {
                super.close()
                val complete = f.size <= 0 || done >= f.size
                setItem(item.id) {
                    copy(done = done, speedBps = 0, status = if (complete) Status.DONE else Status.FAILED, error = if (complete) null else "Interrupted")
                }
                maybeStopService()
            }
        }
    }

    // ------------------------------------------------------------------ sending to another phone (app to app)

    /** Parses a QR / link like http://192.168.1.5:8765/?pin=123456&n=Redmi. */
    fun parseConnect(text: String): Pair<Peer, String>? = try {
        val u = Uri.parse(text.trim())
        val host = u.host
        val pinParam = u.getQueryParameter("pin")
        if ((u.scheme == "http") && !host.isNullOrBlank() && !pinParam.isNullOrBlank()) {
            Peer(u.getQueryParameter("n") ?: host, host, if (u.port > 0) u.port else 80) to pinParam
        } else null
    } catch (e: Exception) {
        null
    }

    private data class SendJob(val peer: Peer, val pin: String, val file: OutFile)
    private class FatalSend(msg: String) : IOException(msg)
    private class LegacyReceiver : Exception("Receiver runs an older app version")
    private class PeerSession(val sid: String, val key: ByteArray)

    private val jobs = ConcurrentHashMap<Long, SendJob>()
    private val peerSessions = ConcurrentHashMap<String, PeerSession>()

    fun sendOutgoingTo(peer: Peer, pin: String) {
        val files = _ui.value.outgoing
        if (files.isEmpty()) {
            msg("Pick some files to send first.")
            return
        }
        ShareService.start(app)
        for (f in files) {
            val item = newItem(f.name, f.size, Direction.OUT, peer.name, f.uri, f.mime)
            jobs[item.id] = SendJob(peer, pin, f)
            sendExec.execute { sendOne(item.id) }
        }
        clearOutgoing()
    }

    /** Re-queues a failed or cancelled send; it resumes where it stopped. */
    fun retry(id: Long) {
        if (jobs[id] == null) return
        cancelled.remove(id)
        setItem(id) { copy(status = Status.QUEUED, error = null, note = null, speedBps = 0) }
        ShareService.start(app)
        sendExec.execute { sendOne(id) }
    }

    fun canRetry(id: Long) = jobs.containsKey(id)

    private fun sendOne(id: Long) {
        val job = jobs[id] ?: return
        var attempt = 0
        var legacy = false
        try {
            while (true) {
                if (id in cancelled) return
                setItem(id) { copy(status = Status.ACTIVE, error = null) }
                try {
                    if (job.file.size > 0 && !legacy) {
                        try {
                            sendResumable(job, id)
                        } catch (e: LegacyReceiver) {
                            legacy = true
                            sendLegacy(job, id)
                        }
                    } else {
                        legacy = true
                        sendLegacy(job, id)
                    }
                    return
                } catch (e: FatalSend) {
                    setItem(id) { copy(status = Status.FAILED, speedBps = 0, error = e.message, note = null) }
                    return
                } catch (e: Exception) {
                    if (id in cancelled) {
                        setItem(id) { copy(status = Status.CANCELLED, speedBps = 0, note = null) }
                        return
                    }
                    // Legacy uploads can't resume or be de-duplicated, so a blind retry could save a second copy.
                    if (legacy || ++attempt >= MAX_ATTEMPTS) {
                        setItem(id) { copy(status = Status.FAILED, speedBps = 0, error = e.message ?: "Connection failed", note = null) }
                        return
                    }
                    setItem(id) { copy(speedBps = 0, note = "Connection dropped, resuming… ($attempt/${MAX_ATTEMPTS - 1})") }
                    Thread.sleep(1500L * attempt)
                }
            }
        } finally {
            connections.remove(id)
            maybeStopService()
        }
    }

    private fun base(job: SendJob) = "http://${job.peer.host}:${job.peer.port}"

    /** Sessions are per receiver address *and* PIN, so a restarted receiver (new PIN) gets a fresh key exchange. */
    private fun sessionKey(job: SendJob) = "${job.peer.host}:${job.peer.port}:${job.pin}"

    private fun open(url: String, method: String, readTimeout: Int = 30_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 6000
            this.readTimeout = readTimeout
        }

    private fun HttpURLConnection.body(): String =
        (if (responseCode < 400) inputStream else errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()

    /** Gets (or creates) an encrypted session with the receiver. Null when the receiver runs an older app version. */
    private fun session(job: SendJob): PeerSession? {
        val k = sessionKey(job)
        peerSessions[k]?.let { return it }
        synchronized(peerSessions) {
            peerSessions[k]?.let { return it }
            val kp = SecureChannel.newKeyPair()
            val c = open(
                "${base(job)}/hello?pin=${enc(job.pin)}&from=${enc(deviceName())}&pub=${SecureChannel.encodePub(kp)}", "POST", 75_000,
            )
            try {
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
                when (c.responseCode) {
                    200 -> {}
                    404 -> return null
                    403 -> throw FatalSend(c.body().ifBlank { "Wrong PIN or the receiver declined" })
                    else -> throw IOException("Receiver answered ${c.responseCode}")
                }
                val j = JSONObject(c.body())
                val s = PeerSession(j.getString("sid"), SecureChannel.deriveKey(kp, j.getString("pub"), job.pin))
                peerSessions[k] = s
                return s
            } finally {
                c.disconnect()
            }
        }
    }

    private fun sendResumable(job: SendJob, id: Long) {
        val f = job.file
        val s = session(job) ?: throw LegacyReceiver()
        val key = SecureChannel.hex(
            MessageDigest.getInstance("SHA-256").digest("${deviceName()}|${f.uri}|${f.name}|${f.size}".toByteArray()),
        ).take(32)
        val q = "pin=${enc(job.pin)}&key=$key&name=${enc(f.name)}&size=${f.size}&sid=${s.sid}&from=${enc(deviceName())}"

        // 1. how much does the receiver already have?
        val st = open("${base(job)}/status?$q", "GET")
        val have = try {
            if (st.responseCode == 401) { peerSessions.remove(sessionKey(job)); throw IOException("Session expired") }
            if (st.responseCode == 403) throw FatalSend(st.body().ifBlank { "Wrong PIN or the receiver declined" })
            if (st.responseCode != 200) throw IOException("Receiver answered ${st.responseCode}")
            JSONObject(st.body()).getLong("have")
        } finally {
            st.disconnect()
        }
        if (have < 0) {
            setItem(id) { copy(status = Status.DONE, speedBps = 0, done = size, note = "Already on ${job.peer.name}, skipped", secure = true) }
            return
        }

        // 2. upload the rest (encrypted), hashing the whole file on the way
        val digest = MessageDigest.getInstance("SHA-256")
        val input = app.contentResolver.openInputStream(f.uri) ?: throw FatalSend("Can't read the file")
        DigestInputStream(input.buffered(256 * 1024), digest).use { ins ->
            val skipBuf = ByteArray(256 * 1024)
            var skipped = 0L
            while (skipped < have) {
                val n = ins.read(skipBuf, 0, minOf(skipBuf.size.toLong(), have - skipped).toInt())
                if (n < 0) throw FatalSend("File changed while sending")
                skipped += n
            }
            if (have < f.size) {
                setItem(id) { copy(done = have, secure = true, note = if (have > 0) "Resumed at ${fmtSize(have)}" else null) }
                val c = open("${base(job)}/upload2?$q&offset=$have", "PUT", 120_000)
                connections[id] = c
                try {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(SecureChannel.encryptedLength(f.size - have))
                    c.setRequestProperty("Content-Type", "application/octet-stream")
                    val tracker = Tracker(id, have)
                    c.outputStream.use { out ->
                        SecureChannel.encrypt(s.key, ins, out, f.size - have) { n ->
                            if (id in cancelled) throw IOException("cancelled")
                            tracker.add(have + n)
                        }
                    }
                    when (c.responseCode) {
                        200 -> {}
                        401 -> { peerSessions.remove(sessionKey(job)); throw IOException("Session expired") }
                        403 -> throw FatalSend(c.body().ifBlank { "Receiver declined" })
                        else -> throw IOException("Receiver answered ${c.responseCode}")
                    }
                } finally {
                    connections.remove(id)
                    c.disconnect()
                }
            }
            // drain anything left so the hash covers the whole file
            while (ins.read(skipBuf) >= 0) Unit
        }

        // 3. receiver verifies the checksum and saves the file
        val fin = open("${base(job)}/finish?$q&sha=${SecureChannel.sha256Hex(digest)}", "POST", 120_000)
        try {
            fin.doOutput = true
            fin.setFixedLengthStreamingMode(0)
            fin.outputStream.close()
            when (fin.responseCode) {
                200 -> setItem(id) { copy(status = Status.DONE, speedBps = 0, done = size, note = "Encrypted · verified (SHA-256)") }
                409 -> throw IOException("Checksum mismatch, resending")
                403 -> throw FatalSend(fin.body().ifBlank { "Receiver declined" })
                else -> throw IOException("Receiver answered ${fin.responseCode}")
            }
        } finally {
            fin.disconnect()
        }
    }

    /** Plain single-request upload: unknown file size, or a receiver running an older app version. */
    private fun sendLegacy(job: SendJob, id: Long) {
        val f = job.file
        val c = open("${base(job)}/upload?pin=${enc(job.pin)}&name=${enc(f.name)}&from=${enc(deviceName())}", "PUT", 120_000)
        connections[id] = c
        try {
            c.doOutput = true
            if (f.size > 0) c.setFixedLengthStreamingMode(f.size) else c.setChunkedStreamingMode(64 * 1024)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            val input = app.contentResolver.openInputStream(f.uri) ?: throw FatalSend("Can't read the file")
            val tracker = Tracker(id)
            var done = 0L
            input.use { ins ->
                c.outputStream.use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (id in cancelled) throw IOException("cancelled")
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        tracker.add(done)
                    }
                }
            }
            when (val code = c.responseCode) {
                200 -> setItem(id) { copy(status = Status.DONE, speedBps = 0, done = if (size > 0) size else done) }
                403 -> throw FatalSend(c.body().ifBlank { "Wrong PIN or receiver is not accepting files" })
                else -> throw IOException("Receiver answered $code")
            }
        } finally {
            connections.remove(id)
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ smart music share

    /** Queues a playlist's songs plus a small manifest so the receiver can rebuild the playlist. */
    fun sharePlaylist(name: String, songs: List<Song>) {
        if (songs.isEmpty()) return
        addOutgoing(songs.map { it.uri })
        val json = JSONObject().put("name", name).put(
            "songs",
            JSONArray().apply {
                songs.forEach { put(JSONObject().put("file", it.fileName).put("title", it.title).put("artist", it.artist)) }
            },
        )
        val dir = File(app.cacheDir, "outgoing").apply { mkdirs() }
        val file = File(dir, name.replace(Regex("[^A-Za-z0-9 _-]"), "_").take(60) + PLAYLIST_EXT)
        file.writeText(json.toString())
        val out = OutFile(ids.getAndIncrement(), Uri.fromFile(file), file.name, file.length(), "application/json")
        update { copy(outgoing = outgoing.filterNot { it.name == out.name } + out) }
    }

    private fun storePendingPlaylist(text: String) {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return
        val arr = runCatching { JSONArray(prefs.getString("pending_playlists", "[]")) }.getOrDefault(JSONArray())
        arr.put(j.put("received", System.currentTimeMillis()))
        prefs.edit().putString("pending_playlists", arr.toString()).apply()
    }

    /** Creates received playlists from songs now in the library. Unmatched songs are retried for 2 days. */
    fun resolvePlaylists(songs: List<Song>, store: PlaylistStore): Int {
        val arr = runCatching { JSONArray(prefs.getString("pending_playlists", "[]")) }.getOrDefault(JSONArray())
        if (arr.length() == 0 || songs.isEmpty()) return 0
        val byFile = songs.groupBy { it.fileName.lowercase() }
        val byTitle = songs.groupBy { (it.title + "|" + it.artist).lowercase() }
        val keep = JSONArray()
        var created = 0
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val name = p.optString("name").ifBlank { "Shared playlist" }
            val list = p.optJSONArray("songs") ?: JSONArray()
            val ids = ArrayList<Long>()
            var missing = 0
            for (k in 0 until list.length()) {
                val e = list.getJSONObject(k)
                val match = byFile[e.optString("file").lowercase()]?.firstOrNull()
                    ?: byTitle[(e.optString("title") + "|" + e.optString("artist")).lowercase()]?.firstOrNull()
                if (match != null) ids += match.id else missing++
            }
            if (ids.isNotEmpty()) {
                store.create(name)
                ids.forEach { store.addSong(name, it) }
                created++
            }
            val fresh = System.currentTimeMillis() - p.optLong("received") < 2 * 86_400_000L
            if (missing > 0 && fresh) keep.put(p)
        }
        prefs.edit().putString("pending_playlists", keep.toString()).apply()
        return created
    }

    // ------------------------------------------------------------------ web player

    fun song(id: Long): Song? = library.firstOrNull { it.id == id }

    fun sizeOf(uri: Uri): Long = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    fun openUri(uri: Uri): InputStream? = try { app.contentResolver.openInputStream(uri) } catch (e: Exception) { null }

    // ------------------------------------------------------------------ nearby discovery (NSD)

    private val nsd: NsdManager? = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var myServiceName: String? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private fun displayName(service: String) = service.removePrefix("MS-").replace(Regex("\\s\\(\\d+\\)$"), "")

    private fun registerNsd(port: Int) {
        val m = nsd ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "MS-" + deviceName().take(24)
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { myServiceName = i.serviceName }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        regListener = l
        runCatching { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun unregisterNsd() {
        val m = nsd ?: return
        regListener?.let { l -> runCatching { m.unregisterService(l) } }
        regListener = null
        myServiceName = null
    }

    fun startDiscovery() {
        val m = nsd ?: return
        if (discListener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceName != myServiceName) enqueueResolve(i)
            }
            override fun onServiceLost(i: NsdServiceInfo) {
                val n = displayName(i.serviceName)
                update { copy(peers = peers.filterNot { it.name == n }) }
            }
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, c: Int) { discListener = null }
            override fun onStopDiscoveryFailed(t: String, c: Int) {}
        }
        discListener = l
        runCatching { m.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stopDiscovery() {
        val m = nsd ?: return
        discListener?.let { l -> runCatching { m.stopServiceDiscovery(l) } }
        discListener = null
        synchronized(resolveQueue) { resolveQueue.clear(); resolving = false }
        update { copy(peers = emptyList()) }
    }

    private fun enqueueResolve(i: NsdServiceInfo) {
        synchronized(resolveQueue) { resolveQueue.addLast(i) }
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val m = nsd ?: return
        var pending: NsdServiceInfo? = null
        synchronized(resolveQueue) {
            if (!resolving && resolveQueue.isNotEmpty()) {
                pending = resolveQueue.removeFirst()
                resolving = true
            }
        }
        val next = pending ?: return
        val done = {
            synchronized(resolveQueue) { resolving = false }
            resolveNext()
        }
        try {
            m.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) { done() }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val host = i.host?.hostAddress
                    if (host != null && !host.contains(':') && i.port > 0 && host !in localAddresses()) {
                        val peer = Peer(displayName(i.serviceName), host, i.port)
                        update { copy(peers = peers.filterNot { it.host == host && it.port == peer.port } + peer) }
                    }
                    done()
                }
            })
        } catch (e: Exception) {
            done()
        }
    }

    // ------------------------------------------------------------------ offline hotspot (no router needed)

    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun startHotspot() {
        if (Build.VERSION.SDK_INT < 26) {
            msg("Hotspot sharing needs Android 8 or newer.")
            return
        }
        if (hotspot != null) return
        startSession()
        startHotspotO()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    @RequiresApi(26)
    private fun startHotspotO() {
        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    hotspot = r
                    var ssid = ""
                    var pass = ""
                    if (Build.VERSION.SDK_INT >= 30) {
                        val c = r.softApConfiguration
                        ssid = c.ssid ?: ""
                        pass = c.passphrase ?: ""
                    } else {
                        val c = r.wifiConfiguration
                        ssid = c?.SSID ?: ""
                        pass = c?.preSharedKey ?: ""
                    }
                    update { copy(hotspot = HotspotInfo(ssid.trim('"'), pass.trim('"'))) }
                    Handler(Looper.getMainLooper()).postDelayed({ refreshNetwork() }, 1500)
                }
                override fun onStopped() { hotspot = null; update { copy(hotspot = null) } }
                override fun onFailed(reason: Int) {
                    msg("Couldn't start the hotspot (code $reason). Turn on Location and Wi-Fi, then try again.")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            msg("Hotspot failed: ${e.message}")
        }
    }

    fun stopHotspot() {
        if (Build.VERSION.SDK_INT >= 26) runCatching { hotspot?.close() }
        hotspot = null
        update { copy(hotspot = null) }
    }

    companion object {
        private const val SERVICE_TYPE = "_musicshare._tcp."
        private const val PARALLEL_SENDS = 3
        private const val MAX_ATTEMPTS = 4
        const val PLAYLIST_EXT = ".mplaylist.json"

        @Volatile private var instance: ShareManager? = null
        fun get(context: Context): ShareManager =
            instance ?: synchronized(this) { instance ?: ShareManager(context).also { instance = it } }
    }
}
