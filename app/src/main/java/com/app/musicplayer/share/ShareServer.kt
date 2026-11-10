package com.app.musicplayer.share

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Tiny local web server:
 *  - GET  /            page for any browser (download offered files, upload files to this phone)
 *  - GET  /dl/{id}     download an offered file (supports resume via Range)
 *  - PUT  /upload      upload one file (used by the app and by the web page)
 *  - GET  /info        device name (used by the app to verify a PIN)
 * Every request needs the session PIN.
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

        return when {
            uri == "/" -> html(mainPage(pin))
            uri == "/info" -> json("{\"name\":\"" + jsonEsc(mgr.deviceName()) + "\"}")
            uri.startsWith("/dl/") -> download(session, uri.removePrefix("/dl/").toLongOrNull(), ip)
            uri == "/upload" && (session.method == Method.PUT || session.method == Method.POST) -> upload(session, ip)
            else -> text(Status.NOT_FOUND, "Not found")
        }
    }

    // ------------------------------------------------------------------ upload

    private fun upload(session: IHTTPSession, ip: String): Response {
        if (!mgr.acceptingUploads()) return text(Status.FORBIDDEN, "This device is not receiving right now.")
        val raw = session.parms["name"]
            ?: session.headers["x-file-name"]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?: "file"
        val name = sanitize(raw)
        val length = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (length > ShareManager.MAX_UPLOAD_BYTES) {
            return text(Status.lookup(413) ?: Status.INTERNAL_ERROR, "File exceeds the 2 GiB upload limit.")
        }
        if (length > ShareManager.MAX_UPLOAD_BYTES) {
            return text(Status.lookup(413) ?: Status.INTERNAL_ERROR, "File exceeds the 2 GiB upload limit.")
        }
        val from = session.parms["from"]?.takeIf { it.isNotBlank() } ?: ip.ifBlank { "device" }
        val ok = mgr.receiveStream(name, length, session.inputStream, from)
        val r = if (ok) json("{\"ok\":true}") else text(Status.INTERNAL_ERROR, "Receive failed")
        r.closeConnection(true)
        return r
    }

    // ------------------------------------------------------------------ download

    private fun download(session: IHTTPSession, id: Long?, ip: String): Response {
        val f = id?.let { mgr.outFile(it) } ?: return text(Status.NOT_FOUND, "File not found")
        val total = f.size
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
        val raw = mgr.openOut(f) ?: return text(Status.INTERNAL_ERROR, "Can't open file")
        if (start > 0) {
            var left = start
            while (left > 0) {
                val s = raw.skip(left)
                if (s <= 0) break
                left -= s
            }
        }
        val stream = mgr.countingStream(raw, f, ip, start)
        val len = if (total > 0) end - start + 1 else -1L
        val res = if (len >= 0) {
            newFixedLengthResponse(if (partial) Status.PARTIAL_CONTENT else Status.OK, f.mime, stream, len)
        } else {
            newChunkedResponse(Status.OK, f.mime, stream)
        }
        res.addHeader("Accept-Ranges", "bytes")
        if (partial) res.addHeader("Content-Range", "bytes $start-$end/$total")
        val ascii = f.name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val utf8 = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
        res.addHeader("Content-Disposition", "attachment; filename=\"$ascii\"; filename*=UTF-8''$utf8")
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

    private fun mainPage(pin: String): String {
        val safePin = pin.filter { it.isDigit() }
        val files = mgr.outFiles()
        val list = if (files.isEmpty()) "<div class=sub>No files are being offered right now.</div>" else
            files.joinToString("") { f ->
                "<div class=row><span class=n>${esc(f.name)}<br><small>${mgr.fmtSize(f.size)}</small></span>" +
                    "<a class=b href=\"/dl/${f.id}?pin=$safePin\" download>Download</a></div>"
            }
        val uploadBox = if (!mgr.acceptingUploads()) "" else """
            <div class=card><b>Send files to ${esc(mgr.deviceName())}</b>
            <p class=sub>Pick files here and they are saved on this phone.</p>
            <input type=file id=f multiple><button onclick="up()">Send</button><div id=prog></div></div>
            <script>
            var PIN='$safePin';
            function up(){var fs=document.getElementById('f').files;var box=document.getElementById('prog');
            for(var i=0;i<fs.length;i++){(function(f){var d=document.createElement('div');d.className='row';
            var s=document.createElement('span');s.className='n';s.textContent=f.name;var p=document.createElement('progress');p.max=100;p.value=0;
            d.appendChild(s);d.appendChild(p);box.appendChild(d);var x=new XMLHttpRequest();
            x.open('PUT','/upload?pin='+PIN+'&name='+encodeURIComponent(f.name)+'&from=Browser');
            x.upload.onprogress=function(e){if(e.lengthComputable)p.value=e.loaded*100/e.total};
            x.onload=function(){s.textContent=(x.status==200?'Done: ':'Failed: ')+f.name};
            x.onerror=function(){s.textContent='Failed: '+f.name};x.send(f)})(fs[i])}}
            </script>"""
        return """
            <!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><meta charset=utf-8>
            <title>Music Player Share</title><style>$css</style></head><body><div class=w>
            <h1>${esc(mgr.deviceName())}</h1><div class=sub>Music Player Share</div>
            <div class=card><b>Files to download</b>$list</div>
            $uploadBox
            </div></body></html>
        """.trimIndent()
    }
}
