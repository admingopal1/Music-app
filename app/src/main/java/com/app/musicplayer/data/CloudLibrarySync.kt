package com.app.musicplayer.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * Spark-tier, user-scoped sync. Local IDs are never uploaded as device-global IDs;
 * tracks are matched by normalized title/artist/album/duration on each device.
 * Sync is additive so an empty/new device cannot erase another device's library data.
 */
class CloudLibrarySync(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    suspend fun sync(
        songs: List<Song>,
        playlists: PlaylistStore,
        stats: StatsStore,
    ): String {
        val user = auth.currentUser ?: return "Please sign in before syncing."
        val byId = songs.associateBy { it.id }
        val userDoc = db.collection("users").document(user.uid)
        val libraryDoc = userDoc.collection("private").document("library")
        val localLists = playlists.lists.value
        val localStats = stats.stats.value

        val localPlaylistPayload = localLists.mapValues { (_, ids) ->
            ids.mapNotNull { id -> byId[id]?.let(::trackPayload) }
        }
        val localStatsPayload = localStats.mapNotNull { (id, stat) ->
            byId[id]?.let { song ->
                trackKey(song) to mapOf(
                    "track" to trackPayload(song),
                    "favorite" to stat.favorite,
                    "rating" to stat.rating,
                    "plays" to stat.plays,
                    "lastPlayed" to stat.lastPlayed,
                    "updatedAt" to stat.updatedAt,
                )
            }
        }.toMap()

        // A single bounded document avoids write-per-track costs on the free tier.
        val remote = libraryDoc.get().await().data ?: emptyMap<String, Any>()
        val remoteLists = remote["playlists"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val remoteUpdated = (remote["playlistUpdatedAt"] as? Map<*, *>)?.mapNotNull { (k, v) ->
            (k as? String)?.let { it to ((v as? Number)?.toLong() ?: 0L) }
        }?.toMap().orEmpty()
        val remoteDeletedAt = (remote["playlistDeletedAt"] as? Map<*, *>)?.mapNotNull { (k, v) ->
            (k as? String)?.let { it to ((v as? Number)?.toLong() ?: 0L) }
        }?.toMap().orEmpty()
        // Migrate older name-only tombstones into timestamped delete markers.
        val legacyDeleted = (remote["deletedPlaylists"] as? List<*>)?.mapNotNull { it as? String }
            ?.associateWith { remoteUpdated[it] ?: 1L }.orEmpty()
        val localUpdated = playlists.updatedAt.value
        val localDeletedAt = playlists.deletedAt.value
        val names = (remoteLists.keys.mapNotNull { it as? String } + localPlaylistPayload.keys +
            remoteUpdated.keys + remoteDeletedAt.keys + legacyDeleted.keys +
            localUpdated.keys + localDeletedAt.keys).distinct()
        val mergedUpdatedAt = LinkedHashMap<String, Long>()
        val mergedDeletedAt = LinkedHashMap<String, Long>()
        val mergedLists = LinkedHashMap<String, List<Map<String, Any>>>()
        names.forEach { name ->
            val editAt = maxOf(remoteUpdated[name] ?: 0L, localUpdated[name] ?: 0L)
            val deleteAt = maxOf(remoteDeletedAt[name] ?: 0L, legacyDeleted[name] ?: 0L, localDeletedAt[name] ?: 0L)
            if (deleteAt >= editAt && deleteAt > 0L) {
                mergedDeletedAt[name] = deleteAt
            } else {
                if (editAt > 0L) mergedUpdatedAt[name] = editAt
                val remoteTracks = remoteLists[name] as? List<*> ?: emptyList<Any>()
                val localTracks = localPlaylistPayload[name].orEmpty()
                val combined = LinkedHashMap<String, Map<String, Any>>()
                (remoteTracks + localTracks).forEach { item ->
                    val map = item as? Map<String, Any> ?: return@forEach
                    val key = map["key"] as? String ?: return@forEach
                    combined[key] = map
                }
                mergedLists[name] = combined.values.toList()
            }
        }
        // Keep the newest 500 delete markers to bound the single Firestore document.
        val boundedDeletedAt = mergedDeletedAt.entries
            .sortedByDescending { it.value }.take(500).associate { it.toPair() }
        val remoteStats = remote["stats"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val mergedStats = LinkedHashMap<String, Map<String, Any>>()
        (remoteStats.keys.mapNotNull { it as? String } + localStatsPayload.keys).distinct().forEach { key ->
            val old = remoteStats[key] as? Map<*, *> ?: emptyMap<Any, Any>()
            val fresh = localStatsPayload[key].orEmpty()
            val oldTrack = old["track"] as? Map<String, Any>
            val newTrack = fresh["track"] as? Map<String, Any>
            val track = newTrack ?: oldTrack ?: return@forEach
            val oldRating = (old["rating"] as? Number)?.toInt() ?: 0
            val newRating = (fresh["rating"] as? Number)?.toInt() ?: 0
            val oldUpdatedAt = (old["updatedAt"] as? Number)?.toLong() ?: 0L
            val newUpdatedAt = (fresh["updatedAt"] as? Number)?.toLong() ?: 0L
            val preferLocal = fresh.isNotEmpty() && newUpdatedAt >= oldUpdatedAt
            mergedStats[key] = mapOf(
                "track" to track,
                "favorite" to if (preferLocal) ((fresh["favorite"] as? Boolean) == true) else ((old["favorite"] as? Boolean) == true),
                "rating" to if (preferLocal) newRating else oldRating,
                "updatedAt" to maxOf(oldUpdatedAt, newUpdatedAt),
                "plays" to maxOf((old["plays"] as? Number)?.toInt() ?: 0, (fresh["plays"] as? Number)?.toInt() ?: 0),
                "lastPlayed" to maxOf((old["lastPlayed"] as? Number)?.toLong() ?: 0L, (fresh["lastPlayed"] as? Number)?.toLong() ?: 0L),
            )
        }

        val payload = mapOf(
            "schemaVersion" to 2,
            "playlists" to mergedLists,
            "playlistUpdatedAt" to mergedUpdatedAt,\n            "playlistDeletedAt" to boundedDeletedAt,\n            "deletedPlaylists" to boundedDeletedAt.keys.toList(),
            "stats" to mergedStats,
            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
        )
        // Firestore documents are limited to 1 MiB. Keep a safety margin and fail
        // before writing rather than risking a rejected sync.
        val estimatedBytes = org.json.JSONObject(mapOf("schemaVersion" to 2, "playlists" to mergedLists, "playlistUpdatedAt" to mergedUpdatedAt, "playlistDeletedAt" to boundedDeletedAt, "stats" to mergedStats)).toString().toByteArray(Charsets.UTF_8).size
        require(estimatedBytes <= 700_000) {
            "Cloud library is too large for the safe Firestore document limit. Use local Backup/Restore for the full library."
        }

        libraryDoc.set(
            payload,
            SetOptions.merge(),
        ).await()

        // Merge remote entries back into this device, matching by track metadata.
        val index = songs.groupBy { trackKey(it) }
        val restoredLists = LinkedHashMap<String, List<Long>>()
        mergedLists.keys.forEach { name -> restoredLists[name] = playlists.lists.value[name].orEmpty() }
        mergedLists.forEach { (name, tracks) ->
            val ids = tracks.mapNotNull { track ->
                val key = track["key"] as? String ?: return@mapNotNull null
                index[key]?.firstOrNull()?.id
            }
            val existing = restoredLists[name].orEmpty()
            restoredLists[name] = existing + ids.filterNot { it in existing }
        }
        playlists.applyCloudMerge(restoredLists, mergedUpdatedAt, boundedDeletedAt)

        val updates = HashMap<Long, SongStats>()
        mergedStats.forEach { (key, value) ->
            val song = index[key]?.firstOrNull() ?: return@forEach
            val current = stats.stats.value[song.id] ?: SongStats()
            val rating = (value["rating"] as? Number)?.toInt() ?: current.rating
            updates[song.id] = SongStats(
                favorite = (value["favorite"] as? Boolean) ?: current.favorite,
                rating = rating.coerceIn(0, 5),
                plays = maxOf(current.plays, (value["plays"] as? Number)?.toInt() ?: 0),
                lastPlayed = maxOf(current.lastPlayed, (value["lastPlayed"] as? Number)?.toLong() ?: 0L),
                updatedAt = maxOf(current.updatedAt, (value["updatedAt"] as? Number)?.toLong() ?: 0L),
            )
        }
        stats.putAll(updates)
        return "Cloud sync complete: ${mergedLists.size} playlists and ${updates.size} matched tracks. Local data was merged, not replaced."
    }

    private fun trackPayload(song: Song): Map<String, Any> = mapOf(
        "key" to trackKey(song),
        "title" to song.title.take(200),
        "artist" to song.artist.take(200),
        "album" to song.album.take(200),
        "durationMs" to song.durationMs,
    )

    private fun trackKey(song: Song): String =
        listOf(song.title, song.artist, song.album, song.durationMs.toString())
            .joinToString("|") { it.trim().lowercase().replace(Regex("\\s+"), " ") }
}
