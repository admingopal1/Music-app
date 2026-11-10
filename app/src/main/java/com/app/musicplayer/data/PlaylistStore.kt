package com.app.musicplayer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Persistent playlists with timestamped edits/deletions for conflict-safe cloud sync. */
class PlaylistStore(context: Context) {
    private val prefs = context.getSharedPreferences("playlists", Context.MODE_PRIVATE)
    private val _lists = MutableStateFlow(load())
    val lists: StateFlow<Map<String, List<Long>>> = _lists.asStateFlow()
    private val _updatedAt = MutableStateFlow(loadTimestamps("updated_at"))
    val updatedAt: StateFlow<Map<String, Long>> = _updatedAt.asStateFlow()
    private val _deletedAt = MutableStateFlow(loadTimestamps("deleted_at"))
    val deletedAt: StateFlow<Map<String, Long>> = _deletedAt.asStateFlow()

    @Synchronized
    fun create(name: String) {
        val n = name.trim()
        if (n.isEmpty() || _lists.value.containsKey(n)) return
        val now = nextTimestamp(n)
        persistDeletedAt(_deletedAt.value - n)
        persistUpdatedAt(_updatedAt.value + (n to now))
        commit(_lists.value + (n to emptyList()))
    }

    @Synchronized
    fun delete(name: String) {
        if (name !in _lists.value) return
        val now = nextTimestamp(name)
        persistUpdatedAt(_updatedAt.value - name)
        persistDeletedAt(_deletedAt.value + (name to now))
        commit(_lists.value - name)
    }

    @Synchronized
    fun replaceAll(map: Map<String, List<Long>>) {
        persistUpdatedAt(map.keys.associateWith { nextTimestamp(it) })
        persistDeletedAt(emptyMap())
        commit(map)
    }

    /** Apply a resolved cloud snapshot and its timestamped conflict metadata. */
    @Synchronized
    fun applyCloudMerge(map: Map<String, List<Long>>, updated: Map<String, Long>, deleted: Map<String, Long>) {
        persistUpdatedAt(updated.filterKeys { it in map.keys })
        persistDeletedAt(deleted.filterKeys { it !in map.keys })
        commit(map)
    }

    /**
     * Atomically checks that no local edit happened during network sync before
     * applying the remote snapshot. Prevents a check/apply race from losing edits.
     */
    @Synchronized
    fun applyCloudMergeIfUnchanged(
        expectedLists: Map<String, List<Long>>,
        expectedUpdated: Map<String, Long>,
        expectedDeleted: Map<String, Long>,
        mergedLists: Map<String, List<Long>>,
        mergedUpdated: Map<String, Long>,
        mergedDeleted: Map<String, Long>,
    ): Boolean {
        if (_lists.value != expectedLists ||
            _updatedAt.value != expectedUpdated ||
            _deletedAt.value != expectedDeleted
        ) return false

        persistUpdatedAt(mergedUpdated.filterKeys { it in mergedLists.keys })
        persistDeletedAt(mergedDeleted.filterKeys { it !in mergedLists.keys })
        commit(mergedLists)
        return true
    }

    @Synchronized
    fun addSong(name: String, songId: Long) {
        val cur = _lists.value[name] ?: return
        if (songId in cur) return
        persistUpdatedAt(_updatedAt.value + (name to nextTimestamp(name)))
        commit(_lists.value + (name to cur + songId))
    }

    @Synchronized
    fun removeSong(name: String, songId: Long) {
        val cur = _lists.value[name] ?: return
        if (songId !in cur) return
        persistUpdatedAt(_updatedAt.value + (name to nextTimestamp(name)))
        commit(_lists.value + (name to cur - songId))
    }

    // Ensure two quick edits on the same playlist still have a strict local order.
    private fun nextTimestamp(name: String): Long = maxOf(
        System.currentTimeMillis(),
        maxOf(_updatedAt.value[name] ?: 0L, _deletedAt.value[name] ?: 0L) + 1L,
    )

    private fun persistUpdatedAt(values: Map<String, Long>) {
        _updatedAt.value = values
        prefs.edit().putString("updated_at", JSONObject(values.mapValues { it.value }).toString()).apply()
    }

    private fun persistDeletedAt(values: Map<String, Long>) {
        _deletedAt.value = values
        prefs.edit().putString("deleted_at", JSONObject(values.mapValues { it.value }).toString()).apply()
    }

    private fun loadTimestamps(key: String): Map<String, Long> = try {
        val o = JSONObject(prefs.getString(key, "{}") ?: "{}")
        buildMap { for (name in o.keys()) put(name, o.optLong(name, 0L)) }
    } catch (_: Exception) {
        emptyMap()
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
    } catch (_: Exception) {
        emptyMap()
    }
}
