using System;
using System.Globalization;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using WizMusicSync.Core;

namespace WizMusicSync.App;

/// <summary>Small helper for the code-built dialogs: a titled window with content and a button row.</summary>
internal static class DialogWindow
{
    public static Window Create(Window owner, string title, UIElement content, out StackPanel buttons)
    {
        buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
        var root = new StackPanel { Margin = new Thickness(24) };
        root.Children.Add(content);
        root.Children.Add(buttons);
        return new Window
        {
            Owner = owner,
            Title = title,
            Icon = owner.Icon,
            Width = 420,
            SizeToContent = SizeToContent.Height,
            ResizeMode = ResizeMode.NoResize,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            ShowInTaskbar = false,
            Content = new ScrollViewer { Content = root, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, MaxHeight = 760 },
        };
    }

    public static Button AddButton(StackPanel row, string text, bool accent = false)
    {
        var button = new Button { Content = text, MinWidth = 90, Margin = new Thickness(8, 0, 0, 0) };
        if (accent && Application.Current.TryFindResource("AccentButtonStyle") is Style style) button.Style = style;
        row.Children.Add(button);
        return button;
    }
}

/// <summary>Edits one music-sync state's light, with "Try on bulb".</summary>
internal static class ColourEditor
{
    /// <returns>The edited light, or null if cancelled.</returns>
    public static BulbLight? Show(Window owner, string title, BulbLight current, Func<string> bulbIp)
    {
        var picker = new LightPicker();
        picker.SetLight(current);
        var window = DialogWindow.Create(owner, title, picker, out var buttons);
        var tryButton = DialogWindow.AddButton(buttons, "Try on bulb");
        var cancel = DialogWindow.AddButton(buttons, "Cancel");
        var save = DialogWindow.AddButton(buttons, "Save", accent: true);

        bool tried = false;
        BulbLight? result = null;
        tryButton.Click += (_, _) =>
        {
            tried = true;
            string payload = WizBulb.Payload(picker.Light), ip = bulbIp();
            if (ip.Length > 0) System.Threading.Tasks.Task.Run(() => WizBulb.SendOnce(ip, payload));
        };
        cancel.Click += (_, _) => window.Close();
        save.Click += (_, _) =>
        {
            result = picker.Light;
            window.Close();
        };
        window.ShowDialog();

        // Put the bulb back after a try, unless the sync is driving it
        if (tried && !SyncSession.IsRunning)
        {
            string payload = WizBulb.Payload(SettingsStore.Current.Theme.Idle), ip = bulbIp();
            if (ip.Length > 0) System.Threading.Tasks.Task.Run(() => WizBulb.SendOnce(ip, payload));
        }
        return result;
    }
}

