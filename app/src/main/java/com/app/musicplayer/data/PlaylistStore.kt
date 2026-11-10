package com.app.musicplayer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Persistent playlists plus deletion markers used to propagate deletes between devices. */
class PlaylistStore(context: Context) {
    private val prefs = context.getSharedPreferences("playlists", Context.MODE_PRIVATE)
    private val _lists = MutableStateFlow(load())
    val lists: StateFlow<Map<String, List<Long>>> = _lists.asStateFlow()
    private val _deletedNames = MutableStateFlow(loadDeletedNames())
    val deletedNames: StateFlow<Set<String>> = _deletedNames.asStateFlow()

    fun create(name: String) {
        val n = name.trim()
        if (n.isEmpty() || _lists.value.containsKey(n)) return
        val deleted = _deletedNames.value - n
        persistDeletedNames(deleted)
        commit(_lists.value + (n to emptyList()))
    }

    fun delete(name: String) {
        if (name !in _lists.value) return
        persistDeletedNames(_deletedNames.value + name)
        commit(_lists.value - name)
    }

    fun replaceAll(map: Map<String, List<Long>>) = commit(map)

    /** Apply a merged cloud snapshot without losing durable delete markers. */
    fun applyCloudMerge(map: Map<String, List<Long>>, deleted: Set<String>) {
        val active = map.keys
        persistDeletedNames(deleted - active)
        commit(map.filterKeys { it !in deleted || it in active })
    }

    fun addSong(name: String, songId: Long) {
        val cur = _lists.value[name] ?: return
        if (songId in cur) return
        commit(_lists.value + (name to cur + songId))
    }

    fun removeSong(name: String, songId: Long) {
        val cur = _lists.value[name] ?: return
        commit(_lists.value + (name to cur - songId))
    }

    private fun persistDeletedNames(names: Set<String>) {
        _deletedNames.value = names
        prefs.edit().putString("deleted_names", JSONArray(names.sorted()).toString()).apply()
    }

    private fun commit(map: Map<String, List<Long>>) {
        _lists.value = map
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        prefs.edit().putString("data", o.toString()).apply()
    }

    private fun loadDeletedNames(): Set<String> = try {
        val a = JSONArray(prefs.getString("deleted_names", "[]") ?: "[]")
        (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    private fun load(): Map<String, List<Long>> = try {
        val o = JSONObject(prefs.getString("data", null) ?: "{}")
        val out = LinkedHashMap<String, List<Long>>()
        for (k in o.keys()) {
            val a = o.getJSONArray(k)
            out[k] = List(a.length()) { a.getLong(it) }
        }
        out
    } catch (_: Exception) {
        emptyMap()
    }
}
