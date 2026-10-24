package com.wizmusicsync

/** One bulb colour: RGB plus brightness (WiZ "dimming", 10-100). */
data class LightColor(val r: Int, val g: Int, val b: Int, val dimming: Int)

/** The bulb's dedicated white LEDs: colour temperature in kelvin plus brightness. */
data class WhiteLight(val temp: Int, val dimming: Int)

/** What the bulb shows for each state the engine detects. User-editable; [DEFAULT] is the original script. */
data class LightTheme(
    /** Quiet, mids, drive, high — in rising order. */
    val tiers: List<LightColor>,
    val drop: LightColor,
    val idle: WhiteLight,
) {
    companion object {
        val DEFAULT = LightTheme(
            tiers = listOf(
                LightColor(0, 0, 255, 60),    // Low background wave
                LightColor(0, 255, 0, 70),    // Vocals / Mids
                LightColor(255, 120, 0, 85),  // Synths / Drive
                LightColor(255, 0, 160, 95),  // High intensity
            ),
            drop = LightColor(255, 0, 0, 100), // Peak bass kick
            idle = WhiteLight(4200, 100),      // Paused / silent
        )
    }
}
