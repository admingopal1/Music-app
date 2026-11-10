package com.app.musicplayer.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** RBJ "Audio EQ Cookbook" biquad, transposed direct form II (one instance per channel). */
class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0f; z2 = 0f }

    fun lowPass(rate: Int, hz: Double, q: Double = 0.7071) = design(rate, hz, q) { c, a ->
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + a, -2 * c, 1 - a)
    }

    fun highPass(rate: Int, hz: Double, q: Double = 0.7071) = design(rate, hz, q) { c, a ->
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + a, -2 * c, 1 - a)
    }

    /** Constant 0 dB peak gain band-pass. */
    fun bandPass(rate: Int, hz: Double, q: Double) = design(rate, hz, q) { c, a ->
        set(a, 0.0, -a, 1 + a, -2 * c, 1 - a)
    }

    fun peaking(rate: Int, hz: Double, q: Double, gainDb: Double) = design(rate, hz, q) { c, a ->
        val g = 10.0.pow(gainDb / 40.0)
        set(1 + a * g, -2 * c, 1 - a * g, 1 + a / g, -2 * c, 1 - a / g)
    }

    fun lowShelf(rate: Int, hz: Double, gainDb: Double) = shelf(rate, hz, gainDb, low = true)
    fun highShelf(rate: Int, hz: Double, gainDb: Double) = shelf(rate, hz, gainDb, low = false)

    private fun shelf(rate: Int, hz: Double, gainDb: Double, low: Boolean) = design(rate, hz, 0.7071) { c, a ->
        val g = 10.0.pow(gainDb / 40.0)
        val k = 2 * sqrt(g) * a
        if (low) set(
            g * ((g + 1) - (g - 1) * c + k), 2 * g * ((g - 1) - (g + 1) * c), g * ((g + 1) - (g - 1) * c - k),
            (g + 1) + (g - 1) * c + k, -2 * ((g - 1) + (g + 1) * c), (g + 1) + (g - 1) * c - k,
        ) else set(
            g * ((g + 1) + (g - 1) * c + k), -2 * g * ((g - 1) + (g + 1) * c), g * ((g + 1) + (g - 1) * c - k),
            (g + 1) - (g - 1) * c + k, 2 * ((g - 1) - (g + 1) * c), (g + 1) - (g - 1) * c - k,
        )
    }

    private inline fun design(rate: Int, hz: Double, q: Double, block: (Double, Double) -> Unit): Biquad {
        val f = hz.coerceIn(10.0, rate * 0.45)
        val w = 2 * PI * f / rate
        block(cos(w), sin(w) / (2 * q))
        return this
    }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
    }
}

/** One-pole envelope follower with separate attack / release times. */
class Envelope(rate: Int, attackMs: Double, releaseMs: Double) {
    private val att = coef(rate, attackMs)
    private val rel = coef(rate, releaseMs)
    var value = 0f
        private set

    fun process(x: Float): Float {
        value += (if (x > value) att else rel) * (x - value)
        return value
    }

    fun reset() { value = 0f }

    companion object {
        fun coef(rate: Int, ms: Double): Float = (1.0 - kotlin.math.exp(-1000.0 / (ms * rate))).toFloat()
    }
}
