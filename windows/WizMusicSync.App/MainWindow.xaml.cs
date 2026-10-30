using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;
using WizMusicSync.Core;

namespace WizMusicSync.App;

public partial class MainWindow : Window
{
    /// <summary>Rows 0-3 are the theme's tiers, 4 is the bass-drop flash, 5 is the idle light.</summary>
    private static readonly string[] RowNames =
    {
        "Quiet / background", "Vocals / mids", "Synths / drive", "High intensity", "Sudden bass kick", "Paused or silent",
    };
    private const int DropRow = 4;
    private const int IdleRow = 5;
    /// <summary>Minimum gap between bulb updates while dragging the fixed-colour picker.</summary>
    private static readonly TimeSpan FixedPushInterval = TimeSpan.FromMilliseconds(100);

    private string? _message;
    private bool _scanning;
    /// <summary>True while controls are being filled from settings, so their events don't write back.</summary>
    private bool _binding;
    /// <summary>IP already auto-scanned for since the window opened, so a bulb that's off isn't searched for repeatedly.</summary>
    private string? _autoScannedIp;
    private bool _trayHintShown;
    private IReadOnlyList<Schedule>? _shownSchedules;
    private LightTheme? _shownTheme;

    private readonly DispatcherTimer _ui = new() { Interval = TimeSpan.FromMilliseconds(200) };
    private readonly DispatcherTimer _fixedPush = new();
    private BulbLight? _pendingFixed;
    private DateTime _lastFixedPush;

    public MainWindow()
    {
        InitializeComponent();
        Icon = AppIcon.ImageSource;
        // Never open taller than the screen above the taskbar (high display scaling shrinks it)
        Height = Math.Min(Height, SystemParameters.WorkArea.Height - 40);

        BulbIp.TextChanged += (_, _) =>
        {
            if (!_binding) SettingsStore.Update(s => s with { BulbIp = CurrentIp });
        };
        Scan.Click += (_, _) => ScanForBulbs(autoReason: null, then: null);
        PowerOn.Click += (_, _) => SetPower(true);
        PowerOff.Click += (_, _) => SetPower(false);

        ModeSync.Checked += (_, _) => { if (!_binding) SwitchMode(fixedMode: false); };
        ModeFixed.Checked += (_, _) => { if (!_binding) SwitchMode(fixedMode: true); };
        FixedPicker.Changed += light =>
        {
            SettingsStore.Update(s => s with { FixedLight = light });
            QueueFixed(light);
        };
        _fixedPush.Tick += (_, _) => PushFixed();

        Delay.ValueChanged += (_, e) =>
        {
            int ms = (int)e.NewValue;
            DelayLabel.Text = $"Speaker delay: {ms} ms";
            if (!_binding) SettingsStore.Update(s => s with { DelayMs = ms });
        };
        Sensitivity.ValueChanged += (_, e) =>
        {
            int value = (int)e.NewValue;
            SensitivityLabel.Text = $"Sensitivity: {SensitivityWord(value)}";
            if (!_binding) SettingsStore.Update(s => s with { Sensitivity = value });
        };
        KeepColour.Checked += (_, _) => { if (!_binding) SettingsStore.Update(s => s with { KeepColourOnPause = true }); };
        KeepColour.Unchecked += (_, _) => { if (!_binding) SettingsStore.Update(s => s with { KeepColourOnPause = false }); };

        ResetColors.Click += (_, _) =>
        {
            if (Confirm("Reset all light colours to the defaults?"))
                SettingsStore.Update(s => s with { Theme = LightTheme.Default });
        };
        ToggleSyncButton.Click += (_, _) => ToggleSync();
        AddSchedule.Click += (_, _) => OnAddSchedule();

        StartWithWindows.Checked += (_, _) => { if (!_binding) StartupRegistration.Set(true); };
        StartWithWindows.Unchecked += (_, _) => { if (!_binding) StartupRegistration.Set(false); };
        ResetAll.Click += (_, _) => OnResetAll();

        // Settings change from other threads too (schedules firing, auto-scan in the background)
        SettingsStore.Changed += () => Dispatcher.BeginInvoke(RefreshLists);
        _ui.Tick += (_, _) => UpdateUi();

        IsVisibleChanged += (_, _) =>
        {
            if (IsVisible)
            {
                _ui.Start();
                RefreshPowerState();
            }
            else
            {
                _ui.Stop();
            }
        };
        Bind();
    }

