namespace WizMusicSync.Core;

/// <summary>
/// One power schedule. Either a one-off timer (<see cref="AtMillis"/> set: "off in 10 min") or a time of
/// day (<see cref="Hour"/>/<see cref="Minute"/>, repeating on <see cref="Days"/> or once when it is 0:
/// "on at 07:00 on weekdays"). Same rules as the Android app.
/// </summary>
/// <param name="AtMillis">Timer: absolute fire time in Unix epoch milliseconds. Null for time-of-day schedules.</param>
/// <param name="Days">Bit set of repeat days, Monday = bit 0 ... Sunday = bit 6; 0 = once.</param>
public sealed record Schedule(
    int Id,
    bool TurnOn,
    long? AtMillis = null,
    int Hour = 0,
    int Minute = 0,
    int Days = 0,
    bool Enabled = true)
{
    public const int EveryDay = 0b1111111;
    public const int Weekdays = 0b0011111;
    public const int Weekends = 0b1100000;

    public bool IsTimer => AtMillis != null;

    /// <summary>Runs once and is then removed (timer) or switched off (one-off time).</summary>
    public bool IsOneOff => IsTimer || Days == 0;

    public static int DayBit(DayOfWeek day) => 1 << (((int)day + 6) % 7);

    /// <summary>Next moment this schedule should fire after <paramref name="now"/> (Unix ms), or null if it never will again.</summary>
    public long? NextTrigger(DateTimeOffset now, TimeZoneInfo zone)
    {
        if (!Enabled) return null;
        if (AtMillis is long at) return at > now.ToUnixTimeMilliseconds() ? at : null;

        var localToday = TimeZoneInfo.ConvertTime(now, zone).Date;
        for (int offset = 0; offset <= 7; offset++)
        {
            var date = localToday.AddDays(offset);
            var wall = date.AddHours(Hour).AddMinutes(Minute);
            var candidate = new DateTimeOffset(wall, zone.GetUtcOffset(wall));
            if (candidate <= now) continue;
            if (Days == 0 || (Days & DayBit(date.DayOfWeek)) != 0) return candidate.ToUnixTimeMilliseconds();
        }
        return null;
    }

    public long? NextTrigger() => NextTrigger(DateTimeOffset.Now, TimeZoneInfo.Local);

    /// <summary>"Every day", "Weekdays", "Mon, Wed, Fri" or "Once".</summary>
    public static string RepeatLabel(int days) => days switch
    {
        0 => "Once",
        EveryDay => "Every day",
        Weekdays => "Weekdays",
        Weekends => "Weekends",
        _ => string.Join(", ", MondayFirst.Where(d => (days & DayBit(d)) != 0).Select(d => d.ToString()[..3])),
    };

    public static readonly DayOfWeek[] MondayFirst =
    {
        DayOfWeek.Monday, DayOfWeek.Tuesday, DayOfWeek.Wednesday, DayOfWeek.Thursday,
        DayOfWeek.Friday, DayOfWeek.Saturday, DayOfWeek.Sunday,
    };
}
