package com.wizmusicsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    // Saturday 24 Oct 2026, 10:30
    private val saturdayMorning = ZonedDateTime.of(2026, 10, 24, 10, 30, 0, 0, zone)

    private fun at(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun onceLaterTodayFiresToday() {
        val s = Schedule(id = 1, turnOn = true, hour = 12, minute = 0)
        assertEquals(at(24, 12, 0), s.nextTrigger(saturdayMorning))
    }

    @Test
    fun onceEarlierTodayFiresTomorrow() {
        val s = Schedule(id = 1, turnOn = true, hour = 7, minute = 0)
        assertEquals(at(25, 7, 0), s.nextTrigger(saturdayMorning))
    }

    @Test
    fun exactlyNowIsNotNow() {
        val s = Schedule(id = 1, turnOn = true, hour = 10, minute = 30)
        assertEquals(at(25, 10, 30), s.nextTrigger(saturdayMorning))
    }

    @Test
    fun weekdaysOnSaturdaySkipToMonday() {
        val s = Schedule(id = 1, turnOn = true, hour = 7, minute = 0, days = Schedule.WEEKDAYS)
        assertEquals(at(26, 7, 0), s.nextTrigger(saturdayMorning))
    }

    @Test
    fun weekendsLaterTodayFireToday() {
        val s = Schedule(id = 1, turnOn = false, hour = 23, minute = 30, days = Schedule.WEEKENDS)
        assertEquals(at(24, 23, 30), s.nextTrigger(saturdayMorning))
    }

    @Test
    fun sameTimeNextWeekWhenOnlyTodayHasPassed() {
        val saturdayOnly = Schedule.dayBit(java.time.DayOfWeek.SATURDAY)
        val s = Schedule(id = 1, turnOn = true, hour = 9, minute = 0, days = saturdayOnly)
        assertEquals(at(31, 9, 0), s.nextTrigger(saturdayMorning))
    }

    @Test
    fun timerFiresAtItsTimeAndNeverAfter() {
        val inTenMinutes = saturdayMorning.plusMinutes(10).toInstant().toEpochMilli()
        val timer = Schedule(id = 1, turnOn = false, atMillis = inTenMinutes)
        assertEquals(inTenMinutes, timer.nextTrigger(saturdayMorning))
        assertNull(timer.nextTrigger(saturdayMorning.plusMinutes(11)))
    }

    @Test
    fun disabledNeverFires() {
        assertNull(Schedule(id = 1, turnOn = true, hour = 12, minute = 0, enabled = false).nextTrigger(saturdayMorning))
    }

    @Test
    fun repeatLabels() {
        assertEquals("Once", repeatLabel(0))
        assertEquals("Every day", repeatLabel(Schedule.EVERY_DAY))
        assertEquals("Weekdays", repeatLabel(Schedule.WEEKDAYS))
        val monWedFri = listOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.FRIDAY)
            .sumOf { Schedule.dayBit(it) }
        assertEquals("Mon, Wed, Fri", repeatLabel(monWedFri))
    }
}
