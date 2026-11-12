package com.app.musicplayer.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max

data class RestoreResult(val playlists: Int, val matched: Int, val missing: Int)

/**
 * One JSON file with playlists, favorites / ratings / play counts, imported lyrics and settings (incl. equalizer).
 * Songs are saved by title + artist + duration (not by id), so a backup also restores on a new phone.
 */
class BackupManager(private val ctx: Context) {
    private val lyricsDir = File(ctx.filesDir, "lyrics")
    private val prefFiles = listOf("settings", "audio_fx")
    private val skipKeys = setOf("lyrics_tree")      // folder permission can't move between phones

    fun export(songs: List<Song>, playlists: Map<String, List<Long>>, stats: Map<Long, SongStats>): String {
        val byId = songs.associateBy { it.id }
        val root = JSONObject()
        root.put("app", "com.app.musicplayer")
        root.put("version", 2)
        root.put("created", System.currentTimeMillis())

        val used = LinkedHashSet<Long>()
        playlists.values.forEach { used.addAll(it) }
        used.addAll(stats.keys)
        lyricsDir.listFiles()?.forEach { f -> f.nameWithoutExtension.toLongOrNull()?.let { used.add(it) } }

        val idents = JSONObject()
        for (id in used) {
            val s = byId[id] ?: continue
            idents.put(
                id.toString(),
                JSONObject().put("t", s.title).put("a", s.artist).put("al", s.album).put("d", s.durationMs),
            )
        }
        root.put("songs", idents)

        val pl = JSONObject()
        for ((name, ids) in playlists) pl.put(name, JSONArray(ids.filter { byId.containsKey(it) }))
        root.put("playlists", pl)

        val st = JSONObject()
        for ((id, s) in stats) {
            if (byId.containsKey(id)) st.put(id.toString(), "${if (s.favorite) 1 else 0},${s.rating},${s.plays},${s.lastPlayed},${s.updatedAt}")
        }
        root.put("stats", st)

        val ly = JSONObject()
        lyricsDir.listFiles()?.forEach { f ->
            val id = f.nameWithoutExtension.toLongOrNull()
            if (id != null && byId.containsKey(id)) ly.put(id.toString(), runCatching { f.readText() }.getOrDefault(""))
        }
        root.put("lyrics", ly)

        val prefsJson = JSONObject()
        for (name in prefFiles) prefsJson.put(name, dumpPrefs(ctx.getSharedPreferences(name, Context.MODE_PRIVATE)))
        root.put("prefs", prefsJson)
        return root.toString()
    }