    private string CurrentIp => BulbIp.Text.Trim();

    /// <summary>Fills every control from settings (on start and after Reset all).</summary>
    private void Bind()
    {
        var s = SettingsStore.Current;
        _binding = true;
        BulbIp.Text = s.BulbIp;
        (s.FixedMode ? ModeFixed : ModeSync).IsChecked = true;
        FixedPicker.SetLight(s.FixedLight);
        Delay.Value = s.DelayMs;
        DelayLabel.Text = $"Speaker delay: {s.DelayMs} ms";
        Sensitivity.Value = s.Sensitivity;
        SensitivityLabel.Text = $"Sensitivity: {SensitivityWord(s.Sensitivity)}";
        KeepColour.IsChecked = s.KeepColourOnPause;
        StartWithWindows.IsChecked = StartupRegistration.IsEnabled;
        _binding = false;
        ShowSections();
        RefreshLists();
    }

    /// <summary>Closing the window keeps the app in the tray; Exit in the tray menu quits.</summary>
    protected override void OnClosing(CancelEventArgs e)
    {
        e.Cancel = true;
        Hide();
        if (!_trayHintShown)
        {
            _trayHintShown = true;
            App.Instance.Tray.Notify("WiZ Music Sync is still running",
                "Sync and schedules keep working. Use the tray icon to open it again or exit.");
        }
    }

    private void UpdateUi()
    {
        bool running = SyncSession.IsRunning;
        ToggleSyncButton.Content = running ? "Stop sync" : "Start sync";
        BulbIp.IsEnabled = !running;
        Scan.IsEnabled = !running && !_scanning;
        StatusText.Text = running ? SyncSession.Status
            : _message ?? (SettingsStore.Current.FixedMode ? $"Fixed colour: {ColorUtil.Describe(SettingsStore.Current.FixedLight)}" : "");
    }

    // ----------------- MODES -----------------

    private void ShowSections()
    {
        bool fixedMode = SettingsStore.Current.FixedMode;
        FixedPanel.Visibility = fixedMode ? Visibility.Visible : Visibility.Collapsed;
        SyncPanel.Visibility = fixedMode ? Visibility.Collapsed : Visibility.Visible;
    }

    private void SwitchMode(bool fixedMode)
    {
        _message = null;
        SettingsStore.Update(s => s with { FixedMode = fixedMode });
        ShowSections();
        if (!fixedMode) return;
        bool wasSyncing = SyncSession.IsRunning;
        // Stop capturing without the usual "back to the paused light" so the fixed colour takes over cleanly,
        // giving the session a moment to drop its queued colours first
        if (wasSyncing) Task.Run(() => SyncSession.StopCurrent(restore: false));
        QueueFixed(SettingsStore.Current.FixedLight, wasSyncing ? TimeSpan.FromMilliseconds(400) : TimeSpan.Zero);
    }

    private void QueueFixed(BulbLight light, TimeSpan? delay = null)
    {
        _pendingFixed = light;
        if (_fixedPush.IsEnabled) return;
        var wait = delay ?? FixedPushInterval - (DateTime.UtcNow - _lastFixedPush);
        if (wait <= TimeSpan.Zero)
        {
            PushFixed();
            return;
        }
        _fixedPush.Interval = wait;
        _fixedPush.Start();
    }

    private void PushFixed()
    {
        _fixedPush.Stop();
        if (_pendingFixed is not { } light) return;
        _pendingFixed = null;
        _lastFixedPush = DateTime.UtcNow;
        SendToBulb(light);
    }

    private void SendToBulb(BulbLight light)
    {
        string ip = CurrentIp;
        if (ip.Length == 0) return;
        string payload = WizBulb.Payload(light);
        Task.Run(() => WizBulb.SendOnce(ip, payload));
    }

    // ----------------- MUSIC SYNC -----------------

    public void ToggleSync()
    {
        _message = null;
        if (SyncSession.IsRunning)
        {
            Task.Run(() => SyncSession.StopCurrent(restore: true));
            return;
        }
        if (SettingsStore.Current.FixedMode)
        {
            App.Instance.ShowMainWindow();
            _message = "Switch to Music sync to start syncing.";
            return;
        }
        StartSync(bulbChecked: false);
    }

