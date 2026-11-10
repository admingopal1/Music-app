package com.app.musicplayer.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Spark-tier, user-scoped sync. Local IDs are never uploaded as device-global IDs;
 * tracks are matched by normalized title/artist/album/duration on each device.
 * Playlist snapshots use timestamps; a new device cannot overwrite newer cloud state.
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
        // Retry the complete merge if another device writes between our read and commit.
        repeat(4) {
            try {
                return syncOnce(songs, playlists, stats)
            } catch (_: SyncConflictException) {
                // Re-read and recompute from the latest cloud snapshot.
            }
        }
        return "Cloud library changed repeatedly on another device. Please retry sync."
    }

    private class SyncConflictException : Exception()

    private suspend fun syncOnce(
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
        val remoteSnapshot = libraryDoc.get().await()
        val remote = remoteSnapshot.data ?: emptyMap<String, Any>()
        val remoteLists = remote["playlists"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val remoteUpdated = (remote["playlistUpdatedAt"] as? Map<*, *>)?.mapNotNull { (k, v) ->
            (k as? String)?.let { it to ((v as? Number)?.toLong() ?: 0L) }
        }?.toMap().orEmpty()
        val remoteDeletedAt = (remote["playlistDeletedAt"] as? Map<*, *>)?.mapNotNull { (k, v) ->
            (k as? String)?.let { it to ((v as? Number)?.toLong() ?: 0L) }
        }?.toMap().orEmpty()
        // Migrate older name-only tombstones into timestamped delete markers.
        val legacyDeleted = (remote["deletedPlaylists"] as? List<*>)?.mapNotNull { it as? String }
            ?.associateWith { name -> remoteDeletedAt[name] ?: remoteUpdated[name] ?: System.currentTimeMillis() }.orEmpty()
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
                // A playlist snapshot is authoritative by its edit timestamp. This
                // propagates song removals as well as additions across devices.
                val localIsNewer = (localUpdated[name] ?: 0L) > (remoteUpdated[name] ?: 0L)
                val selectedTracks = when {
                    localIsNewer -> localTracks
                    (remoteUpdated[name] ?: 0L) > 0L -> remoteTracks
                    else -> remoteTracks + localTracks // legacy records without timestamps
                }
                val unique = LinkedHashMap<String, Map<String, Any>>()
                selectedTracks.forEach { item ->
                    val map = item as? Map<String, Any> ?: return@forEach
                    val key = map["key"] as? String ?: return@forEach
                    unique[key] = map
                }
                mergedLists[name] = unique.values.toList()
            }
        }
        // Retain deletion markers until the full snapshot can be safely compacted.
        // Silently pruning a marker could resurrect a stale playlist from an offline device.
        val boundedDeletedAt = mergedDeletedAt
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
            "playlistUpdatedAt" to mergedUpdatedAt,
            "playlistDeletedAt" to boundedDeletedAt,
            "deletedPlaylists" to boundedDeletedAt.keys.toList(),
            "stats" to mergedStats,
            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
        )
        // Firestore documents are limited to 1 MiB. Keep a safety margin and fail
        // before writing rather than risking a rejected sync.
        val estimatedBytes = org.json.JSONObject(mapOf("schemaVersion" to 2, "playlists" to mergedLists, "playlistUpdatedAt" to mergedUpdatedAt, "playlistDeletedAt" to boundedDeletedAt, "stats" to mergedStats)).toString().toByteArray(Charsets.UTF_8).size
        require(estimatedBytes <= 700_000) {
            "Cloud library is too large for the safe Firestore document limit. Use local Backup/Restore for the full library."
        }

        // Replace this dedicated library document so removed playlists and
        // pruned tombstones are actually removed (merge would retain stale map keys).
        // Optimistic transaction: commit only if the document is still the exact
        // snapshot used for this merge. A competing device forces a full re-read/re-merge.
        val committed = db.runTransaction { transaction ->
            val latestSnapshot = transaction.get(libraryDoc)
            if (latestSnapshot.data != remoteSnapshot.data) {
                false
            } else {
                transaction.set(libraryDoc, payload)
                true
            }
        }.await()
        if (!committed) throw SyncConflictException()

        // Merge remote entries back into this device, matching by track metadata.
        val index = songs.groupBy { trackKey(it) }
        val restoredLists = LinkedHashMap<String, List<Long>>()
        mergedLists.keys.forEach { name -> restoredLists[name] = playlists.lists.value[name].orEmpty() }
        mergedLists.forEach { (name, tracks) ->
            val ids = tracks.mapNotNull { track ->
                val key = track["key"] as? String ?: return@mapNotNull null
                index[key]?.firstOrNull()?.id
            }
            // The merged snapshot already resolved the newest edit timestamp. Applying
            // it exactly is required for removals to propagate to this device.
            restoredLists[name] = ids
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
