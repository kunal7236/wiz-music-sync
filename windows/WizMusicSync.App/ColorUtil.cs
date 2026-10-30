using System;
using System.Windows.Media;
using WizMusicSync.Core;

namespace WizMusicSync.App;

public static class ColorUtil
{
    /// <summary>HSV (hue 0-360, saturation and value 0-1) to RGB.</summary>
    public static (byte R, byte G, byte B) HsvToRgb(double hue, double saturation, double value)
    {
        hue = ((hue % 360) + 360) % 360;
        double c = value * saturation;
        double x = c * (1 - Math.Abs(hue / 60 % 2 - 1));
        double m = value - c;
        (double r, double g, double b) = (int)(hue / 60) switch
        {
            0 => (c, x, 0.0),
            1 => (x, c, 0.0),
            2 => (0.0, c, x),
            3 => (0.0, x, c),
            4 => (x, 0.0, c),
            _ => (c, 0.0, x),
        };
        return (ToByte(r + m), ToByte(g + m), ToByte(b + m));
    }

    /// <summary>RGB to (hue 0-360, saturation 0-1).</summary>
    public static (double Hue, double Saturation) RgbToHueSaturation(int r, int g, int b)
    {
        double rf = r / 255.0, gf = g / 255.0, bf = b / 255.0;
        double max = Math.Max(rf, Math.Max(gf, bf)), min = Math.Min(rf, Math.Min(gf, bf));
        double delta = max - min;
        double hue = delta == 0 ? 0
            : max == rf ? 60 * (((gf - bf) / delta) % 6)
            : max == gf ? 60 * ((bf - rf) / delta + 2)
            : 60 * ((rf - gf) / delta + 4);
        if (hue < 0) hue += 360;
        return (hue, max == 0 ? 0 : delta / max);
    }

    /// <summary>Approximate on-screen colour of white light at <paramref name="kelvin"/> (Tanner Helland's fit).</summary>
    public static Color KelvinToColor(int kelvin)
    {
        double t = kelvin / 100.0;
        double r = t <= 66 ? 255 : 329.698727446 * Math.Pow(t - 60, -0.1332047592);
        double g = t <= 66 ? 99.4708025861 * Math.Log(t) - 161.1195681661 : 288.1221695283 * Math.Pow(t - 60, -0.0755148492);
        double b = t >= 66 ? 255 : t <= 19 ? 0 : 138.5177312231 * Math.Log(t - 10) - 305.0447927307;
        return Color.FromRgb(Clamp(r), Clamp(g), Clamp(b));
    }

    /// <summary>On-screen colour for a swatch of <paramref name="light"/>.</summary>
    public static Color Swatch(BulbLight light) => light switch
    {
        RgbLight c => Color.FromRgb((byte)c.R, (byte)c.G, (byte)c.B),
        WhiteLight w => KelvinToColor(w.Temp),
        _ => Colors.Gray,
    };

    /// <summary>Short label such as "#0000FF · 60%" or "4200 K white · 100%".</summary>
    public static string Describe(BulbLight light) => light switch
    {
        RgbLight c => $"#{c.R:X2}{c.G:X2}{c.B:X2} · {c.Dimming}%",
        WhiteLight w => $"{w.Temp} K white · {w.Dimming}%",
        _ => "",
    };

    private static byte ToByte(double unit) => (byte)Math.Round(Math.Clamp(unit, 0, 1) * 255);
    private static byte Clamp(double v) => (byte)Math.Clamp((int)v, 0, 255);
}
