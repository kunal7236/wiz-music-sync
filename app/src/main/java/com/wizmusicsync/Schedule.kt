package com.wizmusicsync

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One power schedule. Either a one-off timer ([atMillis] set: "off in 10 min") or a time of day
 * ([hour]/[minute], repeating on [days] or once when [days] is 0: "on at 07:00 on weekdays").
 * Pure Kotlin, no Android dependencies.
 */
data class Schedule(
    val id: Int,
    val turnOn: Boolean,
    /** Timer: absolute fire time in epoch millis. Null for time-of-day schedules. */
    val atMillis: Long? = null,
    val hour: Int = 0,
    val minute: Int = 0,
    /** Bit set of repeat days, Monday = bit 0 ... Sunday = bit 6; 0 = once. */
    val days: Int = 0,
    val enabled: Boolean = true,
) {
    val isTimer get() = atMillis != null
    /** Runs once and is then removed (timer) or switched off (one-off time). */
    val isOneOff get() = isTimer || days == 0

    companion object {
        const val EVERY_DAY = 0b1111111
        const val WEEKDAYS = 0b0011111
        const val WEEKENDS = 0b1100000

        fun dayBit(day: DayOfWeek) = 1 shl (day.value - 1)
    }

    /** Next moment this schedule should fire after [now], or null if it never will again. */
    fun nextTrigger(now: ZonedDateTime): Long? {
        if (!enabled) return null
        atMillis?.let { return if (it > now.toInstant().toEpochMilli()) it else null }
        val time = LocalTime.of(hour, minute)
        for (offset in 0..7) {
            val candidate = now.toLocalDate().plusDays(offset.toLong()).atTime(time).atZone(now.zone)
            if (!candidate.isAfter(now)) continue
            if (days == 0 || days and dayBit(candidate.dayOfWeek) != 0) return candidate.toInstant().toEpochMilli()
        }
        return null
    }

    fun nextTrigger(nowMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) =
        nextTrigger(ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone))
}

/** "Every day", "Weekdays", "Mon, Wed, Fri" or "Once". */
fun repeatLabel(days: Int): String = when (days) {
    0 -> "Once"
    Schedule.EVERY_DAY -> "Every day"
    Schedule.WEEKDAYS -> "Weekdays"
    Schedule.WEEKENDS -> "Weekends"
    else -> DayOfWeek.values().filter { days and Schedule.dayBit(it) != 0 }
        .joinToString(", ") { it.name.take(1) + it.name.substring(1, 3).lowercase() }
}
