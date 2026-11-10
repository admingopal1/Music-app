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
import androidx.core.content.FileProvider
import com.app.musicplayer.service.ShareService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Local-network sharing (same Wi-Fi or a phone hotspot):
 *  - Receive: runs a PIN-protected web server (QR + nearby discovery). Another phone with this app, or any browser/PC, can send files.
 *  - Send: push files to a receiver (scan its QR / pick it from nearby / enter address) or offer them as downloads via a link.
 */
class ShareManager private constructor(context: Context) {
    private val app: Context = context.applicationContext
    private val _ui = MutableStateFlow(ShareUi(deviceName = readDeviceName()))
    val ui: StateFlow<ShareUi> = _ui.asStateFlow()

    private val ids = AtomicLong(1)
    private val sendExec = Executors.newSingleThreadExecutor()
    private val cancelled: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())
    private val connections = ConcurrentHashMap<Long, HttpURLConnection>()
    private val failures = HashMap<String, Pair<Int, Long>>()

    private var server: ShareServer? = null
    @Volatile private var pin = ""
    @Volatile private var accepting = false

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
        update { copy(sessionRunning = false, urls = emptyList(), pin = "", hotspot = null) }
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
            failures.entries.removeAll { now - it.value.second >= 60_000L }
            if (ip !in failures && failures.size >= 512) {
                failures.minByOrNull { it.value.second }?.key?.let(failures::remove)
            }
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

    private fun setItem(id: Long, f: TransferItem.() -> TransferItem) =
        update { copy(items = items.map { if (it.id == id) it.f() else it }) }

    fun cancel(id: Long) {
        cancelled.add(id)
        connections[id]?.disconnect()
        setItem(id) { if (status == Status.QUEUED || status == Status.ACTIVE) copy(status = Status.CANCELLED, speedBps = 0) else this }
    }

    fun clearHistory() {
        update { copy(items = items.filter { it.status == Status.ACTIVE || it.status == Status.QUEUED }) }
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
                val out = resolver.openOutputStream(uri)
                if (out == null) {
                    resolver.delete(uri, null, null)
                    return null
                }
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
                    if (done + n > MAX_UPLOAD_BYTES) throw IOException("File exceeds the 2 GiB upload limit")
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
            cancelled.remove(item.id)
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

    fun sendOutgoingTo(peer: Peer, pin: String) {
        val files = _ui.value.outgoing
        if (files.isEmpty()) {
            msg("Pick some files to send first.")
            return
        }
        ShareService.start(app)
        for (f in files) {
            val item = newItem(f.name, f.size, Direction.OUT, peer.name, f.uri, f.mime)
            sendExec.execute { sendOne(peer, pin, f, item.id) }
        }
        clearOutgoing()
    }

    private fun sendOne(peer: Peer, pin: String, f: OutFile, id: Long) {
        if (id in cancelled) {
            cancelled.remove(id)
            maybeStopService()
            return
        }
        setItem(id) { copy(status = Status.ACTIVE) }
        var conn: HttpURLConnection? = null
        try {
            val url = URL("http://${peer.host}:${peer.port}/upload?pin=${enc(pin)}&name=${enc(f.name)}&from=${enc(deviceName())}")
            val c = url.openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "PUT"
            c.doOutput = true
            c.connectTimeout = 6000
            c.readTimeout = 120_000
            if (f.size > 0) c.setFixedLengthStreamingMode(f.size) else c.setChunkedStreamingMode(64 * 1024)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            connections[id] = c
            val input = app.contentResolver.openInputStream(f.uri) ?: throw IOException("Can't read the file")
            val tracker = Tracker(id)
            input.use { ins ->
                c.outputStream.use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        if (id in cancelled) throw IOException("cancelled")
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        tracker.add(done)
                    }
                    setItem(id) { copy(done = done) }
                }
            }
            when (val code = c.responseCode) {
                200 -> setItem(id) { copy(status = Status.DONE, speedBps = 0, done = if (size > 0) size else done) }
                403 -> setItem(id) { copy(status = Status.FAILED, speedBps = 0, error = "Wrong PIN or receiver is not accepting files") }
                else -> setItem(id) { copy(status = Status.FAILED, speedBps = 0, error = "Receiver answered $code") }
            }
        } catch (e: Exception) {
            val wasCancelled = id in cancelled
            setItem(id) {
                copy(
                    status = if (wasCancelled) Status.CANCELLED else Status.FAILED, speedBps = 0,
                    error = if (wasCancelled) null else (e.message ?: "Connection failed"),
                )
            }
        } finally {
            connections.remove(id)
            conn?.disconnect()
            cancelled.remove(id)
            maybeStopService()
        }
    }

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
        runCatching { hotspot?.close() }
        hotspot = null
        update { copy(hotspot = null) }
    }

    companion object {
        const val MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024
        private const val SERVICE_TYPE = "_musicshare._tcp."

        @Volatile private var instance: ShareManager? = null
        fun get(context: Context): ShareManager =
            instance ?: synchronized(this) { instance ?: ShareManager(context).also { instance = it } }
    }
}