    /// <param name="bulbChecked">True once the bulb answered (or was just found by an auto-scan).</param>
    private void StartSync(bool bulbChecked)
    {
        string ip = CurrentIp;
        if (ip.Length == 0)
        {
            ScanForBulbs("No bulb set yet.", _ => StartSync(bulbChecked: true));
            return;
        }
        if (!bulbChecked)
        {
            // Make sure the light show has a bulb to talk to before starting
            _message = "Checking the bulb...";
            Task.Run(() =>
            {
                bool reachable = WizBulb.IsOn(ip, timeoutMs: 1000) != null;
                Dispatcher.BeginInvoke(() =>
                {
                    if (reachable)
                    {
                        _message = null;
                        StartSync(bulbChecked: true);
                    }
                    else
                    {
                        ScanForBulbs($"The bulb at {ip} didn't answer.", _ => StartSync(bulbChecked: true));
                    }
                });
            });
            return;
        }
        SyncSession.Start(ip);
    }

    // ----------------- POWER -----------------

    /// <param name="retry">False on the second attempt after an auto-scan, so a failure never scans again in a loop.</param>
    public void SetPower(bool on, bool retry = true)
    {
        string ip = CurrentIp;
        if (ip.Length == 0)
        {
            ScanForBulbs("No bulb set yet.", _ => SetPower(on, retry: false));
            return;
        }
        // Off ends the light show too, so the next beat doesn't switch the bulb back on
        bool stopSync = !on && SyncSession.IsRunning;
        _message = on ? "Turning the bulb on..." : "Turning the bulb off...";
        Task.Run(() =>
        {
            if (stopSync) SyncSession.StopCurrent(restore: false);
            bool ok = WizBulb.SetPower(ip, on, timeoutMs: 4000);
            Dispatcher.BeginInvoke(() =>
            {
                if (ok)
                {
                    _message = null;
                    ShowPowerState(on);
                }
                else if (retry)
                {
                    ScanForBulbs($"The bulb at {ip} didn't answer.", _ => SetPower(on, retry: false));
                }
                else
                {
                    _message = "The bulb didn't answer. Check it's switched on and this PC is on its network.";
                }
            });
        });
    }

    private void RefreshPowerState()
    {
        string ip = CurrentIp;
        if (ip.Length == 0)
        {
            // First run: look for the bulb straight away
            if (_autoScannedIp == null)
            {
                _autoScannedIp = "";
                ScanForBulbs("No bulb set yet.", _ => RefreshPowerState());
            }
            return;
        }
        Task.Run(() =>
        {
            bool? on = WizBulb.IsOn(ip);
            Dispatcher.BeginInvoke(() =>
            {
                ShowPowerState(on);
                // Search once per IP per visit: the bulb may simply be switched off at the wall
                if (on == null && _autoScannedIp != ip && !SyncSession.IsRunning)
                {
                    _autoScannedIp = ip;
                    ScanForBulbs($"The bulb at {ip} didn't answer.", _ => RefreshPowerState());
                }
            });
        });
    }

    private void ShowPowerState(bool? on) =>
        PowerState.Text = on switch { true => "Bulb is on", false => "Bulb is off", null => "Bulb not reachable" };

    // ----------------- BULB SCAN -----------------

