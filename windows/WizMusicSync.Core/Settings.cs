using System.Text.Json;
using System.Text.Json.Serialization;

namespace WizMusicSync.Core;

/// <summary>Every user setting. Immutable: change it with <see cref="SettingsStore.Update"/>.</summary>
public sealed record AppSettings
{
    /// <summary>Empty until a bulb is found: on first launch the app scans for one.</summary>
    public string BulbIp { get; init; } = "";
    /// <summary>Holds every bulb command back by this much so the light lines up with a lagging speaker.</summary>
    public int DelayMs { get; init; }
    /// <summary>0-100; 50 is the original script.</summary>
    public int Sensitivity { get; init; } = 50;
    /// <summary>While syncing, a music pause keeps the current colour instead of switching to the idle light.</summary>
    public bool KeepColourOnPause { get; init; } = true;
    public bool FixedMode { get; init; }
    public BulbLight FixedLight { get; init; } = new WhiteLight(4200, 100);
    public LightTheme Theme { get; init; } = LightTheme.Default;
    public IReadOnlyList<Schedule> Schedules { get; init; } = Array.Empty<Schedule>();
    public int NextScheduleId { get; init; } = 1;

    /// <summary>Level multiplier for the engine: 0.5x at 0, 1x at 50, 2x at 100.</summary>
    [JsonIgnore]
    public double Gain => Math.Pow(2.0, (Sensitivity - 50) / 50.0);
}

/// <summary>
/// Holds the current <see cref="AppSettings"/> and persists it as JSON. Read anywhere via
/// <see cref="Current"/>; the sync engine reads it live, so edits apply while syncing.
/// </summary>
public static class SettingsStore
{
    private static readonly object Gate = new();
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };
    private static readonly Timer SaveTimer = new(_ => SaveNow());
    private static AppSettings _current = new();

    public static string FilePath { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "WizMusicSync", "settings.json");

    public static AppSettings Current => Volatile.Read(ref _current);

    /// <summary>Raised (on any thread) after every change.</summary>
    public static event Action? Changed;

    public static void Load()
    {
        AppSettings loaded;
        try
        {
            loaded = File.Exists(FilePath)
                ? JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath), Json) ?? new AppSettings()
                : new AppSettings();
        }
        catch (Exception)
        {
            loaded = new AppSettings();
        }
        Volatile.Write(ref _current, loaded);
    }

    public static AppSettings Update(Func<AppSettings, AppSettings> change)
    {
        AppSettings next;
        lock (Gate)
        {
            next = change(_current);
            Volatile.Write(ref _current, next);
        }
        // Coalesce rapid edits (slider drags) into one write
        SaveTimer.Change(300, Timeout.Infinite);
        Changed?.Invoke();
        return next;
    }

    /// <summary>Back to exactly how the app was on first install, schedules included.</summary>
    public static void ResetAll() => Update(_ => new AppSettings());

    /// <summary>Writes pending changes now (call on exit).</summary>
    public static void Flush() => SaveNow();

    private static void SaveNow()
    {
        try
        {
            var settings = Current;
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
            var temp = FilePath + ".tmp";
            File.WriteAllText(temp, JsonSerializer.Serialize(settings, Json));
            File.Move(temp, FilePath, overwrite: true);
        }
        catch (Exception)
        {
            // Settings stay in memory; the next change retries the write
        }
    }

    // ----------------- SCHEDULES -----------------

    /// <summary>Saves a new schedule, assigning its id.</summary>
    public static Schedule AddSchedule(Schedule schedule)
    {
        Schedule saved = schedule;
        Update(s =>
        {
            saved = schedule with { Id = s.NextScheduleId };
            return s with { Schedules = s.Schedules.Append(saved).ToArray(), NextScheduleId = s.NextScheduleId + 1 };
        });
        return saved;
    }

    public static void ReplaceSchedule(Schedule schedule) =>
        Update(s => s with { Schedules = s.Schedules.Select(x => x.Id == schedule.Id ? schedule : x).ToArray() });

    public static void DeleteSchedule(int id) =>
        Update(s => s with { Schedules = s.Schedules.Where(x => x.Id != id).ToArray() });
}
