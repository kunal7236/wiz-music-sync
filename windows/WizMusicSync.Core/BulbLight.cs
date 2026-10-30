using System.Text.Json.Serialization;

namespace WizMusicSync.Core;

/// <summary>One thing the bulb can show: an RGB colour or a white temperature, each with a brightness (WiZ "dimming", 10-100).</summary>
[JsonPolymorphic(TypeDiscriminatorPropertyName = "type")]
[JsonDerivedType(typeof(RgbLight), "rgb")]
[JsonDerivedType(typeof(WhiteLight), "white")]
public abstract record BulbLight(int Dimming);

public sealed record RgbLight(int R, int G, int B, int Dimming) : BulbLight(Dimming);

/// <summary>The bulb's dedicated white LEDs, colour temperature in kelvin.</summary>
public sealed record WhiteLight(int Temp, int Dimming) : BulbLight(Dimming);

/// <summary>What the bulb shows for each state the engine detects. User-editable; <see cref="Default"/> is the original script.</summary>
/// <param name="Tiers">Quiet, mids, drive, high — in rising order.</param>
public sealed record LightTheme(IReadOnlyList<BulbLight> Tiers, BulbLight Drop, BulbLight Idle)
{
    public static LightTheme Default { get; } = new(
        new BulbLight[]
        {
            new RgbLight(0, 0, 255, 60),    // Low background wave
            new RgbLight(0, 255, 0, 70),    // Vocals / Mids
            new RgbLight(255, 120, 0, 85),  // Synths / Drive
            new RgbLight(255, 0, 160, 95),  // High intensity
        },
        new RgbLight(255, 0, 0, 100),       // Peak bass kick
        new WhiteLight(4200, 100));         // Paused / silent

    public LightTheme WithTier(int index, BulbLight light)
    {
        var tiers = Tiers.ToArray();
        tiers[index] = light;
        return this with { Tiers = tiers };
    }
}