    /// <summary>
    /// Scans the network. A manual scan (<paramref name="autoReason"/> null) always shows the results.
    /// An auto-scan (the saved bulb didn't answer) switches to the bulb if exactly one answers and then
    /// runs <paramref name="then"/>; several show the pick list (then runs after a pick); none keeps the IP.
    /// </summary>
    private void ScanForBulbs(string? autoReason, Action<string>? then)
    {
        if (_scanning) return;
        _scanning = true;
        Scan.Content = "Scanning...";
        _message = autoReason == null ? "Looking for WiZ bulbs on this network..." : $"{autoReason} Looking for it on this network...";
        Task.Run(() =>
        {
            var bulbs = WizBulb.Discover();
            Dispatcher.BeginInvoke(() =>
            {
                _scanning = false;
                Scan.Content = "Scan";
                string old = CurrentIp;
                if (autoReason == null)
                {
                    _message = null;
                    ShowScanResults(bulbs, then);
                }
                else if (bulbs.Count == 0)
                {
                    _message = $"{autoReason} No WiZ bulb answered the scan either. Check it's switched on and this PC is on its network.";
                }
                else if (bulbs.Count == 1)
                {
                    string ip = bulbs[0].Ip;
                    if (ip != old)
                    {
                        _autoScannedIp = ip;
                        BulbIp.Text = ip;
                    }
                    _message = ip == old ? null
                        : old.Length == 0 ? $"Found your bulb at {ip}."
                        : $"Bulb found at a new address: now using {ip} (was {old}).";
                    then?.Invoke(ip);
                }
                else
                {
                    _message = $"{autoReason} Found {bulbs.Count} bulbs; pick yours.";
                    ShowScanResults(bulbs, then);
                }
            });
        });
    }

