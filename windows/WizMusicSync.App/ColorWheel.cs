using System;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace WizMusicSync.App;

/// <summary>
/// Hue/saturation colour wheel: hue goes round the circle, saturation runs from white at the centre
/// to the pure colour at the edge (exactly HSV with value = 1). Drag to pick.
/// </summary>
public sealed class ColorWheel : FrameworkElement
{
    private const double MaxSize = 260;
    private const double Inset = 10;

    /// <summary>Raised while the user drags, with hue in degrees (0-360) and saturation (0-1).</summary>
    public event Action<double, double>? Picked;

    private double _hue;
    private double _saturation = 1;
    private BitmapSource? _wheel;
    private int _wheelPixels;

    public ColorWheel()
    {
        Focusable = false;
        Cursor = Cursors.Cross;
    }

    public void SetHueSaturation(double hue, double saturation)
    {
        _hue = hue;
        _saturation = Math.Clamp(saturation, 0, 1);
        InvalidateVisual();
    }

    protected override Size MeasureOverride(Size available)
    {
        double size = Math.Min(MaxSize, double.IsInfinity(available.Width) ? MaxSize : available.Width);
        return new Size(size, size);
    }

    private double Radius => Math.Min(ActualWidth, ActualHeight) / 2 - Inset;
    private Point Center => new(ActualWidth / 2, ActualHeight / 2);

    protected override void OnRender(DrawingContext dc)
    {
        double radius = Radius;
        if (radius <= 0) return;
        var center = Center;
        // Transparent fill so the whole square takes mouse input
        dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, ActualWidth, ActualHeight));

        var scale = VisualTreeHelper.GetDpi(this).DpiScaleX;
        int pixels = (int)Math.Ceiling(radius * 2 * scale);
        if (_wheel == null || _wheelPixels != pixels)
        {
            _wheel = RenderWheel(pixels, 96 * scale);
            _wheelPixels = pixels;
        }
        dc.DrawImage(_wheel, new Rect(center.X - radius, center.Y - radius, radius * 2, radius * 2));

        double angle = _hue * Math.PI / 180;
        var knob = new Point(center.X + Math.Cos(angle) * _saturation * radius, center.Y + Math.Sin(angle) * _saturation * radius);
        dc.DrawEllipse(null, new Pen(Brushes.White, 3), knob, 9, 9);
        dc.DrawEllipse(null, new Pen(Brushes.Black, 1.5), knob, 9, 9);
    }

    /// <summary>Paints the wheel pixel by pixel, with a one-pixel soft edge.</summary>
    private static BitmapSource RenderWheel(int size, double dpi)
    {
        var pixels = new byte[size * size * 4];
        double r = size / 2.0;
        for (int y = 0; y < size; y++)
        for (int x = 0; x < size; x++)
        {
            double dx = x + 0.5 - r, dy = y + 0.5 - r;
            double distance = Math.Sqrt(dx * dx + dy * dy);
            double alpha = Math.Clamp(r - distance, 0, 1);
            if (alpha <= 0) continue;
            double hue = (Math.Atan2(dy, dx) * 180 / Math.PI + 360) % 360;
            var (cr, cg, cb) = ColorUtil.HsvToRgb(hue, Math.Min(1, distance / r), 1);
            int i = (y * size + x) * 4;
            // Premultiplied BGRA
            pixels[i] = (byte)(cb * alpha);
            pixels[i + 1] = (byte)(cg * alpha);
            pixels[i + 2] = (byte)(cr * alpha);
            pixels[i + 3] = (byte)(255 * alpha);
        }
        var bitmap = BitmapSource.Create(size, size, dpi, dpi, PixelFormats.Pbgra32, null, pixels, size * 4);
        bitmap.Freeze();
        return bitmap;
    }

    protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
    {
        CaptureMouse();
        Pick(e.GetPosition(this));
        e.Handled = true;
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        if (IsMouseCaptured) Pick(e.GetPosition(this));
    }

    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
    {
        ReleaseMouseCapture();
        e.Handled = true;
    }

    private void Pick(Point p)
    {
        var center = Center;
        double dx = p.X - center.X, dy = p.Y - center.Y;
        _hue = (Math.Atan2(dy, dx) * 180 / Math.PI + 360) % 360;
        _saturation = Math.Clamp(Math.Sqrt(dx * dx + dy * dy) / Radius, 0, 1);
        InvalidateVisual();
        Picked?.Invoke(_hue, _saturation);
    }
}