/// <summary>New power schedule: on/off, then either a delay or a time of day with repeat days.</summary>
internal static class ScheduleEditor
{
    /// <returns>The new schedule (id 0, assigned on save), or null if cancelled.</returns>
    public static Schedule? Show(Window owner)
    {
        TextBlock Label(string text) => new() { Text = text, Style = (Style)Application.Current.FindResource("Label") };
        string group = Guid.NewGuid().ToString("N");

        var root = new StackPanel();

        // ---- On / Off ----
        root.Children.Add(Label("Action"));
        var turnOn = new RadioButton { Content = "Turn on", GroupName = group + "a", Margin = new Thickness(0, 0, 24, 0) };
        var turnOff = new RadioButton { Content = "Turn off", GroupName = group + "a", IsChecked = true };
        root.Children.Add(Row(turnOn, turnOff));

        // ---- When ----
        root.Children.Add(Label("When"));
        var afterDelay = new RadioButton { Content = "After a delay", GroupName = group + "k", Margin = new Thickness(0, 0, 24, 0), IsChecked = true };
        var atTime = new RadioButton { Content = "At a time", GroupName = group + "k" };
        root.Children.Add(Row(afterDelay, atTime));

        // Delay panel
        var hours = NumberBox(0, 23, 0);
        var minutes = NumberBox(0, 59, 30);
        var delayPanel = new StackPanel { Margin = new Thickness(0, 8, 0, 0) };
        delayPanel.Children.Add(Row(hours, Text("h", 8, 24), minutes, Text("min", 8, 0)));
        var quick = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 8, 0, 0) };
        foreach (var (text, total) in new[] { ("10 min", 10), ("30 min", 30), ("1 h", 60), ("2 h", 120) })
        {
            var b = new Button { Content = text, Margin = new Thickness(0, 0, 8, 0) };
            b.Click += (_, _) =>
            {
                hours.SelectedItem = total / 60;
                minutes.SelectedItem = total % 60;
            };
            quick.Children.Add(b);
        }
        delayPanel.Children.Add(quick);
        root.Children.Add(delayPanel);

        // Time panel: default to the next full hour
        var next = DateTime.Now.AddHours(1);
        var hour = NumberBox(0, 23, next.Hour, "00");
        var minute = NumberBox(0, 59, 0, "00");
        var timePanel = new StackPanel { Margin = new Thickness(0, 8, 0, 0) };
        timePanel.Children.Add(Row(hour, Text(":", 6, 6), minute, Text("(24-hour)", 12, 0)));
        timePanel.Children.Add(Label("Repeat"));
        var once = new RadioButton { Content = "Once", GroupName = group + "r", IsChecked = true };
        var daily = new RadioButton { Content = "Every day", GroupName = group + "r" };
        var weekdays = new RadioButton { Content = "Weekdays", GroupName = group + "r" };
        var custom = new RadioButton { Content = "Choose days", GroupName = group + "r" };
        foreach (var r in new[] { once, daily, weekdays, custom }) timePanel.Children.Add(r);
        var dayBoxes = Schedule.MondayFirst.Select(d => new CheckBox { Content = d.ToString()[..3], Tag = d, MinWidth = 0, Margin = new Thickness(0, 0, 4, 0) }).ToArray();
        var dayRow = new WrapPanel { Margin = new Thickness(0, 4, 0, 0), Visibility = Visibility.Collapsed };
        foreach (var box in dayBoxes) dayRow.Children.Add(box);
        timePanel.Children.Add(dayRow);
        custom.Checked += (_, _) => dayRow.Visibility = Visibility.Visible;
        custom.Unchecked += (_, _) => dayRow.Visibility = Visibility.Collapsed;
        root.Children.Add(timePanel);

        void ShowKind()
        {
            bool delay = afterDelay.IsChecked == true;
            delayPanel.Visibility = delay ? Visibility.Visible : Visibility.Collapsed;
            timePanel.Visibility = delay ? Visibility.Collapsed : Visibility.Visible;
        }
        afterDelay.Checked += (_, _) => ShowKind();
        atTime.Checked += (_, _) => ShowKind();
        ShowKind();

        var error = new TextBlock { Foreground = Brushes.IndianRed, Margin = new Thickness(0, 8, 0, 0), Visibility = Visibility.Collapsed, TextWrapping = TextWrapping.Wrap };
        root.Children.Add(error);

        var window = DialogWindow.Create(owner, "New schedule", root, out var buttons);
        var cancel = DialogWindow.AddButton(buttons, "Cancel");
        var save = DialogWindow.AddButton(buttons, "Save", accent: true);
        Schedule? result = null;
        cancel.Click += (_, _) => window.Close();
        save.Click += (_, _) =>
        {
            bool on = turnOn.IsChecked == true;
            if (afterDelay.IsChecked == true)
            {
                int total = (int)hours.SelectedItem * 60 + (int)minutes.SelectedItem;
                if (total == 0)
                {
                    error.Text = "Choose a delay of at least 1 minute.";
                    error.Visibility = Visibility.Visible;
                    return;
                }
                result = new Schedule(0, on, AtMillis: DateTimeOffset.Now.AddMinutes(total).ToUnixTimeMilliseconds());
            }
            else
            {
                int days = daily.IsChecked == true ? Schedule.EveryDay
                    : weekdays.IsChecked == true ? Schedule.Weekdays
                    : custom.IsChecked == true ? dayBoxes.Where(b => b.IsChecked == true).Sum(b => Schedule.DayBit((DayOfWeek)b.Tag))
                    : 0;
                if (custom.IsChecked == true && days == 0)
                {
                    error.Text = "Pick at least one day.";
                    error.Visibility = Visibility.Visible;
                    return;
                }
                result = new Schedule(0, on, Hour: (int)hour.SelectedItem, Minute: (int)minute.SelectedItem, Days: days);
            }
            window.Close();
        };
        window.ShowDialog();
        return result;
    }

    private static StackPanel Row(params UIElement[] children)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal };
        foreach (var child in children) row.Children.Add(child);
        return row;
    }

    private static TextBlock Text(string text, double left, double right) =>
        new() { Text = text, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(left, 0, right, 0) };

    /// <summary>Drop-down of whole numbers; <see cref="ComboBox.SelectedItem"/> is the int.</summary>
    private static ComboBox NumberBox(int min, int max, int value, string format = "0")
    {
        var box = new ComboBox { MinWidth = 72, ItemStringFormat = format };
        for (int i = min; i <= max; i++) box.Items.Add(i);
        box.SelectedItem = value;
        return box;
    }
}

/// <summary>Display text for schedules, following the PC's 12/24-hour setting.</summary>
internal static class ScheduleText
{
    public static string Time(int hour, int minute) =>
        DateTime.Today.AddHours(hour).AddMinutes(minute).ToString("t", CultureInfo.CurrentCulture);

    public static string Time(long unixMillis) =>
        DateTimeOffset.FromUnixTimeMilliseconds(unixMillis).ToLocalTime().ToString("t", CultureInfo.CurrentCulture);

    /// <summary>Main line of a schedule row, e.g. "Off at 23:40".</summary>
    public static string Title(Schedule s) =>
        $"{(s.TurnOn ? "On" : "Off")} at {(s.AtMillis is long at ? Time(at) : Time(s.Hour, s.Minute))}";

    /// <summary>Second line of a schedule row, e.g. "Timer", "Weekdays" or "Once · done".</summary>
    public static string Detail(Schedule s) =>
        s.IsTimer ? "Timer"
        : s.IsOneOff && !s.Enabled ? "Once · done"
        : !s.Enabled ? $"{Schedule.RepeatLabel(s.Days)} · paused"
        : Schedule.RepeatLabel(s.Days);
}
