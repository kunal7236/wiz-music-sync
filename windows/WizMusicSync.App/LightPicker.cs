using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using WizMusicSync.Core;

namespace WizMusicSync.App;

/// <summary>
/// Picks a <see cref="BulbLight"/>: a Colour tab with the wheel, a White tab with warmth presets and a
/// slider, and a shared brightness slider. Used for the fixed colour and every music-sync state.
/// </summary>
public sealed class LightPicker : UserControl
{
    private const int MinTemp = 2200;
    private const int MaxTemp = 6500;
    private static readonly (string Name, int Kelvin)[] WhitePresets = { ("Warm", 2700), ("Neutral", 4200), ("Daylight", 6500) };

    /// <summary>Raised on every user change.</summary>
    public event Action<BulbLight>? Changed;

    private (byte R, byte G, byte B) _rgb = (0, 0, 255);
    private int _temp = 4200;
    private int _dimming = 100;
    private bool _isWhite;
    /// <summary>True while <see cref="SetLight"/> fills the controls, so their events don't count as edits.</summary>
    private bool _setting;

    private readonly Border _preview = new() { Height = 44, CornerRadius = new CornerRadius(6) };
    private readonly TextBlock _previewLabel = new()
    {
        FontSize = 14,
        FontWeight = FontWeights.SemiBold,
        HorizontalAlignment = HorizontalAlignment.Center,
        VerticalAlignment = VerticalAlignment.Center,
    };
    private readonly RadioButton _colourTab;
    private readonly RadioButton _whiteTab;
    private readonly ColorWheel _wheel = new() { HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 8, 0, 0) };
    private readonly StackPanel _whitePanel = new() { Margin = new Thickness(0, 8, 0, 0) };
    private readonly TextBlock _warmthLabel = new() { Margin = new Thickness(0, 12, 0, 4) };
    private readonly Slider _warmth = new() { Minimum = MinTemp, Maximum = MaxTemp, TickFrequency = 100, IsSnapToTickEnabled = true };
    private readonly TextBlock _brightnessLabel = new() { Margin = new Thickness(0, 16, 0, 4) };
    private readonly Slider _brightness = new() { Minimum = 10, Maximum = 100, TickFrequency = 1, IsSnapToTickEnabled = true };

    public LightPicker()
    {
        string group = "lightPicker" + Guid.NewGuid().ToString("N");
        _colourTab = new RadioButton { Content = "Colour", GroupName = group, Margin = new Thickness(0, 0, 24, 0) };
        _whiteTab = new RadioButton { Content = "White", GroupName = group };

        var root = new StackPanel();
        _preview.Child = _previewLabel;
        root.Children.Add(_preview);

        var tabs = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 12, 0, 0) };
        tabs.Children.Add(_colourTab);
        tabs.Children.Add(_whiteTab);
        root.Children.Add(tabs);
        _colourTab.Checked += (_, _) => SwitchTab(white: false);
        _whiteTab.Checked += (_, _) => SwitchTab(white: true);

        root.Children.Add(_wheel);
        _wheel.Picked += (h, s) =>
        {
            _rgb = ColorUtil.HsvToRgb(h, s, 1);
            OnEdited();
        };

        var presets = new UniformGrid { Columns = WhitePresets.Length };
        foreach (var (name, kelvin) in WhitePresets)
        {
            var button = new Button { Content = name, Margin = new Thickness(0, 0, 8, 0), HorizontalAlignment = HorizontalAlignment.Stretch };
            button.Click += (_, _) =>
            {
                _temp = kelvin;
                _setting = true;
                _warmth.Value = kelvin;
                _setting = false;
                OnEdited();
            };
            presets.Children.Add(button);
        }
        _whitePanel.Children.Add(presets);
        _whitePanel.Children.Add(_warmthLabel);
        _whitePanel.Children.Add(_warmth);
        _warmth.ValueChanged += (_, e) =>
        {
            if (_setting) return;
            _temp = (int)e.NewValue;
            OnEdited();
        };
        root.Children.Add(_whitePanel);

        root.Children.Add(_brightnessLabel);
        root.Children.Add(_brightness);
        _brightness.ValueChanged += (_, e) =>
        {
            if (_setting) return;
            _dimming = (int)e.NewValue;
            OnEdited();
        };

        Content = root;
        SetLight(new RgbLight(0, 0, 255, 100));
    }

    /// <summary>What the picker currently shows.</summary>
    public BulbLight Light => _isWhite
        ? new WhiteLight(_temp, _dimming)
        : new RgbLight(_rgb.R, _rgb.G, _rgb.B, _dimming);

    /// <summary>Shows <paramref name="light"/> without raising <see cref="Changed"/>. An untouched colour is returned exactly as given.</summary>
    public void SetLight(BulbLight light)
    {
        _setting = true;
        _dimming = light.Dimming;
        switch (light)
        {
            case RgbLight c:
                _isWhite = false;
                _rgb = ((byte)c.R, (byte)c.G, (byte)c.B);
                break;
            case WhiteLight w:
                _isWhite = true;
                _temp = w.Temp;
                break;
        }
        var (hue, saturation) = ColorUtil.RgbToHueSaturation(_rgb.R, _rgb.G, _rgb.B);
        _wheel.SetHueSaturation(hue, saturation);
        _warmth.Value = _temp;
        _brightness.Value = _dimming;
        (_isWhite ? _whiteTab : _colourTab).IsChecked = true;
        _setting = false;
        ShowPanel();
        UpdatePreview();
    }

    private void SwitchTab(bool white)
    {
        if (_setting || white == _isWhite) return;
        _isWhite = white;
        ShowPanel();
        OnEdited();
    }

    private void ShowPanel()
    {
        _wheel.Visibility = _isWhite ? Visibility.Collapsed : Visibility.Visible;
        _whitePanel.Visibility = _isWhite ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OnEdited()
    {
        UpdatePreview();
        Changed?.Invoke(Light);
    }

    private void UpdatePreview()
    {
        var light = Light;
        var color = ColorUtil.Swatch(light);
        _preview.Background = new SolidColorBrush(color);
        _previewLabel.Text = ColorUtil.Describe(light);
        // Keep the label readable on light and dark swatches
        double luminance = (0.299 * color.R + 0.587 * color.G + 0.114 * color.B) / 255;
        _previewLabel.Foreground = luminance > 0.6 ? Brushes.Black : Brushes.White;
        _warmthLabel.Text = $"Warmth: {_temp} K";
        _brightnessLabel.Text = $"Brightness: {_dimming}%";
    }
}
