package com.app.musicplayer.transfer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.app.musicplayer.service.FileTransferService
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TransferPeer(val name: String, val address: String, val status: Int)

data class TransferState(
    val serverRunning: Boolean = false,
    val discovering: Boolean = false,
    val connected: Boolean = false,
    val address: String? = null,
    val pairingUrl: String? = null,
    val pairingCode: String? = null,
    val wifiDirectName: String? = null,
    val wifiDirectPassword: String? = null,
    val peers: List<TransferPeer> = emptyList(),
    val message: String = "Start a receiver or find a nearby receiver.",
    val error: String? = null,
    val progress: Float? = null,
    val isTransferring: Boolean = false,
    val receivedFiles: List<File> = emptyList(),
)

/**
 * Wi-Fi Direct group owner and token-protected local HTTP transfer server.
 * The application must keep this manager alive while its transfer screen is in use.
 */
class FileTransferManager(context: Context) {
    private val appContext = context.applicationContext
    private val wifiP2p = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = wifiP2p?.initialize(appContext, Looper.getMainLooper()) {
        updateError("Wi-Fi Direct disconnected. Reopen sharing to reconnect.")
    }
    private val stateMutable = MutableStateFlow(TransferState(receivedFiles = receivedFiles()))
    val state: StateFlow<TransferState> = stateMutable.asStateFlow()
    private val transferExecutor = Executors.newSingleThreadExecutor()
    private val random = SecureRandom()
    private val lock = Any()
    private val receivedDirectory by lazy {
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "Received")
    }

    private var server: TransferHttpServer? = null
    private var token: String? = null
    private var pairingCode: String? = null
    private var registered = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val released = AtomicBoolean(false)
    private val activeConnection = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>()
    @Volatile private var transferSession = 0L

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled) updateError("Turn on Wi-Fi to use Wi-Fi Direct sharing.")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
            }
        }
    }

    fun startReceiver() {
        val manager = wifiP2p
        val p2pChannel = channel
        if (manager == null || p2pChannel == null) {
            updateError("This device does not support Wi-Fi Direct.")
            return
        }
        if (!hasP2pPermissions()) {
            updateError("Grant Nearby devices permission before starting Wi-Fi Direct.")
            return
        }
        if (!startTransferForegroundService()) return
        try {
            registerP2pReceiver()
            startServer()
            if (server?.isAlive != true) {
                appContext.startService(Intent(appContext, FileTransferService::class.java).setAction(FileTransferService.ACTION_STOP))
                return
            }
            acquireWifiLocks()
            updateState {
                it.copy(message = "Starting a secure Wi-Fi Direct receiver…", error = null, discovering = false)
            }
            manager.createGroup(p2pChannel, actionListener("Wi-Fi Direct receiver started.") { requestConnectionInfo() })
        } catch (e: SecurityException) {
            updateError("Wi-Fi Direct permission was denied: ${e.message}")
        }
    }

    fun discoverReceivers() {
        val manager = wifiP2p
        val p2pChannel = channel
        if (manager == null || p2pChannel == null) {
            updateError("This device does not support Wi-Fi Direct.")
            return
        }
        if (!hasP2pPermissions()) {
            updateError("Grant Nearby devices permission before searching.")
            return
        }
        try {
            registerP2pReceiver()
            manager.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    updateState { it.copy(discovering = true, peers = emptyList(), error = null, message = "Searching for nearby receivers…") }
                }
                override fun onFailure(reason: Int) {
                    updateError("Could not search for receivers (Wi-Fi Direct error $reason).")
                }
            })
        } catch (e: SecurityException) {
            updateError("Wi-Fi Direct permission was denied: ${e.message}")
        }
    }

    fun connectTo(peer: TransferPeer) {
        val manager = wifiP2p
        val p2pChannel = channel
        if (manager == null || p2pChannel == null) {
            updateError("This device does not support Wi-Fi Direct.")
            return
        }
        if (!hasP2pPermissions()) {
            updateError("Grant Nearby devices permission before connecting.")
            return
        }
        try {
            @Suppress("DEPRECATION")
            val config = WifiP2pConfig().apply {
                deviceAddress = peer.address
                groupOwnerIntent = 0
            }
            manager.connect(p2pChannel, config, actionListener("Connecting to ${peer.name}…") {
                updateState { it.copy(message = "Connected. Select files to send.", error = null) }
                requestConnectionInfo()
            })
        } catch (e: SecurityException) {
            updateError("Wi-Fi Direct permission was denied: ${e.message}")
        }
    }

    fun sendFiles(uris: List<Uri>, receiverCode: String) {
        if (uris.isEmpty()) return
        val destination = stateMutable.value.address
        val code = receiverCode.filter(Char::isDigit)
        if (destination.isNullOrBlank() || code.length != PAIRING_CODE_LENGTH) {
            updateError("Connect to a receiver and enter its 8-digit pairing code.")
            return
        }
        if (!startTransferForegroundService()) return
        val sessionId = synchronized(lock) {
            if (stateMutable.value.isTransferring) {
                updateError("A file transfer is already in progress.")
                return
            }
            ++transferSession
        }
        updateState { it.copy(isTransferring = true, progress = 0f, message = "Preparing transfer…", error = null) }
        transferExecutor.execute {
            uris.forEachIndexed { index, uri ->
                if (released.get() || sessionId != transferSession) return@execute
                try {
                    uploadFile(uri, destination, code, index, uris.size, sessionId)
                } catch (e: Exception) {
                    if (sessionId != transferSession) return@execute
                    Log.e(TAG, "File transfer failed for $uri", e)
                    updateState {
                        it.copy(
                            isTransferring = false,
                            progress = null,
                            error = "Could not send ${displayName(uri)}: ${e.localizedMessage ?: "transfer failed"}",
                            message = "File transfer failed.",
                        )
                    }
                    return@execute
                }
            }
            if (sessionId == transferSession) updateState {
                it.copy(isTransferring = false, progress = null, message = "File transfer complete.", error = null)
            }
        }
    }

    fun stopSharing() {
        synchronized(lock) { transferSession++ }
        activeConnection.getAndSet(null)?.disconnect()
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop local transfer server", e)
        }
        server = null
        token = null
        pairingCode = null
        try {
            appContext.startService(Intent(appContext, FileTransferService::class.java).setAction(FileTransferService.ACTION_STOP))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not stop file transfer foreground service", e)
        }
        releaseWifiLocks()
        if (registered) {
            try {
                appContext.unregisterReceiver(p2pReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Wi-Fi Direct receiver was already unregistered", e)
            }
            registered = false
        }
        val manager = wifiP2p
        val p2pChannel = channel
        if (manager != null && p2pChannel != null && hasP2pPermissions()) {
            try {
                manager.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "Could not remove Wi-Fi Direct group: $reason")
                    }
                })
            } catch (e: SecurityException) {
                Log.w(TAG, "Wi-Fi Direct permission unavailable while stopping", e)
            }
        }
        updateState {
            TransferState(receivedFiles = receivedFiles(), message = "Sharing stopped.")
        }
    }

    fun close() {
        if (!released.compareAndSet(false, true)) return
        stopSharing()
        transferExecutor.shutdownNow()
    }

    private fun startServer() {
        if (server?.isAlive == true) return
        val secret = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val code = random.nextInt(100_000_000).toString().padStart(PAIRING_CODE_LENGTH, '0')
        token = secret
        pairingCode = code
        try {
            val directory = receiveDirectory()
            val nextServer = TransferHttpServer(PORT, secret, code, directory) { file ->
                updateState {
                    it.copy(
                        receivedFiles = receivedFiles(),
                        message = "Received ${file.name}",
                        error = null,
                    )
                }
            }
            nextServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = nextServer
            updateState { it.copy(serverRunning = true, pairingCode = code, error = null) }
        } catch (e: Exception) {
            token = null
            pairingCode = null
            Log.e(TAG, "Could not start file transfer server", e)
            updateError("Could not start the file receiver: ${e.localizedMessage}")
        }
    }

    private fun startTransferForegroundService(): Boolean =
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, FileTransferService::class.java).setAction(FileTransferService.ACTION_START),
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start file transfer foreground service", e)
            updateError("Could not keep sharing active in the background: ${e.localizedMessage}")
            false
        }

    private fun uploadFile(uri: Uri, host: String, code: String, index: Int, count: Int, sessionId: Long) {
        val name = displayName(uri)
        val encodedName = URLEncoder.encode(name, Charsets.UTF_8.name())
        val url = URL("http://$host:$PORT/upload?name=$encodedName")
        var stagedFile: File? = null
        var connection: HttpURLConnection? = null
        try {
            val size = appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (size > MAX_FILE_BYTES) throw IOException("File is larger than the 8 GiB transfer limit")
            stagedFile = if (size < 0L) stageFile(uri, name) else null
            val uploadSize = stagedFile?.length() ?: size
            val request = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = RESPONSE_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("X-Transfer-Code", code)
                setFixedLengthStreamingMode(uploadSize)
            }
            connection = request
            activeConnection.set(request)
            if (sessionId != transferSession) throw IOException("Transfer cancelled")
            val source = stagedFile?.inputStream()?.buffered()
                ?: appContext.contentResolver.openInputStream(uri)?.buffered()
                ?: throw IOException("Could not open the selected file")
            source.use { input ->
                request.outputStream.use { output ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    var sent = 0L
                    var lastProgressAt = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Transfer cancelled")
                        output.write(buffer, 0, read)
                        sent += read
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_MS || sent == uploadSize) {
                            if (sessionId == transferSession) updateState {
                                it.copy(
                                    progress = (sent.toFloat() / uploadSize.coerceAtLeast(1L)).coerceIn(0f, 1f),
                                    message = "Sending $name (${index + 1}/$count)…",
                                    error = null,
                                )
                            }
                            lastProgressAt = now
                        }
                    }
                }
            }
            val response = request.responseCode
            if (response !in 200..299) {
                val detail = request.errorStream?.bufferedReader()?.use { it.readText().take(200) }
                throw IOException("Receiver returned HTTP $response${detail?.takeIf(String::isNotBlank)?.let { ": $it" } ?: ""}")
            }
        } finally {
            connection?.let {
                activeConnection.compareAndSet(it, null)
                it.disconnect()
            }
            stagedFile?.takeIf { it.exists() && !it.delete() }?.let {
                Log.w(TAG, "Could not remove staged upload ${it.name}")
            }
        }
    }

    private fun stageFile(uri: Uri, name: String): File {
        val staged = File.createTempFile("outgoing-", ".upload", appContext.cacheDir)
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().buffered().use { output ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    var total = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        total += read
                        if (total > MAX_FILE_BYTES) throw IOException("$name is larger than the 8 GiB transfer limit")
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IOException("Could not open the selected file")
            return staged
        } catch (e: Exception) {
            if (staged.exists() && !staged.delete()) Log.w(TAG, "Could not remove incomplete staged upload")
            throw e
        }
    }

    private fun requestPeers() {
        val manager = wifiP2p ?: return
        val p2pChannel = channel ?: return
        if (!hasP2pPermissions()) return
        try {
            manager.requestPeers(p2pChannel) { list: WifiP2pDeviceList ->
                val peers = list.deviceList.map { TransferPeer(it.deviceName.ifBlank { "Nearby device" }, it.deviceAddress, it.status) }
                updateState { it.copy(peers = peers, discovering = false) }
            }
        } catch (e: SecurityException) {
            updateError("Wi-Fi Direct permission was denied: ${e.message}")
        }
    }

    private fun requestConnectionInfo() {
        val manager = wifiP2p ?: return
        val p2pChannel = channel ?: return
        if (!hasP2pPermissions()) return
        try {
            manager.requestConnectionInfo(p2pChannel) connectionInfoCallback@{ info: WifiP2pInfo ->
                if (!info.groupFormed) return@connectionInfoCallback
                val peerHost = info.groupOwnerAddress?.hostAddress
                if (peerHost.isNullOrBlank()) {
                    updateError("Wi-Fi Direct connected, but the receiver address is unavailable.")
                    return@connectionInfoCallback
                }
                if (info.isGroupOwner) {
                    publishAddress(findLocalAddress().ifBlank { peerHost })
                    requestGroupCredentials(manager, p2pChannel)
                    updateState { it.copy(connected = true, message = "Receiver ready. Scan the Wi-Fi QR, then the upload QR.") }
                } else {
                    updateState {
                        it.copy(
                            connected = true,
                            address = peerHost,
                            message = "Connected to receiver. Select files to send.",
                            error = null,
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            updateError("Wi-Fi Direct permission was denied: ${e.message}")
        }
    }

    private fun requestGroupCredentials(manager: WifiP2pManager, p2pChannel: WifiP2pManager.Channel) {
        try {
            manager.requestGroupInfo(p2pChannel) { group: WifiP2pGroup? ->
                val name = group?.networkName?.takeIf(String::isNotBlank)
                val password = group?.passphrase?.takeIf(String::isNotBlank)
                updateState { it.copy(wifiDirectName = name, wifiDirectPassword = password) }
            }
        } catch (e: SecurityException) {
            updateError("Wi-Fi Direct permission was denied while reading network details: ${e.message}")
        }
    }

    private fun publishAddress(host: String) {
        val secret = token ?: return
        val url = "http://$host:$PORT/?token=$secret"
        updateState {
            it.copy(serverRunning = true, connected = true, address = host, pairingUrl = url, error = null)
        }
    }

    private fun registerP2pReceiver() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(appContext, p2pReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    private fun hasP2pPermissions(): Boolean {
        val nearbyGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        val locationGranted = Build.VERSION.SDK_INT >= 33 ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return nearbyGranted && locationGranted
    }

    private fun actionListener(successMessage: String, onSuccess: () -> Unit = {}) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                updateState { it.copy(error = null, message = successMessage) }
                onSuccess()
            }
            override fun onFailure(reason: Int) {
                updateError("$successMessage Failed (Wi-Fi Direct error $reason).")
            }
        }

    private fun acquireWifiLocks() {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        if (wifiLock?.isHeld != true) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MusicPlayer:Transfer").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (multicastLock?.isHeld != true) {
            multicastLock = wifi.createMulticastLock("MusicPlayer:TransferDiscovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWifiLocks() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not release Wi-Fi transfer lock", e)
        }
        wifiLock = null
        multicastLock = null
    }

    private fun receiveDirectory(): File = receivedDirectory.apply {
        if (!exists() && !mkdirs()) throw IOException("Could not create the received-files directory")
    }

    private fun receivedFiles(): List<File> =
        receivedDirectory.listFiles()?.filter(File::isFile)?.sortedByDescending(File::lastModified).orEmpty()

    private fun findLocalAddress(): String =
        try {
            val addresses = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
            (addresses.firstOrNull { it.hostAddress?.startsWith("192.168.49.") == true } ?: addresses.firstOrNull())
                ?.hostAddress
                ?.substringBefore('%')
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not find a local Wi-Fi address", e)
            ""
        }

    private fun displayName(uri: Uri): String {
        val queried = try {
            appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the selected file name", e)
            null
        }
        return safeFileName(queried ?: uri.lastPathSegment ?: "received-file")
    }

    private fun updateState(transform: (TransferState) -> TransferState) {
        stateMutable.update(transform)
    }

    private fun updateError(message: String) {
        updateState { it.copy(error = message, message = message) }
    }

    private inner class TransferHttpServer(
        port: Int,
        private val secret: String,
        private val pairingCode: String,
        private val directory: File,
        private val onReceived: (File) -> Unit,
    ) : NanoHTTPD(port) {
        private val failedPairings = ConcurrentHashMap<String, MutableList<Long>>()

        override fun serve(session: IHTTPSession): Response {
            val supplied = session.headers["x-transfer-token"]
                ?: session.parameters["token"]?.firstOrNull()
            val code = session.headers["x-transfer-code"]
            val validToken = supplied?.let { secureEquals(it, secret) } == true
            val validCode = session.remoteIpAddress.startsWith("192.168.49.") &&
                code?.let { secureEquals(it, pairingCode) } == true
            if (!validToken && !validCode) {
                if (tooManyPairingAttempts(session.remoteIpAddress)) {
                    return newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, MIME_PLAINTEXT, "Too many pairing attempts")
                }
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Invalid or missing transfer credentials")
            }
            return when {
                session.method == Method.GET && session.uri == "/" ->
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", uploadPage(secret))
                session.method == Method.GET && session.uri == "/status" ->
                    newFixedLengthResponse(Response.Status.OK, "application/json", """{"ready":true}""")
                session.method == Method.POST && session.uri == "/upload" -> receiveUpload(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        }

        private fun receiveUpload(session: IHTTPSession): Response {
            val rawName = session.parameters["name"]?.firstOrNull() ?: return badRequest("File name is required")
            val fileName = safeFileName(rawName)
            val contentLength = session.headers["content-length"]?.toLongOrNull() ?: -1L
            if (contentLength > MAX_FILE_BYTES) return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "File exceeds the 8 GiB limit")
            val parsed = HashMap<String, String>()
            try {
                session.parseBody(parsed)
                val bodyPath = parsed["postData"] ?: parsed.values.firstOrNull()
                    ?: return badRequest("Request body is empty")
                val source = File(bodyPath)
                if (!source.isFile || source.length() > MAX_FILE_BYTES) return badRequest("Invalid or oversized request body")
                val target = synchronized(lock) {
                    val destination = uniqueFile(directory, fileName)
                    val temporary = File.createTempFile(".transfer-", ".part", directory)
                    try {
                        source.inputStream().buffered().use { input ->
                            temporary.outputStream().buffered().use { output -> input.copyTo(output, CHUNK_SIZE) }
                        }
                        if (!temporary.renameTo(destination)) throw IOException("Could not finalize the received file")
                        destination
                    } finally {
                        if (temporary.exists() && !temporary.delete()) {
                            Log.w(TAG, "Could not remove partial transfer file ${temporary.name}")
                        }
                    }
                }
                onReceived(target)
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Received ${target.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Could not receive uploaded file", e)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Could not save uploaded file")
            } finally {
                val tempRoot = File(System.getProperty("java.io.tmpdir") ?: appContext.cacheDir.absolutePath).canonicalFile
                parsed.values.forEach { path ->
                    try {
                        val temporary = File(path).canonicalFile
                        if (temporary.isFile && temporary.path.startsWith(tempRoot.path + File.separator)) temporary.delete()
                    } catch (e: IOException) {
                        Log.w(TAG, "Could not remove temporary upload", e)
                    }
                }
            }
        }

        private fun badRequest(message: String) =
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, message)

        private fun uploadPage(token: String): String = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Music Player file sharing</title>
            <style>body{font:16px system-ui;max-width:38rem;margin:10vh auto;padding:1rem;background:#111;color:#eee}
            button,input{font:inherit;padding:.8rem;border-radius:.7rem}button{background:#ff8800;border:0}
            #status{white-space:pre-wrap;margin-top:1rem}</style></head><body>
            <h1>Send files to this device</h1><p>Choose files to transfer over the local Wi-Fi connection.</p>
            <input id="files" type="file" multiple><button id="send">Send files</button><div id="status"></div>
            <script>const token=${jsString(token)};document.getElementById('send').onclick=async()=>{const files=document.getElementById('files').files;
            const status=document.getElementById('status');if(!files.length){status.textContent='Choose at least one file.';return}
            for(const f of files){status.textContent='Sending '+f.name+'…';try{const r=await fetch('/upload?token='+encodeURIComponent(token)+'&name='+encodeURIComponent(f.name),
            {method:'POST',headers:{'Content-Type':'application/octet-stream'},body:f});if(!r.ok)throw new Error(await r.text());}
            catch(e){status.textContent='Transfer failed: '+e;return}}status.textContent='Transfer complete.';};</script></body></html>
        """.trimIndent()

        private fun tooManyPairingAttempts(address: String): Boolean {
            val now = System.currentTimeMillis()
            val attempts = failedPairings.compute(address) { _, recent ->
                (recent ?: mutableListOf()).apply {
                    removeAll { now - it > PAIRING_WINDOW_MS }
                    add(now)
                }
            } ?: return false
            return attempts.size > MAX_PAIRING_ATTEMPTS
        }
    }

    companion object {
        const val PORT = 8765
        private const val TAG = "FileTransfer"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val RESPONSE_TIMEOUT_MS = 60_000
        private const val CHUNK_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 150L
        private const val MAX_FILE_BYTES = 8L * 1024L * 1024L * 1024L
        private const val PAIRING_CODE_LENGTH = 8
        private const val MAX_PAIRING_ATTEMPTS = 10
        private const val PAIRING_WINDOW_MS = 60_000L

        private fun secureEquals(first: String, second: String): Boolean =
            MessageDigest.isEqual(first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))

        private fun safeFileName(candidate: String): String {
            val name = candidate.substringAfterLast('/').substringAfterLast('\\')
                .filter { it.isLetterOrDigit() || it in " ._()-" }
                .trim()
                .trim('.')
            return name.take(180).ifBlank { "received-file" }
        }

        private fun uniqueFile(directory: File, fileName: String): File {
            val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
            val stem = fileName.substring(0, dot)
            val extension = fileName.substring(dot)
            var candidate = File(directory, fileName)
            var suffix = 1
            while (candidate.exists()) {
                candidate = File(directory, "$stem ($suffix)$extension")
                suffix++
            }
            return candidate
        }

        private fun jsString(value: String): String = "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("&", "\\u0026")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\""
    }
}
