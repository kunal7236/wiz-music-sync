using System.Globalization;

namespace WizMusicSync.Core;

/// <summary>
/// Port of the laptop script's analysis loop (tiers, bass drop, idle light), identical to the
/// Android app's LightEngine.kt. Feed it every captured chunk; it returns what the bulb should
/// show when a command is due.
/// </summary>
public sealed class LightEngine
{
    private static readonly string[] TierLabels = { "LOW", "MIDS", "DRIVE", "HIGH" };

    private readonly int _rate;
    private readonly Func<LightTheme> _theme;
    private readonly Func<double> _sensitivity;
    private readonly Func<bool> _idleOnPause;

    private readonly double[] _historyImpact = Filled(50, 100.0);
    private readonly double[] _historyBass = Filled(25, 10.0);
    private readonly double[] _historyRms = Filled(50, 100.0);
    private double _lastSend;
    private int _activeTierIdx;
    private double _lastDropTime;

    // Silence tracking
    private bool _isCurrentlyIdle;
    private double _silenceStartTime;

    /// <summary>Human-readable line equivalent to the script's console output.</summary>
    public string Status { get; private set; } = "Waiting for audio...";

    /// <param name="theme">Read on every chunk, so colour edits apply while syncing.</param>
    /// <param name="sensitivity">Multiplier on the music level before picking a tier; 1.0 is the original script.</param>
    /// <param name="idleOnPause">
    /// True (the original script) switches to the theme's idle light when the music pauses; false
    /// freezes the bulb on the colour it was showing until the music resumes.
    /// </param>
    public LightEngine(
        int rate,
        double startTime,
        Func<LightTheme>? theme = null,
        Func<double>? sensitivity = null,
        Func<bool>? idleOnPause = null)
    {
        _rate = rate;
        _silenceStartTime = startTime;
        _theme = theme ?? (() => LightTheme.Default);
        _sensitivity = sensitivity ?? (() => 1.0);
        _idleOnPause = idleOnPause ?? (() => true);
    }

    /// <param name="interleaved">PCM 16-bit samples, <paramref name="frames"/> x <paramref name="channels"/> interleaved.</param>
    /// <param name="now">Monotonic time in seconds.</param>
    public BulbLight? Process(short[] interleaved, int frames, int channels, double now)
    {
        var samples = new double[frames];
        for (int i = 0; i < frames; i++)
        {
            double sum = 0.0;
            for (int c = 0; c < channels; c++) sum += interleaved[i * channels + c];
            samples[i] = sum / channels;
        }

        double sumSquares = 0.0;
        foreach (double x in samples) sumSquares += x * x;
        double rms = Math.Sqrt(sumSquares / frames);

        // ----------------- SILENCE / IDLE DETECTION -----------------
        // If audio is paused or muted (RMS < 35 for 1.2 continuous seconds)
        if (rms < 35.0)
        {
            if (!_idleOnPause())
            {
                // Keep mode: send nothing during silence, so the bulb holds the colour it had when the
                // music stopped (analysing the silence would first step it down to the quiet colour)
                if (now - _silenceStartTime > 1.2 && !_isCurrentlyIdle)
                {
                    _isCurrentlyIdle = true;
                    Status = "[PAUSED] Keeping the last colour";
                }
                return null;
            }
            if (now - _silenceStartTime > 1.2)
            {
                if (!_isCurrentlyIdle)
                {
                    _isCurrentlyIdle = true;
                    Status = "[IDLE / NO MUSIC]";
                    return _theme().Idle;
                }
                return null;
            }
        }
        else
        {
            _silenceStartTime = now;
            if (_isCurrentlyIdle)
            {
                _isCurrentlyIdle = false;
                Status = "[MUSIC RESUMED] Resuming reactive light show...";
            }
        }

        // ----------------- AUDIO ANALYSIS & TIERS -----------------
        double bassEnergy = BassEnergy(samples);

        Push(_historyBass, Math.Max(10.0, bassEnergy));
        double avgBass = _historyBass.Average();

        double waveformImpact = (bassEnergy * 2.5) + rms;

        Push(_historyImpact, Math.Max(40.0, waveformImpact));
        double avgImpact = _historyImpact.Average();
        double maxImpact = Math.Max(_historyImpact.Max(), avgImpact * 2.2);

        Push(_historyRms, rms);
        double avgRms = _historyRms.Average();

        double normLevel = Math.Clamp(
            (waveformImpact - (avgImpact * 0.45)) / (maxImpact - (avgImpact * 0.45) + 1e-6), 0.0, 1.0);
        double level = Math.Clamp(normLevel * _sensitivity(), 0.0, 1.0);

        // The script compared rms against avg_impact, which is dominated by bass FFT magnitude
        // (hundreds of times larger), so the flash never fired. Compare loudness with loudness.
        bool isBassDrop = (bassEnergy > avgBass * 1.55) && (rms > avgRms * 0.5);

        var current = _theme();
        BulbLight light;
        string tierName;
        if (isBassDrop && (now - _lastDropTime > 0.14))
        {
            _lastDropTime = now;
            light = current.Drop;
            tierName = "[BASS DROP FLASH]";
        }
        else
        {
            int targetTier = level < 0.25 ? 0 : level < 0.50 ? 1 : level < 0.75 ? 2 : 3;

            _activeTierIdx = targetTier >= _activeTierIdx ? targetTier : Math.Max(0, _activeTierIdx - 1);

            light = current.Tiers[_activeTierIdx];
            tierName = $"[{TierLabels[_activeTierIdx]}]";
        }

        if (now - _lastSend >= 0.075)
        {
            _lastSend = now;
            string bar = new('#', (int)(level * 20));
            Status = string.Format(CultureInfo.InvariantCulture,
                "{0,-19} Level: {1,5:F1}% | Dim: {2,3}%\n[{3,-20}]", tierName, level * 100, light.Dimming, bar);
            return light;
        }
        return null;
    }

    /// <summary>Mean |rfft| over the 35-160 Hz bins, computed directly for just those bins.</summary>
    private double BassEnergy(double[] x)
    {
        int n = x.Length;
        double sum = 0.0;
        int count = 0;
        for (int k = 0; k <= n / 2; k++)
        {
            double freq = k * ((double)_rate / n);
            if (freq < 35.0 || freq > 160.0) continue;
            double re = 0.0, im = 0.0;
            double w = 2.0 * Math.PI * k / n;
            for (int t = 0; t < n; t++)
            {
                re += x[t] * Math.Cos(w * t);
                im -= x[t] * Math.Sin(w * t);
            }
            sum += double.Hypot(re, im);
            count++;
        }
        return count > 0 ? sum / count : 0.0;
    }

    /// <summary>np.roll(a, -1); a[-1] = value</summary>
    private static void Push(double[] a, double value)
    {
        Array.Copy(a, 1, a, 0, a.Length - 1);
        a[^1] = value;
    }

    private static double[] Filled(int length, double value)
    {
        var a = new double[length];
        Array.Fill(a, value);
        return a;
    }
}
