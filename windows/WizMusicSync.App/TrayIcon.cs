using System;
using System.Windows;
using Forms = System.Windows.Forms;

namespace WizMusicSync.App;

/// <summary>Notification-area icon: left click opens the window; the menu covers sync, power and exit.</summary>
public sealed class TrayIcon : IDisposable
{
    private readonly Forms.NotifyIcon _icon;

    public TrayIcon()
    {
        var app = App.Instance;
        var open = new Forms.ToolStripMenuItem("Open WiZ Music Sync", null, (_, _) => app.ShowMainWindow());
        var sync = new Forms.ToolStripMenuItem("Start sync", null, (_, _) => app.Window.ToggleSync());
        var on = new Forms.ToolStripMenuItem("Turn bulb on", null, (_, _) => app.Window.SetPower(true));
        var off = new Forms.ToolStripMenuItem("Turn bulb off", null, (_, _) => app.Window.SetPower(false));
        var exit = new Forms.ToolStripMenuItem("Exit", null, (_, _) => app.ExitApp());

        var menu = new Forms.ContextMenuStrip();
        menu.Items.AddRange(new Forms.ToolStripItem[] { open, new Forms.ToolStripSeparator(), sync, on, off, new Forms.ToolStripSeparator(), exit });
        menu.Opening += (_, _) =>
        {
            sync.Text = SyncSession.IsRunning ? "Stop sync" : "Start sync";
            // Music sync only makes sense in Music sync mode
            sync.Enabled = SyncSession.IsRunning || !Core.SettingsStore.Current.FixedMode;
        };

        _icon = new Forms.NotifyIcon
        {
            Icon = AppIcon.LoadIcon(),
            Text = "WiZ Music Sync",
            ContextMenuStrip = menu,
            Visible = true,
        };
        _icon.MouseClick += (_, e) =>
        {
            if (e.Button == Forms.MouseButtons.Left) app.ShowMainWindow();
        };
    }

    /// <summary>Shows a Windows notification from the tray icon.</summary>
    public void Notify(string title, string text, bool warning = false) =>
        _icon.ShowBalloonTip(5000, title, text, warning ? Forms.ToolTipIcon.Warning : Forms.ToolTipIcon.Info);

    public void Dispose()
    {
        _icon.Visible = false;
        _icon.Dispose();
    }
}
