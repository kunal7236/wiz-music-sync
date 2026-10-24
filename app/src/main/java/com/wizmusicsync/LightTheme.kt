package com.wizmusicsync

/** One thing the bulb can show: an RGB colour or a white temperature, each with a brightness (WiZ "dimming", 10-100). */
sealed class BulbLight {
    abstract val dimming: Int

    data class Rgb(val r: Int, val g: Int, val b: Int, override val dimming: Int) : BulbLight()

    /** The bulb's dedicated white LEDs, colour temperature in kelvin. */
    data class White(val temp: Int, override val dimming: Int) : BulbLight()
}

/** What the bulb shows for each state the engine detects. User-editable; [DEFAULT] is the original script. */
data class LightTheme(
    /** Quiet, mids, drive, high — in rising order. */
    val tiers: List<BulbLight>,
    val drop: BulbLight,
    val idle: BulbLight,
) {
    companion object {
        val DEFAULT = LightTheme(
            tiers = listOf(
                BulbLight.Rgb(0, 0, 255, 60),    // Low background wave
                BulbLight.Rgb(0, 255, 0, 70),    // Vocals / Mids
                BulbLight.Rgb(255, 120, 0, 85),  // Synths / Drive
                BulbLight.Rgb(255, 0, 160, 95),  // High intensity
            ),
            drop = BulbLight.Rgb(255, 0, 0, 100), // Peak bass kick
            idle = BulbLight.White(4200, 100),    // Paused / silent
        )
    }
}
