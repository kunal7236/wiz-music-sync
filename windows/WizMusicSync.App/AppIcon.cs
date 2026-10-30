using System;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace WizMusicSync.App;

/// <summary>The bulb icon (Assets/app.ico) for the tray and the window title bar.</summary>
public static class AppIcon
{
    private static readonly Uri IconUri = new("pack://application:,,,/Assets/app.ico");

    public static System.Drawing.Icon LoadIcon()
    {
        using var stream = Application.GetResourceStream(IconUri)!.Stream;
        // Ask for the small-icon size so the tray picks the crisp 16/32 px frame
        return new System.Drawing.Icon(stream, System.Windows.Forms.SystemInformation.SmallIconSize);
    }

    public static ImageSource ImageSource { get; } = BitmapFrame.Create(IconUri);
}
