using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using WizMusicSync.Core;

namespace WizMusicSync.App;

/// <summary>
/// One music-sync run: captures whatever Windows is playing (WASAPI loopback of the default output,
/// following device switches like the laptop script), runs it through <see cref="LightEngine"/> and
/// sends the bulb commands over UDP, delayed by the speaker-delay setting.
/// </summary>
public sealed class SyncSession
{
    private const int Chunk = 1024;
    /// <summary>Loopback delivers nothing while nothing plays; after this gap we feed silence so pauses are noticed.</summary>
    private const double SilenceAfterSeconds = 0.25;

    private static readonly object Gate = new();
    private static SyncSession? _current;

    public static bool IsRunning => Volatile.Read(ref _current) != null;
    public static string Status => Volatile.Read(ref _current)?._status ?? "Stopped";

    /// <summary>Starts syncing to the bulb at <paramref name="ip"/> (no-op if already running).</summary>
    public static void Start(string ip)
    {
        lock (Gate)
        {
            if (_current != null) return;
            _current = new SyncSession(ip);
            _current.Begin();
        }
    }

    /// <param name="restore">True puts the bulb on the "Paused or silent" light; false leaves it as it is.</param>
    public static void StopCurrent(bool restore)
    {
        SyncSession? session;
        lock (Gate)
        {
            session = _current;
            _current = null;
        }
        session?.End(restore);
    }

    private sealed record Samples(short[] Data, int Channels, int Rate);

    private readonly string _ip;
    private readonly CancellationTokenSource _cancel = new();
    private readonly BlockingCollection<Samples> _chunks = new(boundedCapacity: 64);
    private readonly UdpClient _udp = new();
    private readonly object _sendGate = new();
    private readonly object _bufferGate = new();
    private Thread? _deviceThread;
    private Thread? _processThread;
    private volatile string _status;

    // Current capture (owned by the device thread)
    private WasapiLoopbackCapture? _capture;
    private string? _deviceId;
    private volatile bool _captureStopped;
    private short[] _pending = Array.Empty<short>();
    private int _pendingCount;
    private int _channels = 2;
    private int _rate = 48000;

    private SyncSession(string ip)
    {
        _ip = ip;
        _status = $"Connecting to WiZ bulb at {ip}...";
    }

    private void Begin()
    {
        _deviceThread = new Thread(DeviceLoop) { IsBackground = true, Name = "audio-device" };
        _processThread = new Thread(ProcessLoop) { IsBackground = true, Name = "light-engine" };
        _deviceThread.Start();
        _processThread.Start();
    }

    private void End(bool restore)
    {
        _cancel.Cancel();
        _deviceThread?.Join(1500);
        _processThread?.Join(1500);
        if (restore) Send(WizBulb.Payload(SettingsStore.Current.Theme.Idle));
        lock (_sendGate) _udp.Dispose();
        _status = "Stopped";
    }

    // ----------------- CAPTURE -----------------

    /// <summary>Keeps a loopback capture open on the current default output, reopening it when that changes.</summary>
    private void DeviceLoop()
    {
        using var enumerator = new MMDeviceEnumerator();
        while (!_cancel.IsCancellationRequested)
        {
            try
            {
                var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                if (_capture == null || _captureStopped || device.ID != _deviceId)
                {
                    StopCapture();
                    StartCapture(device);
                }
            }
            catch (Exception)
            {
                StopCapture();
                _status = "No audio output device found. Waiting for one...";
            }
            _cancel.Token.WaitHandle.WaitOne(1000);
        }
        StopCapture();
    }

