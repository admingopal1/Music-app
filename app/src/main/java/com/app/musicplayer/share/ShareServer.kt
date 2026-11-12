package com.app.musicplayer.share

import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * Tiny local web server:
 *  - GET  /             web page for any browser: music web player, downloads, drag & drop upload
 *  - GET  /dl/{id}      download an offered file (supports resume via Range)
 *  - GET  /zip          all offered files as one ZIP
 *  - GET  /api/library  the music library as JSON;  GET /stream/{songId}  stream a song (Range)
 *  - PUT  /upload       plain one-shot upload (web page, older app versions)
 *  - POST /hello        ECDH key exchange for encrypted app-to-app transfers (waits for the user to accept)
 *  - GET  /status       bytes already received for a transfer key (resume / duplicate check)
 *  - PUT  /upload2      encrypted, resumable upload body;  POST /finish  SHA-256 check and save
 *  - GET  /info         device name (used by the app to verify a PIN)
 * Every request needs the session PIN; a new device must also be accepted on this phone (if "Ask before receiving" is on).
 */
class ShareServer(port: Int, private val mgr: ShareManager) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        text(Status.INTERNAL_ERROR, "Error: ${e.message}")
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri
        val pin = session.parms["pin"].orEmpty()
        val ip = session.headers["http-client-ip"].orEmpty()

        if (pin.isEmpty()) return html(pinPage(null))
        when (mgr.checkPin(pin, ip)) {
            1 -> return if (uri == "/") html(pinPage("Wrong PIN")) else text(Status.FORBIDDEN, "Wrong PIN")
            2 -> return text(Status.lookup(429) ?: Status.FORBIDDEN, "Too many attempts. Try again in a minute.")
        }

        if (uri != "/info") {
            val from = session.parms["from"]?.takeIf { it.isNotBlank() } ?: "Browser ($ip)"
            when (mgr.approval(ip, from, block = uri == "/hello")) {
                false -> return if (uri == "/") html(notePage("${mgr.deviceName()} declined the connection.", null))
                else text(Status.FORBIDDEN, "${mgr.deviceName()} declined the connection")
                null -> return if (uri == "/") html(notePage("Waiting for ${mgr.deviceName()} to accept…", pin))
                else text(Status.FORBIDDEN, "Not accepted on ${mgr.deviceName()} in time")
                true -> {}
            }
        }

        val post = session.method == Method.POST
        val put = session.method == Method.PUT
        return when {
            uri == "/" -> html(mainPage(pin))
            uri == "/info" -> json("{\"name\":\"" + jsonEsc(mgr.deviceName()) + "\"}")
            uri.startsWith("/dl/") -> download(session, uri.removePrefix("/dl/").toLongOrNull(), ip)
            uri == "/zip" -> zip()
            uri == "/api/library" -> library()
            uri.startsWith("/stream/") -> stream(session, uri.removePrefix("/stream/").toLongOrNull())
            uri == "/upload" && (put || post) -> upload(session, ip)
            uri == "/hello" && post -> hello(session)
            uri == "/status" -> status(session)
            uri == "/upload2" && put -> upload2(session, ip)
            uri == "/finish" && post -> finish(session, ip)
            else -> text(Status.NOT_FOUND, "Not found")
        }
    }

    // ------------------------------------------------------------------ secure, resumable app-to-app

    private class Transfer(val key: String, val name: String, val size: Long, val sid: String?, val from: String)

    private fun transfer(session: IHTTPSession, ip: String): Transfer? {
        val key = session.parms["key"]?.filter { it.isLetterOrDigit() }?.take(64).orEmpty()
        val size = session.parms["size"]?.toLongOrNull() ?: return null
        if (key.isEmpty() || size <= 0) return null
        val sid = session.parms["sid"]?.takeIf { it.isNotBlank() }
        val from = session.parms["from"]?.takeIf { it.isNotBlank() } ?: ip.ifBlank { "device" }
        return Transfer(key, sanitize(session.parms["name"] ?: "file"), size, sid, from)
    }

    private fun hello(session: IHTTPSession): Response {
        if (!mgr.acceptingUploads()) return text(Status.FORBIDDEN, "This device is not receiving right now.")
        val pub = session.parms["pub"]?.filter { it.isLetterOrDigit() } ?: return text(Status.BAD_REQUEST, "Missing key")
        return try {
            json(mgr.hello(pub))
        } catch (e: Exception) {
            text(Status.BAD_REQUEST, "Bad key")
        }
    }

    private fun status(session: IHTTPSession): Response {
        val t = transfer(session, "") ?: return text(Status.BAD_REQUEST, "Bad request")
        if (t.sid.isNullOrBlank() || !mgr.hasSession(t.sid)) return text(Status.UNAUTHORIZED, "Unknown or missing secure session")
        val print = session.parms["qp"]?.filter { it.isLetterOrDigit() }
        return json(JSONObject().put("have", mgr.status(t.key, t.name, t.size, print, t.sid)).toString())
    }

    private fun upload2(session: IHTTPSession, ip: String): Response {
        if (!mgr.acceptingUploads()) return text(Status.FORBIDDEN, "This device is not receiving right now.")
        val t = transfer(session, ip) ?: return text(Status.BAD_REQUEST, "Bad request")
        val offset = session.parms["offset"]?.toLongOrNull() ?: 0L
        val have = mgr.receivePart(t.key, t.name, t.size, offset, t.sid, session.inputStream, t.from)
        val r = when {
            have < 0 -> text(Status.UNAUTHORIZED, "Unknown session")
            have == t.size -> json(JSONObject().put("have", have).toString())
            else -> text(Status.CONFLICT, "Incomplete, have $have")
        }
        r.closeConnection(true)
        return r
    }

    private fun finish(session: IHTTPSession, ip: String): Response {
        val t = transfer(session, ip) ?: return text(Status.BAD_REQUEST, "Bad request")
        if (t.sid.isNullOrBlank() || !mgr.hasSession(t.sid)) {
            return text(Status.UNAUTHORIZED, "Unknown or missing secure session")
        }
        val sha = session.parms["sha"].orEmpty()
        return when (mgr.finish(t.key, t.name, t.size, sha, t.from)) {
            0 -> json("{\"ok\":true}")
            1 -> text(Status.CONFLICT, "Checksum mismatch")
            else -> text(Status.INTERNAL_ERROR, "Incomplete")
        }
    }

    // ------------------------------------------------------------------ web player + zip

    private fun library(): Response {
        val arr = JSONArray()
        mgr.library.forEach {
            arr.put(JSONObject().put("id", it.id).put("t", it.title).put("a", it.artist).put("al", it.album).put("d", it.durationMs))
        }
        return json(arr.toString())
    }

    private fun stream(session: IHTTPSession, id: Long?): Response {
        val song = id?.let { mgr.song(it) } ?: return text(Status.NOT_FOUND, "Song not found")
        val mime = mgr.guessMime(song.fileName.ifBlank { "a.mp3" }).takeIf { it.startsWith("audio/") } ?: "audio/mpeg"
        return ranged(session, song.uri, mgr.sizeOf(song.uri), mime, song.fileName.ifBlank { song.title }, inline = true) { this }
    }

    private fun zip(): Response {
        val files = mgr.outFiles()
        if (files.isEmpty()) return text(Status.NOT_FOUND, "No files are being offered")
        val input = PipedInputStream(256 * 1024)
        val output = PipedOutputStream(input)
        thread(name = "share-zip") {
            runCatching {
                ZipOutputStream(output).use { z ->
                    z.setLevel(Deflater.NO_COMPRESSION)
                    val used = HashSet<String>()
                    for (f in files) {
                        var n = sanitize(f.name)
                        var i = 1
                        while (!used.add(n)) n = "${f.name.substringBeforeLast('.')} (${i++}).${f.name.substringAfterLast('.', "")}"
                        val ins = mgr.openOut(f) ?: continue
                        z.putNextEntry(ZipEntry(n))
                        ins.use { it.copyTo(z, 256 * 1024) }
                        z.closeEntry()
                    }
                }
            }
            runCatching { output.close() }
        }
        val res = newChunkedResponse(Status.OK, "application/zip", input)
        res.addHeader("Content-Disposition", "attachment; filename=\"${sanitize(mgr.deviceName())}-share.zip\"")
        return res
    }

    // ------------------------------------------------------------------ upload

    private fun upload(session: IHTTPSession, ip: String): Response {
        if (!mgr.acceptingUploads()) return text(Status.FORBIDDEN, "This device is not receiving right now.")
        val raw = session.parms["name"]
            ?: session.headers["x-file-name"]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?: "file"
        val name = sanitize(raw)
        val length = session.headers["content-length"]?.toLongOrNull() ?: -1L
        val from = session.parms["from"]?.takeIf { it.isNotBlank() } ?: ip.ifBlank { "device" }
        val ok = mgr.receiveStream(name, length, session.inputStream, from)
        val r = if (ok) json("{\"ok\":true}") else text(Status.INTERNAL_ERROR, "Receive failed")
        r.closeConnection(true)
        return r
    }

    // ------------------------------------------------------------------ download

    private fun download(session: IHTTPSession, id: Long?, ip: String): Response {
        val f = id?.let { mgr.outFile(it) } ?: return text(Status.NOT_FOUND, "File not found")
        return ranged(session, f.uri, f.size, f.mime, f.name, inline = false) { start -> mgr.countingStream(this, f, ip, start) }
    }

    /** Serves [uri] with HTTP Range support (resume / seeking). */
    private fun ranged(
        session: IHTTPSession, uri: Uri, total: Long, mime: String, name: String, inline: Boolean,
        wrap: InputStream.(Long) -> InputStream,
    ): Response {
        var start = 0L
        var end = total - 1
        var partial = false
        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=") && total > 0) {
            val parts = range.removePrefix("bytes=").split("-")
            start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            parts.getOrNull(1)?.toLongOrNull()?.let { end = it.coerceAtMost(total - 1) }
            if (start >= total || start > end) return text(Status.RANGE_NOT_SATISFIABLE, "Bad range")
            partial = true
        }
        val raw = mgr.openUri(uri) ?: return text(Status.INTERNAL_ERROR, "Can't open file")
        if (start > 0) {
            var left = start
            while (left > 0) {
                val s = raw.skip(left)
                if (s <= 0) break
                left -= s
            }
        }
        val stream = raw.wrap(start)
        val len = if (total > 0) end - start + 1 else -1L
        val res = if (len >= 0) {
            newFixedLengthResponse(if (partial) Status.PARTIAL_CONTENT else Status.OK, mime, stream, len)
        } else {
            newChunkedResponse(Status.OK, mime, stream)
        }
        res.addHeader("Accept-Ranges", "bytes")
        if (partial) res.addHeader("Content-Range", "bytes $start-$end/$total")
        val ascii = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val utf8 = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        res.addHeader("Content-Disposition", "${if (inline) "inline" else "attachment"}; filename=\"$ascii\"; filename*=UTF-8''$utf8")
        return res
    }

    // ------------------------------------------------------------------ pages

    private fun text(status: Response.IStatus, msg: String): Response = newFixedLengthResponse(status, MIME_PLAINTEXT, msg)
    private fun html(body: String): Response = newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", body)
    private fun json(body: String): Response = newFixedLengthResponse(Status.OK, "application/json", body)

    private fun sanitize(n: String): String =
        n.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_")
            .trim().take(150).ifBlank { "file" }

    private fun jsonEsc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val css = """
        body{margin:0;font-family:system-ui,sans-serif;background:#0b0b0f;color:#eceaf4}
        .w{max-width:560px;margin:0 auto;padding:20px}
        h1{font-size:22px;margin:6px 0 2px} .sub{color:#9a96ab;font-size:14px;margin-bottom:18px}
        .card{background:#17171f;border:1px solid #26262f;border-radius:18px;padding:16px;margin-bottom:14px}
        .row{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:10px 0;border-bottom:1px solid #22222b}
        .row:last-child{border-bottom:0} .n{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1}
        a.b,button,input[type=submit]{background:#ff8800;color:#111;border:0;border-radius:12px;padding:10px 16px;font-weight:700;text-decoration:none;font-size:15px}
        input[type=text],input[type=file]{width:100%;box-sizing:border-box;background:#0f0f15;border:1px solid #2c2c36;border-radius:12px;color:#eee;padding:12px;font-size:16px;margin-bottom:10px}
        progress{width:100%} small{color:#9a96ab}
    """.trimIndent()

    private fun pinPage(error: String?): String = """
        <!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><meta charset=utf-8>
        <title>Music Player Share</title><style>$css</style></head><body><div class=w>
        <h1>Music Player Share</h1><div class=sub>Enter the PIN shown on the other phone.</div>
        <form class=card method=get action="/">
        ${if (error != null) "<div style='color:#ff6b6b;margin-bottom:8px'>${esc(error)}</div>" else ""}
        <input type=text name=pin inputmode=numeric autocomplete=off placeholder="6-digit PIN" autofocus>
        <input type=submit value="Connect"></form></div></body></html>
    """.trimIndent()

    private fun notePage(message: String, pin: String?): String = """
        <!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><meta charset=utf-8>
        ${if (pin != null) "<meta http-equiv=refresh content=\"2;url=/?pin=${pin.filter { it.isDigit() }}\">" else ""}
        <title>Music Player Share</title><style>$css</style></head><body><div class=w>
        <h1>Music Player Share</h1><div class=card>${esc(message)}</div></div></body></html>
    """.trimIndent()

    private fun mainPage(pin: String): String {
        val safePin = pin.filter { it.isDigit() }
        val files = mgr.outFiles()
        val list = if (files.isEmpty()) "<div class=sub>No files are being offered right now.</div>" else
            files.joinToString("") { f ->
                "<div class=row><span class=n>${esc(f.name)}<br><small>${mgr.fmtSize(f.size)}</small></span>" +
                    "<a class=b href=\"/dl/${f.id}?pin=$safePin\" download>Download</a></div>"
            } + (if (files.size > 1) "<p><a class=b href=\"/zip?pin=$safePin\">Download all (ZIP)</a></p>" else "")
        val player = if (mgr.library.isEmpty()) "" else """
            <div class=card><b>Web player</b> <small id=cnt></small>
            <div class=pl><div class=np id=np>Pick a song</div>
            <audio id=au controls preload=none></audio>
            <div class=ctl><button onclick="go(-1)">&#9664;&#9664;</button><button onclick="shuf()">Shuffle</button><button onclick="go(1)">&#9654;&#9654;</button></div></div>
            <input type=text id=q placeholder="Search songs, artists, albums" oninput="render()">
            <div id=songs class=list></div></div>"""
        val uploadBox = if (!mgr.acceptingUploads()) "" else """
            <div class=card><b>Send files to ${esc(mgr.deviceName())}</b>
            <div id=drop class=drop onclick="document.getElementById('f').click()">Drop files here or tap to choose</div>
            <input type=file id=f multiple style="display:none" onchange="up(this.files)"><div id=prog></div></div>"""
        return """
            <!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><meta charset=utf-8>
            <title>${esc(mgr.deviceName())} · Music Player Share</title><style>$css
            .pl{background:#0f0f15;border-radius:14px;padding:12px;margin:10px 0}.np{font-weight:700;margin-bottom:8px}
            audio{width:100%}.ctl{display:flex;gap:8px;justify-content:center;margin-top:8px}.ctl button{padding:8px 14px}
            .list{max-height:55vh;overflow:auto}.song{cursor:pointer}.song.on .n{color:#ff8800}
            .drop{border:2px dashed #3a3a46;border-radius:16px;padding:28px;text-align:center;color:#9a96ab;margin-top:10px;cursor:pointer}
            .drop.over{border-color:#ff8800;color:#ff8800}
            </style></head><body><div class=w>
            <h1>${esc(mgr.deviceName())}</h1><div class=sub>Music Player Share</div>
            $player
            <div class=card><b>Files to download</b>$list</div>
            $uploadBox
            </div><script>
            var PIN='$safePin',S=[],V=[],cur=-1;
            function q(u){return u+(u.indexOf('?')<0?'?':'&')+'pin='+PIN}
            function fmt(ms){var s=Math.round(ms/1000);return Math.floor(s/60)+':'+('0'+s%60).slice(-2)}
            function esc(t){var d=document.createElement('div');d.textContent=t;return d.innerHTML}
            function render(){var box=document.getElementById('songs');if(!box)return;var k=document.getElementById('q').value.toLowerCase();
              V=S.filter(function(s){return !k||(s.t+' '+s.a+' '+s.al).toLowerCase().indexOf(k)>=0});
              box.innerHTML=V.slice(0,500).map(function(s,i){return '<div class="row song'+(S[cur]&&S[cur].id==s.id?' on':'')+'" onclick="play('+i+')"><span class=n>'+esc(s.t)+'<br><small>'+esc(s.a)+' · '+fmt(s.d)+'</small></span><a class=b href="'+q('/stream/'+s.id)+'" download onclick="event.stopPropagation()">&#8595;</a></div>'}).join('');
              document.getElementById('cnt').textContent='('+S.length+' songs)'}
            function play(i){var s=V[i];if(!s)return;cur=S.indexOf(s);var a=document.getElementById('au');a.src=q('/stream/'+s.id);a.play();
              document.getElementById('np').textContent=s.t+' — '+s.a;document.title=s.t;render()}
            function go(d){if(!V.length)return;var i=V.indexOf(S[cur]);play((i+d+V.length)%V.length)}
            function shuf(){if(V.length)play(Math.floor(Math.random()*V.length))}
            var au=document.getElementById('au');if(au){au.onended=function(){go(1)};
              fetch(q('/api/library')).then(function(r){return r.json()}).then(function(j){S=j;render()})}
            function up(fs){var box=document.getElementById('prog');
              for(var i=0;i<fs.length;i++){(function(f){var d=document.createElement('div');d.className='row';
              var s=document.createElement('span');s.className='n';s.textContent=f.name;var p=document.createElement('progress');p.max=100;p.value=0;
              d.appendChild(s);d.appendChild(p);box.appendChild(d);var x=new XMLHttpRequest();
              x.open('PUT',q('/upload?name='+encodeURIComponent(f.name)+'&from=Browser'));
              x.upload.onprogress=function(e){if(e.lengthComputable)p.value=e.loaded*100/e.total};
              x.onload=function(){s.textContent=(x.status==200?'Done: ':'Failed: ')+f.name};
              x.onerror=function(){s.textContent='Failed: '+f.name};x.send(f)})(fs[i])}}
            var dr=document.getElementById('drop');if(dr){
              ['dragenter','dragover'].forEach(function(e){dr.addEventListener(e,function(v){v.preventDefault();dr.classList.add('over')})});
              ['dragleave','drop'].forEach(function(e){dr.addEventListener(e,function(v){v.preventDefault();dr.classList.remove('over')})});
              dr.addEventListener('drop',function(v){up(v.dataTransfer.files)})}
            </script></body></html>
        """.trimIndent()
    }
}
