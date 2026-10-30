package com.wizmusicsync

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port of the laptop script's analysis loop (tiers, bass drop, idle white).
 * Feed it every captured chunk; it returns what the bulb should show, if a command is due.
 * Pure Kotlin with no Android dependencies, so it can be checked against the Python original.
 *
 * @param theme read on every chunk, so colour edits apply while syncing
 * @param sensitivity multiplier on the music level before picking a tier; 1.0 is the original script
 * @param idleOnPause true (the original script) switches to the theme's idle light when the music
 *   pauses; false freezes the bulb on the colour it was showing until the music resumes
 */
class LightEngine(
    private val rate: Int,
    startTime: Double,
    private val theme: () -> LightTheme = { LightTheme.DEFAULT },
    private val sensitivity: () -> Double = { 1.0 },
    private val idleOnPause: () -> Boolean = { true },
) {

    companion object {
        private val TIER_LABELS = listOf("LOW", "MIDS", "DRIVE", "HIGH")
    }

    private val historyImpact = DoubleArray(50) { 100.0 }
    private val historyBass = DoubleArray(25) { 10.0 }
    private val historyRms = DoubleArray(50) { 100.0 }
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
    fun process(interleaved: ShortArray, frames: Int, channels: Int, now: Double): BulbLight? {
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
            if (!idleOnPause()) {
                // Keep mode: send nothing during silence, so the bulb holds the colour it had when the
                // music stopped (analysing the silence would first step it down to the quiet colour)
                if (now - silenceStartTime > 1.2 && !isCurrentlyIdle) {
                    isCurrentlyIdle = true
                    status = "[PAUSED] Keeping the last colour"
                }
                return null
            }
            if (now - silenceStartTime > 1.2) {
                if (!isCurrentlyIdle) {
                    isCurrentlyIdle = true
                    status = "[IDLE / NO MUSIC]"
                    return theme().idle
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

        push(historyRms, rms)
        val avgRms = historyRms.average()

        val normLevel = ((waveformImpact - (avgImpact * 0.45)) /
            (maxImpact - (avgImpact * 0.45) + 1e-6)).coerceIn(0.0, 1.0)
        val level = (normLevel * sensitivity()).coerceIn(0.0, 1.0)

        // The script compared rms against avg_impact, which is dominated by bass FFT magnitude
        // (hundreds of times larger), so the flash never fired. Compare loudness with loudness.
        val isBassDrop = (bassEnergy > avgBass * 1.55) && (rms > avgRms * 0.5)

        val current = theme()
        val light: BulbLight
        val tierName: String
        if (isBassDrop && (now - lastDropTime > 0.14)) {
            lastDropTime = now
            light = current.drop
            tierName = "[BASS DROP FLASH]"
        } else {
            val targetTier = when {
                level < 0.25 -> 0
                level < 0.50 -> 1
                level < 0.75 -> 2
                else -> 3
            }

            activeTierIdx = if (targetTier >= activeTierIdx) targetTier else max(0, activeTierIdx - 1)

            light = current.tiers[activeTierIdx]
            tierName = "[${TIER_LABELS[activeTierIdx]}]"
        }

        if (now - lastSend >= 0.075) {
            lastSend = now
            val bar = "#".repeat((level * 20).toInt())
            status = "%-19s Level: %5.1f%% | Dim: %3d%%\n[%-20s]".format(tierName, level * 100, light.dimming, bar)
            return light
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
