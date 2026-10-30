namespace WizMusicSync.Core;

/// <summary>
/// Fires power schedules while the app is running (in the window or the tray). Checks once a second,
/// so it copes with clock changes and sleep: a schedule whose time passed while the PC was asleep for
/// more than <see cref="MissedAfter"/> is treated as missed (one-offs are retired, repeats move on),
/// matching the Android app's "don't fire late after a restart" rule.
/// </summary>
public sealed class ScheduleRunner : IDisposable
{
    public static readonly TimeSpan MissedAfter = TimeSpan.FromMinutes(2);

    private readonly Func<bool> _isSyncRunning;
    private readonly Action _stopSyncWithoutRestore;
    private readonly Action<Schedule, bool> _onFired;
    private readonly Dictionary<int, (Schedule Schedule, long? At)> _armed = new();
    private readonly HashSet<int> _firing = new();
    private Timer? _timer;

    /// <param name="onFired">Called after each schedule ran, with whether the bulb confirmed.</param>
    public ScheduleRunner(Func<bool> isSyncRunning, Action stopSyncWithoutRestore, Action<Schedule, bool> onFired)
    {
        _isSyncRunning = isSyncRunning;
        _stopSyncWithoutRestore = stopSyncWithoutRestore;
        _onFired = onFired;
    }

    public void Start() => _timer = new Timer(_ => Tick(), null, TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(1));

    private void Tick()
    {
        var now = DateTimeOffset.Now;
        long nowMs = now.ToUnixTimeMilliseconds();
        var schedules = SettingsStore.Current.Schedules;
        lock (_armed)
        {
            foreach (int gone in _armed.Keys.Where(id => schedules.All(s => s.Id != id)).ToList())
                _armed.Remove(gone);

            foreach (var schedule in schedules)
            {
                if (_firing.Contains(schedule.Id)) continue;
                // New or edited schedule: work out when it next runs
                if (!_armed.TryGetValue(schedule.Id, out var armed) || armed.Schedule != schedule)
                {
                    _armed[schedule.Id] = (schedule, schedule.NextTrigger(now, TimeZoneInfo.Local));
                    continue;
                }
                if (armed.At is long at && nowMs >= at)
                {
                    bool missed = nowMs - at > (long)MissedAfter.TotalMilliseconds;
                    _firing.Add(schedule.Id);
                    Task.Run(() => Fire(schedule, missed));
                }
            }
        }
    }

    private void Fire(Schedule schedule, bool missed)
    {
        try
        {
            if (!missed)
            {
                if (!schedule.TurnOn && _isSyncRunning())
                {
                    _stopSyncWithoutRestore();
                    Thread.Sleep(600); // let the last queued colours go out before switching off
                }
                bool ok = SwitchBulb(schedule.TurnOn);
                _onFired(schedule, ok);
            }
            AfterFired(schedule);
        }
        finally
        {
            lock (_armed)
            {
                _firing.Remove(schedule.Id);
                _armed.Remove(schedule.Id); // re-armed from the updated settings on the next tick
            }
        }
    }

    /// <summary>Sets the power; if the bulb doesn't answer and exactly one bulb is found elsewhere, switches to it.</summary>
    private static bool SwitchBulb(bool on)
    {
        string ip = SettingsStore.Current.BulbIp;
        if (!string.IsNullOrEmpty(ip) && WizBulb.SetPower(ip, on)) return true;

        var bulbs = WizBulb.Discover();
        if (bulbs.Count != 1) return false;
        string moved = bulbs[0].Ip;
        if (moved != ip) SettingsStore.Update(s => s with { BulbIp = moved });
        return WizBulb.SetPower(moved, on, timeoutMs: 4000);
    }

    /// <summary>Timers are removed, one-off times switched off; repeating ones simply re-arm.</summary>
    private static void AfterFired(Schedule schedule)
    {
        if (schedule.IsTimer) SettingsStore.DeleteSchedule(schedule.Id);
        else if (schedule.IsOneOff) SettingsStore.ReplaceSchedule(schedule with { Enabled = false });
    }

    public void Dispose() => _timer?.Dispose();
}
