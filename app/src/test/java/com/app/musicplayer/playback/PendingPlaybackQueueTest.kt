package com.app.musicplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingPlaybackQueueTest {
    @Test fun drainsCommandsInFifoOrder() {
        val queue = PendingPlaybackQueue<String>()
        queue.enqueue("play")
        queue.enqueue("play-next")
        queue.enqueue("add")
        assertEquals(listOf("play", "play-next", "add"), queue.drain())
        assertEquals(emptyList<String>(), queue.drain())
    }

    @Test fun clearDropsSupersededPendingCommands() {
        val queue = PendingPlaybackQueue<String>()
        queue.enqueue("play-next")
        queue.clear()
        queue.enqueue("replace")
        assertEquals(listOf("replace"), queue.drain())
    }

    @Test fun commandsAddedAfterDrainAreRetained() {
        val queue = PendingPlaybackQueue<Int>()
        queue.enqueue(1)
        assertEquals(listOf(1), queue.drain())
        queue.enqueue(2)
        assertEquals(listOf(2), queue.drain())
    }
}
