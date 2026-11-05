package com.app.musicplayer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Tiny persistent playlist store: name -> ordered MediaStore ids. */
class PlaylistStore(context: Context) {
    private val prefs = context.getSharedPreferences("playlists", Context.MODE_PRIVATE)
    private val _lists = MutableStateFlow(load())
    val lists: StateFlow<Map<String, List<Long>>> = _lists.asStateFlow()

    fun create(name: String) {
        val n = name.trim()
        if (n.isEmpty() || _lists.value.containsKey(n)) return
        commit(_lists.value + (n to emptyList()))
    }

    fun delete(name: String) = commit(_lists.value - name)

    fun addSong(name: String, songId: Long) {
        val cur = _lists.value[name] ?: return
        if (songId in cur) return
        commit(_lists.value + (name to cur + songId))
    }

    fun removeSong(name: String, songId: Long) {
        val cur = _lists.value[name] ?: return
        commit(_lists.value + (name to cur - songId))
    }

    private fun commit(map: Map<String, List<Long>>) {
        _lists.value = map
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        prefs.edit().putString("data", o.toString()).apply()
    }

    private fun load(): Map<String, List<Long>> = try {
        val o = JSONObject(prefs.getString("data", null) ?: "{}")
        val out = LinkedHashMap<String, List<Long>>()
        for (k in o.keys()) {
            val a = o.getJSONArray(k)
            out[k] = List(a.length()) { a.getLong(it) }
        }
        out
    } catch (e: Exception) {
        emptyMap()
    }
}
