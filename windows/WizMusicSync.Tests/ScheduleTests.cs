using WizMusicSync.Core;
using Xunit;

namespace WizMusicSync.Tests;

/// <summary>Same cases as the Android app's ScheduleTest.</summary>
public class ScheduleTests
{
    // Fixed offset (India has no daylight saving), so results don't depend on the test machine
    private static readonly TimeZoneInfo Zone = TimeZoneInfo.CreateCustomTimeZone("IST", TimeSpan.FromHours(5.5), "IST", "IST");

    // Saturday 24 Oct 2026, 10:30
    private static readonly DateTimeOffset SaturdayMorning = At(24, 10, 30);

    private static DateTimeOffset At(int day, int hour, int minute) => new(2026, 10, day, hour, minute, 0, TimeSpan.FromHours(5.5));
    private static long Ms(int day, int hour, int minute) => At(day, hour, minute).ToUnixTimeMilliseconds();

    [Fact]
    public void OnceLaterTodayFiresToday() =>
        Assert.Equal(Ms(24, 12, 0), new Schedule(1, true, Hour: 12, Minute: 0).NextTrigger(SaturdayMorning, Zone));

    [Fact]
    public void OnceEarlierTodayFiresTomorrow() =>
        Assert.Equal(Ms(25, 7, 0), new Schedule(1, true, Hour: 7).NextTrigger(SaturdayMorning, Zone));

    [Fact]
    public void ExactlyNowIsNotNow() =>
        Assert.Equal(Ms(25, 10, 30), new Schedule(1, true, Hour: 10, Minute: 30).NextTrigger(SaturdayMorning, Zone));

    [Fact]
    public void WeekdaysOnSaturdaySkipToMonday() =>
        Assert.Equal(Ms(26, 7, 0), new Schedule(1, true, Hour: 7, Days: Schedule.Weekdays).NextTrigger(SaturdayMorning, Zone));

    [Fact]
    public void WeekendsLaterTodayFireToday() =>
        Assert.Equal(Ms(24, 23, 30), new Schedule(1, false, Hour: 23, Minute: 30, Days: Schedule.Weekends).NextTrigger(SaturdayMorning, Zone));

    [Fact]
    public void SameTimeNextWeekWhenOnlyTodayHasPassed() =>
        Assert.Equal(Ms(31, 9, 0),
            new Schedule(1, true, Hour: 9, Days: Schedule.DayBit(DayOfWeek.Saturday)).NextTrigger(SaturdayMorning, Zone));

    [Fact]
    public void TimerFiresAtItsTimeAndNeverAfter()
    {
        long inTenMinutes = SaturdayMorning.AddMinutes(10).ToUnixTimeMilliseconds();
        var timer = new Schedule(1, false, AtMillis: inTenMinutes);
        Assert.Equal(inTenMinutes, timer.NextTrigger(SaturdayMorning, Zone));
        Assert.Null(timer.NextTrigger(SaturdayMorning.AddMinutes(11), Zone));
    }

    [Fact]
    public void DisabledNeverFires() =>
        Assert.Null(new Schedule(1, true, Hour: 12, Enabled: false).NextTrigger(SaturdayMorning, Zone));

    [Fact]
    public void RepeatLabels()
    {
        Assert.Equal("Once", Schedule.RepeatLabel(0));
        Assert.Equal("Every day", Schedule.RepeatLabel(Schedule.EveryDay));
        Assert.Equal("Weekdays", Schedule.RepeatLabel(Schedule.Weekdays));
        int monWedFri = Schedule.DayBit(DayOfWeek.Monday) | Schedule.DayBit(DayOfWeek.Wednesday) | Schedule.DayBit(DayOfWeek.Friday);
        Assert.Equal("Mon, Wed, Fri", Schedule.RepeatLabel(monWedFri));
    }
}
