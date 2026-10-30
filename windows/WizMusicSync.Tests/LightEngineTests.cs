using System.Text.Json;
using WizMusicSync.Core;
using Xunit;

namespace WizMusicSync.Tests;

public class LightEngineTests
{
    private const int Rate = 48000;
    private const int Chunk = 1024;
    private const double ChunkSeconds = (double)Chunk / Rate;

    /// <summary>3 s of music (60 Hz kick every 0.5 s over a 440 Hz tone), 3 s of silence, 2 s of music.</summary>
    private static short SampleAt(double t)
    {
        if (t is >= 3.0 and <= 6.0) return 0;
        double kick = Math.Sin(2 * Math.PI * 60 * t) * Math.Exp(-(t % 0.5) * 18) * 12000;
        double tone = Math.Sin(2 * Math.PI * 440 * t) * 4000;
        return (short)(int)(kick + tone);
    }

    private static List<(double Time, BulbLight Light)> Run(bool idleOnPause)
    {
        var engine = new LightEngine(Rate, 0.0, idleOnPause: () => idleOnPause);
        var sent = new List<(double, BulbLight)>();
        int chunks = (int)(8.0 / ChunkSeconds);
        for (int i = 0; i < chunks; i++)
        {
            var buf = new short[Chunk * 2];
            for (int j = 0; j < buf.Length; j++) buf[j] = SampleAt((double)(i * Chunk + j / 2) / Rate);
            double now = (i + 1) * ChunkSeconds;
            if (engine.Process(buf, Chunk, 2, now) is { } light) sent.Add((now, light));
        }
        return sent;
    }

    private static List<BulbLight> During(List<(double Time, BulbLight Light)> sent, double from, double to) =>
        sent.Where(s => s.Time > from && s.Time <= to).Select(s => s.Light).ToList();

    [Fact]
    public void KeepModeHoldsLastColourThroughPause()
    {
        var sent = Run(idleOnPause: false);
        // Nothing at all is sent while the music is paused, so the bulb keeps its colour
        Assert.Empty(During(sent, 3.0 + ChunkSeconds, 6.0));
        Assert.DoesNotContain(sent, s => s.Light == LightTheme.Default.Idle);
        // ...and colours resume with the music
        Assert.NotEmpty(During(sent, 6.0, 8.0));
    }

    [Fact]
    public void IdleModeSwitchesToIdleLightOnce()
    {
        var sent = Run(idleOnPause: true);
        Assert.Equal(1, During(sent, 3.0, 6.0).Count(l => l == LightTheme.Default.Idle));
        Assert.NotEmpty(During(sent, 6.0, 8.0));
    }

    [Fact]
    public void BassKicksFlashRed()
    {
        var sent = Run(idleOnPause: true);
        Assert.Contains(sent, s => s.Light == LightTheme.Default.Drop);
    }
}

public class ProtocolAndSettingsTests
{
    [Fact]
    public void ColourPayloadMatchesWizApi()
    {
        using var doc = JsonDocument.Parse(WizBulb.Payload(new RgbLight(0, 0, 255, 60)));
        var root = doc.RootElement;
        Assert.Equal("setPilot", root.GetProperty("method").GetString());
        var p = root.GetProperty("params");
        Assert.Equal(255, p.GetProperty("b").GetInt32());
        Assert.Equal(60, p.GetProperty("dimming").GetInt32());
        Assert.True(p.GetProperty("state").GetBoolean());
    }

    [Fact]
    public void WhitePayloadUsesTemperatureAndClampsBrightness()
    {
        using var doc = JsonDocument.Parse(WizBulb.Payload(new WhiteLight(2700, 5)));
        var p = doc.RootElement.GetProperty("params");
        Assert.Equal(2700, p.GetProperty("temp").GetInt32());
        Assert.Equal(10, p.GetProperty("dimming").GetInt32());
        Assert.False(p.TryGetProperty("r", out _));
    }

    [Fact]
    public void SettingsRoundTripThroughJson()
    {
        var settings = new AppSettings
        {
            BulbIp = "192.168.0.103",
            DelayMs = 180,
            Sensitivity = 70,
            KeepColourOnPause = false,
            FixedMode = true,
            FixedLight = new RgbLight(10, 20, 30, 40),
            Theme = LightTheme.Default.WithTier(0, new WhiteLight(2700, 50)),
            Schedules = new[] { new Schedule(3, true, Hour: 7, Days: Schedule.Weekdays), new Schedule(4, false, AtMillis: 123) },
            NextScheduleId = 5,
        };
        var back = JsonSerializer.Deserialize<AppSettings>(JsonSerializer.Serialize(settings))!;

        Assert.Equal(settings.BulbIp, back.BulbIp);
        Assert.Equal(settings.DelayMs, back.DelayMs);
        Assert.Equal(settings.Sensitivity, back.Sensitivity);
        Assert.False(back.KeepColourOnPause);
        Assert.True(back.FixedMode);
        Assert.Equal(settings.FixedLight, back.FixedLight);
        Assert.Equal(settings.Theme.Tiers, back.Theme.Tiers);
        Assert.Equal(settings.Theme.Drop, back.Theme.Drop);
        Assert.Equal(settings.Theme.Idle, back.Theme.Idle);
        Assert.Equal(settings.Schedules, back.Schedules);
        Assert.Equal(5, back.NextScheduleId);
    }
}