    private void StartCapture(MMDevice device)
    {
        var capture = new WasapiLoopbackCapture(device);
        var format = capture.WaveFormat;
        int channels = format.Channels;
        int bits = format.BitsPerSample;
        bool isFloat = format.Encoding == WaveFormatEncoding.IeeeFloat ||
                       (format.Encoding == WaveFormatEncoding.Extensible && bits == 32);
        lock (_bufferGate)
        {
            _channels = channels;
            _rate = format.SampleRate;
            _pending = new short[Chunk * channels];
            _pendingCount = 0;
        }
        capture.DataAvailable += (_, e) => OnData(e.Buffer, e.BytesRecorded, isFloat, bits);
        capture.RecordingStopped += (_, _) => _captureStopped = true; // device removed: reopened next second
        _captureStopped = false;
        capture.StartRecording();
        _capture = capture;
        _deviceId = device.ID;
    }

    private void StopCapture()
    {
        var capture = _capture;
        _capture = null;
        _deviceId = null;
        if (capture == null) return;
        try { capture.StopRecording(); } catch (Exception) { }
        capture.Dispose();
    }

    /// <summary>Converts captured audio to 16-bit samples and queues it in 1024-frame chunks.</summary>
    private void OnData(byte[] buffer, int bytes, bool isFloat, int bits)
    {
        int bytesPerSample = bits / 8;
        int count = bytes / bytesPerSample;
        lock (_bufferGate)
        {
            for (int i = 0; i < count; i++)
            {
                short sample;
                if (isFloat && bits == 32)
                {
                    float f = BitConverter.ToSingle(buffer, i * 4);
                    sample = (short)Math.Clamp(f * 32767f, short.MinValue, short.MaxValue);
                }
                else if (bits == 16)
                {
                    sample = BitConverter.ToInt16(buffer, i * 2);
                }
                else
                {
                    return; // unusual format (24-bit integer mix): not supported
                }
                _pending[_pendingCount++] = sample;
                if (_pendingCount == _pending.Length)
                {
                    _chunks.TryAdd(new Samples((short[])_pending.Clone(), _channels, _rate));
                    _pendingCount = 0;
                }
            }
        }
    }

    // ----------------- ANALYSIS & SENDING -----------------

    private void ProcessLoop()
    {
        var clock = Stopwatch.StartNew();
        LightEngine? engine = null;
        int engineRate = 0;
        int lastChannels = 2;
        double lastData = 0, lastSilence = 0;
        var token = _cancel.Token;
        while (!token.IsCancellationRequested)
        {
            Samples? chunk;
            try
            {
                if (!_chunks.TryTake(out chunk, 20, token)) chunk = null;
            }
            catch (OperationCanceledException)
            {
                break;
            }

            double now = clock.Elapsed.TotalSeconds;
            if (chunk != null)
            {
                lastData = now;
                lastChannels = chunk.Channels;
            }
            else
            {
                // Feed silence at the chunk rate once audio has been quiet for a moment
                if (engine == null || now - lastData < SilenceAfterSeconds) continue;
                double chunkSeconds = (double)Chunk / engineRate;
                if (now - lastSilence < chunkSeconds) continue;
                lastSilence = now;
                chunk = new Samples(new short[Chunk * lastChannels], lastChannels, engineRate);
            }

            if (engine == null || chunk.Rate != engineRate)
            {
                engineRate = chunk.Rate;
                engine = new LightEngine(engineRate, now,
                    () => SettingsStore.Current.Theme,
                    () => SettingsStore.Current.Gain,
                    () => !SettingsStore.Current.KeepColourOnPause);
            }

            var light = engine.Process(chunk.Data, Chunk, chunk.Channels, now);
            _status = engine.Status;
            if (light == null) continue;

            string payload = WizBulb.Payload(light);
            int delay = SettingsStore.Current.DelayMs;
            if (delay <= 0) Send(payload);
            else Task.Delay(delay, token).ContinueWith(t => { if (!t.IsCanceled) Send(payload); }, TaskScheduler.Default);
        }
    }

    private void Send(string payload)
    {
        var bytes = Encoding.UTF8.GetBytes(payload);
        lock (_sendGate)
        {
            try { _udp.Send(bytes, bytes.Length, _ip, WizBulb.Port); }
            catch (Exception) { }
        }
    }
}
