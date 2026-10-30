using System;
using System.Linq;
using System.Threading;
using System.Windows;
using WizMusicSync.Core;

namespace WizMusicSync.App;

/// <summary>
/// Single-instance tray app: the window can be closed while sync and schedules keep running in the
/// tray; Exit (tray menu) really quits. Started with --tray (Start with Windows) it opens hidden.
/// </summary>
public partial class App : Application
{
    private const string InstanceName = "WizMusicSync.SingleInstance";
    private const string ShowSignalName = "WizMusicSync.Show";

    private Mutex? _singleInstance;
    private EventWaitHandle? _showSignal;
    private ScheduleRunner? _schedules;

    public static App Instance => (App)Current;
    public TrayIcon Tray { get; private set; } = null!;
    public MainWindow Window { get; private set; } = null!;

    protected override void OnStartup(StartupEventArgs e)
    {
        _singleInstance = new Mutex(true, InstanceName, out bool first);
        if (!first)
        {
            // Already running: bring that window forward instead of starting twice
            try { EventWaitHandle.OpenExisting(ShowSignalName).Set(); } catch (Exception) { }
            Shutdown();
            return;
        }
        _showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, ShowSignalName);
        new Thread(() =>
        {
            while (_showSignal.WaitOne())
            {
                try { Dispatcher.Invoke(ShowMainWindow); } catch (Exception) { return; }
            }
        }) { IsBackground = true, Name = "show-signal" }.Start();

        base.OnStartup(e);
        SettingsStore.Load();

        Window = new MainWindow();
        Tray = new TrayIcon();
        _schedules = new ScheduleRunner(
            () => SyncSession.IsRunning,
            () => SyncSession.StopCurrent(restore: false),
            (schedule, ok) => Dispatcher.BeginInvoke(() => OnScheduleFired(schedule, ok)));
        _schedules.Start();

        if (!e.Args.Contains("--tray")) ShowMainWindow();
    }

    public void ShowMainWindow()
    {
        Window.Show();
        if (Window.WindowState == WindowState.Minimized) Window.WindowState = WindowState.Normal;
        Window.Activate();
    }

    private void OnScheduleFired(Schedule schedule, bool ok)
    {
        if (!ok)
        {
            Tray.Notify($"Couldn't turn the bulb {(schedule.TurnOn ? "on" : "off")}",
                "The bulb didn't answer. Is this PC on the same network as the bulb?", warning: true);
        }
        Window.OnScheduleRan(schedule, ok);
    }

    /// <summary>Quits for real: stops sync (restoring the idle light), saves and removes the tray icon.</summary>
    public void ExitApp()
    {
        SyncSession.StopCurrent(restore: true);
        _schedules?.Dispose();
        SettingsStore.Flush();
        Tray.Dispose();
        _singleInstance?.ReleaseMutex();
        Shutdown();
    }
}
