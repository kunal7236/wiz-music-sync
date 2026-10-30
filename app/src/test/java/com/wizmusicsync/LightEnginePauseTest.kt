package com.wizmusicsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class LightEnginePauseTest {
    private val rate = 48000
    private val chunk = 1024
    private val chunkSeconds = chunk.toDouble() / rate

    /** 3 s of music (60 Hz kick every 0.5 s over a 440 Hz tone), 3 s of silence, 2 s of music. */
    private fun sampleAt(t: Double): Short {
        if (t in 3.0..6.0) return 0
        val kick = sin(2 * PI * 60 * t) * exp(-(t % 0.5) * 18) * 12000
        val tone = sin(2 * PI * 440 * t) * 4000
        return (kick + tone).toInt().toShort()
    }

    /** Runs the engine and returns (time, light) for every command it sends. */
    private fun run(idleOnPause: Boolean): List<Pair<Double, BulbLight>> {
        val engine = LightEngine(rate, 0.0, idleOnPause = { idleOnPause })
        val sent = mutableListOf<Pair<Double, BulbLight>>()
        val chunks = (8.0 / chunkSeconds).toInt()
        for (i in 0 until chunks) {
            val buf = ShortArray(chunk * 2) { sampleAt((i * chunk + it / 2).toDouble() / rate) }
            val now = (i + 1) * chunkSeconds
            engine.process(buf, chunk, 2, now)?.let { sent += now to it }
        }
        return sent
    }

    private fun during(sent: List<Pair<Double, BulbLight>>, from: Double, to: Double) =
        sent.filter { it.first > from && it.first <= to }.map { it.second }

    @Test
    fun keepModeHoldsLastColourThroughPause() {
        val sent = run(idleOnPause = false)
        // Nothing at all is sent while the music is paused, so the bulb keeps its colour
        assertEquals(emptyList<BulbLight>(), during(sent, 3.0 + chunkSeconds, 6.0))
        assertTrue(sent.none { it.second == LightTheme.DEFAULT.idle })
        // ...and colours resume with the music
        assertTrue(during(sent, 6.0, 8.0).isNotEmpty())
    }

    @Test
    fun idleModeSwitchesToIdleLightOnce() {
        val sent = run(idleOnPause = true)
        val pause = during(sent, 3.0, 6.0)
        assertEquals(1, pause.count { it == LightTheme.DEFAULT.idle })
        assertTrue(during(sent, 6.0, 8.0).isNotEmpty())
    }
}