    /** Read an import stream with a strict byte cap before allocating the JSON string. */
    fun restore(input: InputStream, songs: List<Song>, playlistStore: PlaylistStore, statsStore: StatsStore): RestoreResult {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_BACKUP_BYTES) { "Backup is too large to restore safely." }
            output.write(buffer, 0, count)
        }
        return restore(String(output.toByteArray(), Charsets.UTF_8), songs, playlistStore, statsStore)
    }

    fun restore(json: String, songs: List<Song>, playlistStore: PlaylistStore, statsStore: StatsStore): RestoreResult {
        require(json.length <= MAX_BACKUP_CHARS) { "Backup is too large to restore safely." }
        val root = runCatching { JSONObject(json) }
            .getOrElse { throw IllegalArgumentException("Backup JSON is damaged or incomplete.", it) }
        validateBackup(root)

        // library index: title|artist -> songs
        val index = HashMap<String, MutableList<Song>>()
        for (s in songs) index.getOrPut(norm(s.title) + "|" + norm(s.artist)) { ArrayList() }.add(s)

        val idents = root.optJSONObject("songs") ?: JSONObject()
        val map = HashMap<Long, Song>()       // old id -> song on this device
        var missing = 0
        for (key in idents.keys()) {
            val o = idents.getJSONObject(key)
            val cands = index[norm(o.optString("t")) + "|" + norm(o.optString("a"))].orEmpty()
            val d = o.optLong("d")
            val hit = cands.firstOrNull { abs(it.durationMs - d) <= 2000 } ?: cands.singleOrNull()
            val oldId = key.toLongOrNull()
            if (hit != null && oldId != null) map[oldId] = hit else missing++
        }

        // playlists: merge, keep existing order and add new songs at the end
        val current = playlistStore.lists.value
        val merged = LinkedHashMap<String, List<Long>>(current)
        val changed = HashSet<String>()
        val pl = root.optJSONObject("playlists") ?: JSONObject()
        var plCount = 0
        for (name in pl.keys()) {
            val arr = pl.getJSONArray(name)
            val ids = (0 until arr.length()).mapNotNull { map[arr.getLong(it)]?.id }
            val existing = merged[name].orEmpty()
            val combined = existing + ids.filter { it !in existing }
            if (combined != existing || name !in current) changed.add(name)
            merged[name] = combined
            plCount++
        }
        playlistStore.mergeRestore(merged, changed)

        // stats: favourite OR, rating from backup if set, bigger play count / latest play
        val st = root.optJSONObject("stats") ?: JSONObject()
        val updates = HashMap<Long, SongStats>()
        for (key in st.keys()) {
            val song = key.toLongOrNull()?.let { map[it] } ?: continue
            val p = st.getString(key).split(',')
            if (p.size < 4) continue
            val old = statsStore.stats.value[song.id] ?: SongStats()
            val rating = p[1].toIntOrNull() ?: 0
            updates[song.id] = SongStats(
                favorite = old.favorite || p[0] == "1",
                rating = if (rating > 0) rating else old.rating,
                plays = max(old.plays, p[2].toIntOrNull() ?: 0),
                lastPlayed = max(old.lastPlayed, p[3].toLongOrNull() ?: 0L),
                // Older backup files have no timestamp; treat an explicit restore as
                // a fresh local edit so cloud sync can resolve it deterministically.
                updatedAt = max(old.updatedAt, p.getOrNull(4)?.toLongOrNull() ?: System.currentTimeMillis()),
            )
        }
        statsStore.putAll(updates)

        // lyrics the user loaded by hand
        val ly = root.optJSONObject("lyrics") ?: JSONObject()
        lyricsDir.mkdirs()
        for (key in ly.keys()) {
            val song = key.toLongOrNull()?.let { map[it] } ?: continue
            val f = File(lyricsDir, "${song.id}.lrc")
            if (!f.exists()) runCatching { f.writeText(ly.getString(key)) }
        }

        // settings + equalizer (applied after the app restarts)
        val prefsJson = root.optJSONObject("prefs") ?: JSONObject()
        for (name in prefFiles) {
            val o = prefsJson.optJSONObject(name) ?: continue
            restorePrefs(ctx.getSharedPreferences(name, Context.MODE_PRIVATE), o)
        }
        return RestoreResult(plCount, map.size, missing)
    }

    /**
     * Validate the complete document before changing playlists, stats, lyrics or preferences.
     * This prevents a truncated/corrupt file from leaving a half-restored library.
     */
    private fun validateBackup(root: JSONObject) {
        require(root.optString("app") == "com.app.musicplayer") { "Not a Music Player backup" }
        val version = root.optInt("version", 1)
        require(version in 1..2) { "This backup version is not supported." }

        val songs = root.optJSONObject("songs")
            ?: throw IllegalArgumentException("Backup is missing its song index.")
        for (key in songs.keys()) {
            require(key.toLongOrNull() != null) { "Backup contains an invalid song ID." }
            val song = songs.optJSONObject(key)
                ?: throw IllegalArgumentException("Backup contains an invalid song record.")
            require(song.has("t") && song.has("a") && song.has("d")) {
                "Backup contains an incomplete song record."
            }
            require(song.optLong("d", -1L) >= 0L) { "Backup contains an invalid song duration." }
        }

        val playlists = root.optJSONObject("playlists")
            ?: throw IllegalArgumentException("Backup is missing playlists.")
        for (name in playlists.keys()) {
            require(name.isNotBlank()) { "Backup contains an empty playlist name." }
            val items = playlists.optJSONArray(name)
                ?: throw IllegalArgumentException("Backup contains an invalid playlist.")
            for (i in 0 until items.length()) {
                require(items.optLong(i, Long.MIN_VALUE) != Long.MIN_VALUE) {
                    "Backup contains an invalid playlist song reference."
                }
            }
        }

        val stats = root.optJSONObject("stats")
            ?: if (version >= 2) throw IllegalArgumentException("Backup is missing song statistics.") else JSONObject()
        for (key in stats.keys()) {
            require(key.toLongOrNull() != null) { "Backup contains an invalid statistics ID." }
            val fields = stats.optString(key, "").split(',')
            require(fields.size in 4..5 &&
                fields[0] in setOf("0", "1") &&
                fields[1].toIntOrNull()?.let { it in 0..5 } == true &&
                fields[2].toIntOrNull()?.let { it >= 0 } == true &&
                fields[3].toLongOrNull()?.let { it >= 0L } == true &&
                (fields.size == 4 || fields[4].toLongOrNull()?.let { it >= 0L } == true)
            ) { "Backup contains invalid song statistics." }
        }

        val lyrics = root.optJSONObject("lyrics")
            ?: if (version >= 2) throw IllegalArgumentException("Backup is missing lyrics data.") else JSONObject()
        for (key in lyrics.keys()) {
            require(key.toLongOrNull() != null && lyrics.opt(key) is String) {
                "Backup contains invalid lyrics data."
            }
        }

        val prefs = root.optJSONObject("prefs")
            ?: if (version >= 2) throw IllegalArgumentException("Backup is missing settings.") else JSONObject()
        for (name in prefFiles) {
            val entries = prefs.optJSONObject(name) ?: continue
            for (key in entries.keys()) {
                val entry = entries.optJSONObject(key)
                    ?: throw IllegalArgumentException("Backup contains an invalid setting.")
                when (entry.optString("t")) {
                    "b" -> entry.getBoolean("v")
                    "i" -> entry.getInt("v")
                    "l" -> entry.getLong("v")
                    "f" -> require(entry.getDouble("v").isFinite()) { "Backup contains an invalid setting value." }
                    "s" -> entry.getString("v")
                }
            }
        }
    }

    private fun dumpPrefs(p: SharedPreferences): JSONObject {
        val out = JSONObject()
        for ((k, v) in p.all) {
            if (k in skipKeys) continue
            val e = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is String -> JSONObject().put("t", "s").put("v", v)
                else -> null
            }
            if (e != null) out.put(k, e)
        }
        return out
    }

    private fun restorePrefs(p: SharedPreferences, o: JSONObject) {
        val ed = p.edit()
        for (k in o.keys()) {
            if (k in skipKeys) continue
            val e = o.getJSONObject(k)
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "s" -> ed.putString(k, e.getString("v"))
            }
        }
        ed.apply()
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()


    private companion object {
        const val MAX_BACKUP_CHARS = 5 * 1024 * 1024
        const val MAX_BACKUP_BYTES = 5L * 1024 * 1024
    }
}
