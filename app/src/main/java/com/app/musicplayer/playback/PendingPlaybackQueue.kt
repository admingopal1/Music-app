package com.app.musicplayer.playback

/**
 * FIFO buffer for player commands received before MediaSession is connected.
 * The owner is responsible for draining it on the playback/UI thread.
 */
class PendingPlaybackQueue<T> {
    private val pending = ArrayList<T>()

    fun enqueue(action: T) {
        pending.add(action)
    }

    fun clear() {
        pending.clear()
    }

    fun drain(): List<T> {
        if (pending.isEmpty()) return emptyList()
        val actions = pending.toList()
        pending.clear()
        return actions
    }
}