    private void ShowScanResults(List<WizBulb.Found> bulbs, Action<string>? onPicked)
    {
        if (bulbs.Count == 0)
        {
            MessageBox.Show(this,
                "No WiZ bulb answered. Make sure this PC is on the same network as the bulb and the bulb is switched on, then scan again.",
                "No bulbs found", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        var list = new ListBox { MaxHeight = 320 };
        foreach (var bulb in bulbs)
        {
            string current = bulb.Ip == CurrentIp ? "  (current)" : "";
            list.Items.Add(new ListBoxItem
            {
                Tag = bulb.Ip,
                Content = new TextBlock
                {
                    Text = $"{bulb.Ip}{current}\n{bulb.Mac}{(bulb.Model != null ? " · " + bulb.Model : "")}",
                    Margin = new Thickness(0, 4, 0, 4),
                },
            });
        }
        list.SelectedIndex = 0;
        var window = DialogWindow.Create(this, bulbs.Count == 1 ? "Found 1 bulb" : $"Found {bulbs.Count} bulbs", list, out var buttons);
        DialogWindow.AddButton(buttons, "Cancel").Click += (_, _) => window.Close();
        var use = DialogWindow.AddButton(buttons, "Use this bulb", accent: true);
        string? picked = null;
        use.Click += (_, _) =>
        {
            picked = (list.SelectedItem as ListBoxItem)?.Tag as string;
            window.Close();
        };
        list.MouseDoubleClick += (_, _) =>
        {
            picked = (list.SelectedItem as ListBoxItem)?.Tag as string;
            window.Close();
        };
        window.ShowDialog();
        if (picked == null) return;
        _autoScannedIp = picked;
        BulbIp.Text = picked;
        _message = $"Bulb set to {picked}";
        onPicked?.Invoke(picked);
    }

    // ----------------- LIGHT COLOURS & SCHEDULES -----------------

    /// <summary>Rebuilds the colour and schedule lists when their settings changed.</summary>
    private void RefreshLists()
    {
        var s = SettingsStore.Current;
        if (!ReferenceEquals(s.Theme, _shownTheme))
        {
            _shownTheme = s.Theme;
            RefreshColorRows(s.Theme);
        }
        if (!ReferenceEquals(s.Schedules, _shownSchedules))
        {
            _shownSchedules = s.Schedules;
            RefreshScheduleRows(s.Schedules);
        }
    }

    private static BulbLight LightFor(LightTheme theme, int row) =>
        row == IdleRow ? theme.Idle : row == DropRow ? theme.Drop : theme.Tiers[row];

    private void RefreshColorRows(LightTheme theme)
    {
        ColorRows.Children.Clear();
        for (int row = 0; row < RowNames.Length; row++)
        {
            int index = row;
            var light = LightFor(theme, row);
            var content = new StackPanel { Orientation = Orientation.Horizontal };
            content.Children.Add(new Ellipse
            {
                Width = 28,
                Height = 28,
                Fill = new SolidColorBrush(ColorUtil.Swatch(light)),
                Stroke = Brushes.Gray,
                StrokeThickness = 1,
                Margin = new Thickness(0, 0, 14, 0),
            });
            var texts = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            texts.Children.Add(new TextBlock { Text = RowNames[row], FontSize = 14 });
            texts.Children.Add(new TextBlock { Text = ColorUtil.Describe(light), Style = (Style)FindResource("Hint") });
            content.Children.Add(texts);

            var button = new Button
            {
                Content = content,
                HorizontalAlignment = HorizontalAlignment.Stretch,
                HorizontalContentAlignment = HorizontalAlignment.Left,
                Padding = new Thickness(10, 6, 10, 6),
                Margin = new Thickness(0, 0, 0, 6),
            };
            button.Click += (_, _) => EditColour(index);
            ColorRows.Children.Add(button);
        }
    }

    private void EditColour(int row)
    {
        var edited = ColourEditor.Show(this, RowNames[row], LightFor(SettingsStore.Current.Theme, row), () => CurrentIp);
        if (edited == null) return;
        SettingsStore.Update(s => s with
        {
            Theme = row switch
            {
                IdleRow => s.Theme with { Idle = edited },
                DropRow => s.Theme with { Drop = edited },
                _ => s.Theme.WithTier(row, edited),
            },
        });
    }

    private void RefreshScheduleRows(IReadOnlyList<Schedule> schedules)
    {
        ScheduleRows.Children.Clear();
        var sorted = schedules
            .OrderBy(s => !s.Enabled)
            .ThenBy(s => s.NextTrigger() ?? long.MaxValue);
        foreach (var schedule in sorted)
        {
            var row = new Grid { Margin = new Thickness(0, 4, 0, 4), Opacity = schedule.Enabled ? 1 : 0.55 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var texts = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            texts.Children.Add(new TextBlock { Text = ScheduleText.Title(schedule), FontSize = 14 });
            texts.Children.Add(new TextBlock { Text = ScheduleText.Detail(schedule), Style = (Style)FindResource("Hint") });
            row.Children.Add(texts);

            // Timers run once and are simply deleted; time-of-day schedules can be paused
            if (!schedule.IsTimer)
            {
                var enabled = new CheckBox
                {
                    Content = "On",
                    IsChecked = schedule.Enabled,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(8, 0, 8, 0),
                    ToolTip = "Enable or pause this schedule",
                };
                enabled.Click += (_, _) => SettingsStore.ReplaceSchedule(schedule with { Enabled = enabled.IsChecked == true });
                Grid.SetColumn(enabled, 1);
                row.Children.Add(enabled);
            }
            var delete = new Button
            {
                Content = new TextBlock { Text = "", FontFamily = new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets") },
                ToolTip = "Delete schedule",
                VerticalAlignment = VerticalAlignment.Center,
                Padding = new Thickness(8, 6, 8, 6),
            };
            delete.Click += (_, _) => SettingsStore.DeleteSchedule(schedule.Id);
            Grid.SetColumn(delete, 2);
            row.Children.Add(delete);

            ScheduleRows.Children.Add(row);
        }
    }

    private void OnAddSchedule()
    {
        var schedule = ScheduleEditor.Show(this);
        if (schedule == null) return;
        var saved = SettingsStore.AddSchedule(schedule);
        if (saved.NextTrigger() is long at)
            _message = $"The bulb will turn {(saved.TurnOn ? "on" : "off")} at {ScheduleText.Time(at)}.";
    }

    /// <summary>Called by the app after a schedule ran (lists refresh through settings changes).</summary>
    public void OnScheduleRan(Schedule schedule, bool ok)
    {
        if (ok) ShowPowerState(schedule.TurnOn);
    }

    // ----------------- RESET -----------------

    private void OnResetAll()
    {
        if (!Confirm("Bulb IP, speaker delay, sensitivity, mode, fixed colour, all light colours and all schedules " +
                     "go back to how they were when the app was installed.", "Reset all settings?"))
            return;
        SettingsStore.ResetAll();
        _autoScannedIp = null;
        Bind();
        _message = "All settings are back to their defaults.";
    }

    private bool Confirm(string text, string title = "WiZ Music Sync") =>
        MessageBox.Show(this, text, title, MessageBoxButton.OKCancel, MessageBoxImage.Question) == MessageBoxResult.OK;

    private static string SensitivityWord(int value) => value switch
    {
        < 20 => "Very low",
        < 45 => "Low",
        <= 55 => "Normal",
        <= 80 => "High",
        _ => "Very high",
    };
}
