package com.wizmusicsync

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port of the laptop script's analysis loop (tiers, bass drop, idle white).
 * Feed it every captured chunk; it returns the bulb command to send, if any.
 * Pure Kotlin with no Android dependencies, so it can be checked against the Python original.
 */
class LightEngine(private val rate: Int, startTime: Double) {

    sealed class Command {
        data class Pilot(val r: Int, val g: Int, val b: Int, val dimming: Int) : Command()
        data object White : Command()
    }

    data class Tier(val name: String, val r: Int, val g: Int, val b: Int, val dim: Int)

    companion object {
        // ----------------- DISCRETE COLOR TIERS -----------------
        val COLOR_TIERS = listOf(
            Tier("LOW/BLUE", 0, 0, 255, 60),       // Low background wave
            Tier("MIDS/GREEN", 0, 255, 0, 70),     // Vocals / Mids
            Tier("DRIVE/AMBER", 255, 120, 0, 85),  // Synths / Drive
            Tier("HIGH/MAGENTA", 255, 0, 160, 95), // High intensity
        )

        val COLOR_DROP = Tier("BASS DROP", 255, 0, 0, 100) // Peak bass kick
    }

    private val historyImpact = DoubleArray(50) { 100.0 }
    private val historyBass = DoubleArray(25) { 10.0 }
    private var lastSend = 0.0
    private var activeTierIdx = 0
    private var lastDropTime = 0.0

    // Silence tracking
    private var isCurrentlyIdle = false
    private var silenceStartTime = startTime

    /** Human-readable line equivalent to the script's console output. */
    var status = "Waiting for audio..."
        private set

    /**
     * @param interleaved PCM 16-bit samples, [frames] x [channels] interleaved
     * @param now monotonic time in seconds
     */
    fun process(interleaved: ShortArray, frames: Int, channels: Int, now: Double): Command? {
        val samples = DoubleArray(frames)
        for (i in 0 until frames) {
            var sum = 0.0
            for (c in 0 until channels) sum += interleaved[i * channels + c]
            samples[i] = sum / channels
        }

        var sumSquares = 0.0
        for (x in samples) sumSquares += x * x
        val rms = sqrt(sumSquares / frames)

        // ----------------- SILENCE / IDLE DETECTION -----------------
        // If audio is paused or muted (RMS < 35 for 1.2 continuous seconds)
        if (rms < 35.0) {
            if (now - silenceStartTime > 1.2) {
                if (!isCurrentlyIdle) {
                    isCurrentlyIdle = true
                    status = "[IDLE / NO MUSIC] Bright white light"
                    return Command.White
                }
                return null
            }
        } else {
            silenceStartTime = now
            if (isCurrentlyIdle) {
                isCurrentlyIdle = false
                status = "[MUSIC RESUMED] Resuming reactive light show..."
            }
        }

        // ----------------- AUDIO ANALYSIS & TIERS -----------------
        val bassEnergy = bassEnergy(samples)

        push(historyBass, max(10.0, bassEnergy))
        val avgBass = historyBass.average()

        val waveformImpact = (bassEnergy * 2.5) + rms

        push(historyImpact, max(40.0, waveformImpact))
        val avgImpact = historyImpact.average()
        val maxImpact = max(historyImpact.max(), avgImpact * 2.2)

        val normLevel = ((waveformImpact - (avgImpact * 0.45)) /
            (maxImpact - (avgImpact * 0.45) + 1e-6)).coerceIn(0.0, 1.0)

        val isBassDrop = (bassEnergy > avgBass * 1.55) && (rms > avgImpact * 0.5)

        val tier: Tier
        val tierName: String
        if (isBassDrop && (now - lastDropTime > 0.14)) {
            lastDropTime = now
            tier = COLOR_DROP
            tierName = "[BASS DROP FLASH]"
        } else {
            val targetTier = when {
                normLevel < 0.25 -> 0
                normLevel < 0.50 -> 1
                normLevel < 0.75 -> 2
                else -> 3
            }

            activeTierIdx = if (targetTier >= activeTierIdx) targetTier else max(0, activeTierIdx - 1)

            tier = COLOR_TIERS[activeTierIdx]
            tierName = "[${tier.name}]"
        }

        if (now - lastSend >= 0.075) {
            lastSend = now
            val bar = "#".repeat((normLevel * 20).toInt())
            status = "%-19s Level: %5.1f%% | Dim: %3d%%\n[%-20s]".format(tierName, normLevel * 100, tier.dim, bar)
            return Command.Pilot(tier.r, tier.g, tier.b, tier.dim)
        }
        return null
    }

    /** Mean |rfft| over the 35-160 Hz bins, computed directly for just those bins. */
    private fun bassEnergy(x: DoubleArray): Double {
        val n = x.size
        var sum = 0.0
        var count = 0
        for (k in 0..n / 2) {
            val freq = k * (rate.toDouble() / n)
            if (freq < 35.0 || freq > 160.0) continue
            var re = 0.0
            var im = 0.0
            val w = 2.0 * PI * k / n
            for (t in 0 until n) {
                re += x[t] * cos(w * t)
                im -= x[t] * sin(w * t)
            }
            sum += hypot(re, im)
            count++
        }
        return if (count > 0) sum / count else 0.0
    }

    /** np.roll(a, -1); a[-1] = value */
    private fun push(a: DoubleArray, value: Double) {
        System.arraycopy(a, 1, a, 0, a.size - 1)
        a[a.size - 1] = value
    }
}
