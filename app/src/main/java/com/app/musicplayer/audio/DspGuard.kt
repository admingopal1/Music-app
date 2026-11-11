package com.app.musicplayer.audio

/** Process-wide switch: when enabled, custom PCM processors copy audio unchanged. */
object DspGuard {
    @Volatile
    var bypass: Boolean = false
}
