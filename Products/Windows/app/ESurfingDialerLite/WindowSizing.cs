using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Threading;

namespace ESurfingDialerLite;

/// <summary>无标题栏窗口按显示器 DPI 与工作区适配首开尺寸，保持449×912预览比例，禁用最大化。</summary>
/// <remarks>
/// 125% DPI 下以449×912物理像素为基准，初次显示跟随系统缩放，最多占工作区90%。
/// 跨 DPI 显示器保留所选物理尺寸，再按目标显示器的最小尺寸与工作区限幅。
/// 保留边框等比调整和最小化，移除系统最大化入口；不遮挡任务栏。
/// 工作区预留12像素余量；默认最小为所在显示器适配首开尺寸的0.8倍。
/// 整数像素舍入允许不足 1 个像素的宽高误差；不使用后台计时器。
/// </remarks>
public sealed class WindowSizing : IDisposable
{
    public const int DesignWidth = 449;
    public const int DesignHeight = 912;
    public const int DefaultWidthPixels = 449;
    public const int DefaultHeightPixels = 912;
    private const uint ReferenceDpi = 120;
    private const double StartupWorkAreaFraction = 0.9;
    private const int WorkAreaInsetPixels = 12;
    private const int WmGetMinMaxInfo = 0x0024;
    private const int WmWindowPosChanging = 0x0046;
    private const int WmSysCommand = 0x0112;
    private const int WmSizing = 0x0214;
    private const int WmEnterSizeMove = 0x0231;
    private const int WmExitSizeMove = 0x0232;
    private const int WmDpiChanged = 0x02E0;
    private const int WmDisplayChange = 0x007E;
    private const int WmSettingChange = 0x001A;
    private const int ScMaximize = 0xF030;
    private const int WsMaximizeBox = 0x00010000;
    private const int GwlStyle = -16;
    private const uint SwpNoSize = 0x0001;
    private const uint SwpNoMove = 0x0002;
    private const uint SwpNoZOrder = 0x0004;
    private const uint SwpNoActivate = 0x0010;
    private const uint SwpFrameChanged = 0x0020;
    private static readonly ConditionalWeakTable<Window, WindowSizing> Instances = new();
    private readonly Window _window;
    private readonly double _minimumScale;
    private HwndSource? _source;
    private PixelSize _preferredSize;
    private int _preferredHeightPixels;
    private bool _applyingBounds;
    private bool _interactiveResize;
    private PixelSize _resizeOriginSize;
    private bool _loadedApplied;
    private bool _disposed;
    private bool _dpiChangePending;
    private int _dpiChangeVersion;

    /// <summary>
    /// 在 InitializeComponent 后调用一次即可；已加载的窗口也可接入。
    /// minimumScale 是所在显示器适配首开尺寸的最小倍数。
    /// </summary>
    public static WindowSizing Attach(Window window, double minimumScale = 0.8)
    {
        ArgumentNullException.ThrowIfNull(window);
        window.Dispatcher.VerifyAccess();
        if (!double.IsFinite(minimumScale) || minimumScale <= 0 || minimumScale > 1)
            throw new ArgumentOutOfRangeException(nameof(minimumScale));
        return Instances.GetValue(window, value => new WindowSizing(value, minimumScale));
    }

    private WindowSizing(Window window, double minimumScale)
    {
        _window = window;
        _minimumScale = minimumScale;
        // 旧 XAML 的 DIP 下限不能覆盖物理像素约束，原生消息负责实际拖动范围。
        window.SizeToContent = SizeToContent.Manual;
        window.MinWidth = window.MinHeight = 0;
        window.MaxWidth = window.MaxHeight = double.PositiveInfinity;
        window.ResizeMode = ResizeMode.CanResize;
        window.SourceInitialized += OnSourceInitialized;
        window.Loaded += OnLoaded;
        window.StateChanged += OnStateChanged;
        window.SizeChanged += OnSizeChanged;
        window.Closed += OnClosed;
        AttachSource();
        if (window.IsLoaded) ApplyLoadedSize();
    }

    private void OnSourceInitialized(object? sender, EventArgs e) => AttachSource();

    private void AttachSource()
    {
        if (_source != null || _disposed) return;
        var handle = new WindowInteropHelper(_window).Handle;
        if (handle == IntPtr.Zero) return;
        _source = HwndSource.FromHwnd(handle);
        if (_source == null) return;
        _source.AddHook(WindowMessage);
        SetWindowLong(handle, GwlStyle, GetWindowLong(handle, GwlStyle) & ~WsMaximizeBox);
        SetWindowPos(handle, IntPtr.Zero, 0, 0, 0, 0,
            SwpNoSize | SwpNoMove | SwpNoZOrder | SwpNoActivate | SwpFrameChanged);
        ApplyBounds(0, null, center: true);
    }

    private void OnLoaded(object sender, RoutedEventArgs e) => ApplyLoadedSize();

    private void ApplyLoadedSize()
    {
        if (_loadedApplied || _disposed) return;
        _loadedApplied = true;
        AttachSource();
        ApplyBounds(0, null, center: true);
    }

    private void OnStateChanged(object? sender, EventArgs e)
    {
        if (_disposed || _window.WindowState != WindowState.Maximized) return;
        var height = _preferredHeightPixels;
        _window.WindowState = WindowState.Normal;
        ApplyBounds(height, null, center: false);
    }

    private void OnSizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (!_interactiveResize) UpdateCornerRadius();
    }

    private void UpdateCornerRadius()
    {
        var chrome = System.Windows.Shell.WindowChrome.GetWindowChrome(_window);
        if (chrome != null && _window.ActualWidth > 0)
        {
            var radius = new CornerRadius(_window.ActualWidth * 18 / 410);
            if (chrome.CornerRadius != radius) chrome.CornerRadius = radius;
        }
    }

    private IntPtr WindowMessage(IntPtr hwnd, int message, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (_disposed) return IntPtr.Zero;
        if (message == WmEnterSizeMove)
        {
            var bounds = CurrentBounds();
            _resizeOriginSize = new PixelSize(bounds.Width, bounds.Height);
            _interactiveResize = true;
        }
        if (message == WmExitSizeMove)
        {
            _interactiveResize = false;
            UpdateCornerRadius();
        }
        if (message == WmSysCommand && (wParam.ToInt64() & 0xFFF0) == ScMaximize)
        {
            handled = true;
            return IntPtr.Zero;
        }
        if (message == WmGetMinMaxInfo && lParam != IntPtr.Zero)
        {
            var limits = Marshal.PtrToStructure<MinMaxInfo>(lParam);
            var work = GetWorkArea(CurrentBounds());
            var minimumScale = MinimumScale(work);
            var maximum = FitSize(double.MaxValue, work, minimumScale);
            var minimum = FitSize(minimumScale, work, minimumScale);
            limits.MinTrackSize = new NativePoint(minimum.Width, minimum.Height);
            limits.MaxTrackSize = new NativePoint(maximum.Width, maximum.Height);
            Marshal.StructureToPtr(limits, lParam, false);
            handled = true;
            return IntPtr.Zero;
        }
        if (message == WmSizing && lParam != IntPtr.Zero)
        {
            var bounds = Marshal.PtrToStructure<NativeRect>(lParam);
            var work = GetWorkArea(bounds);
            // Corner motion is measured from the initial mouse-down rectangle. Comparing it with
            // the preceding constrained frame feeds the correction back and alternates axes.
            bounds = ConstrainSizing(bounds, (int)wParam.ToInt64(), work, MinimumScale(work),
                _interactiveResize ? _resizeOriginSize : _preferredSize);
            RememberSize(new PixelSize(bounds.Width, bounds.Height));
            Marshal.StructureToPtr(bounds, lParam, false);
            handled = true;
            return new IntPtr(1);
        }
        // Snap 和其他尺寸请求不一定发送 WM_SIZING，也需约束客户区比例。
        if (message == WmWindowPosChanging && lParam != IntPtr.Zero && !_applyingBounds && !_dpiChangePending
            && _window.WindowState == WindowState.Normal && !IsIconic(hwnd))
        {
            var position = Marshal.PtrToStructure<WindowPosition>(lParam);
            if ((position.Flags & SwpNoSize) == 0 && position.Width > 0 && position.Height > 0)
            {
                var current = CurrentBounds();
                // Windows keeps a small iconic rectangle while minimized. Reopening the remembered
                // normal size is a restoration, not a new resize request.
                if ((position.Width == current.Width && position.Height == current.Height)
                    || (position.Width == _preferredSize.Width && position.Height == _preferredSize.Height)) return IntPtr.Zero;
                var left = (position.Flags & SwpNoMove) == 0 ? position.X : current.Left;
                var top = (position.Flags & SwpNoMove) == 0 ? position.Y : current.Top;
                var proposed = new NativeRect(left, top, left + position.Width, top + position.Height);
                var work = GetWorkArea(proposed);
                var size = FitSize(RequestedScale(proposed, 8, _preferredSize), work, MinimumScale(work));
                var fitted = PlaceWithinWorkArea(left, top, size, work);
                position.X = fitted.Left; position.Y = fitted.Top;
                position.Width = size.Width; position.Height = size.Height;
                position.Flags &= ~SwpNoMove;
                RememberSize(size);
                Marshal.StructureToPtr(position, lParam, false);
            }
        }
        if (message == WmDpiChanged && lParam != IntPtr.Zero)
        {
            var suggested = Marshal.PtrToStructure<NativeRect>(lParam);
            var desired = _preferredHeightPixels;
            var version = ++_dpiChangeVersion;
            _dpiChangePending = true;
            // 让WPF先更新DPI，再恢复所选物理尺寸。
            _window.Dispatcher.BeginInvoke(DispatcherPriority.Loaded, new Action(() =>
            {
                if (_disposed || version != _dpiChangeVersion) return;
                try { ApplyBounds(desired, suggested, center: false); }
                finally { _dpiChangePending = false; }
            }));
        }
        if (message is WmDisplayChange or WmSettingChange)
        {
            _window.Dispatcher.BeginInvoke(DispatcherPriority.Loaded, new Action(() =>
            {
                if (!_disposed && !_dpiChangePending && _window.WindowState == WindowState.Normal)
                    ApplyBounds(_preferredHeightPixels, null, center: false);
            }));
        }
        return IntPtr.Zero;
    }

    private void ApplyBounds(int desiredHeight, NativeRect? suggested, bool center)
    {
        if (_source == null || _disposed || _window.WindowState == WindowState.Minimized) return;
        var current = suggested ?? CurrentBounds();
        var work = GetWorkArea(current);
        var dpi = GetDpiForWindow(_source.Handle);
        var size = desiredHeight > 0
            ? FitSize(desiredHeight / (double)DefaultHeightPixels, work, MinimumScale(work))
            : StartupSize(work, dpi);
        var left = center ? work.Left + (work.Width - size.Width) / 2 : current.Left;
        var top = center ? work.Top + (work.Height - size.Height) / 2 : current.Top;
        var fitted = PlaceWithinWorkArea(left, top, size, work);
        var scale = dpi > 0 ? dpi / 96d : 1d;
        _applyingBounds = true;
        try
        {
            _window.Width = size.Width / scale;
            _window.Height = size.Height / scale;
            SetWindowPos(_source.Handle, IntPtr.Zero, fitted.Left, fitted.Top, size.Width, size.Height,
                SwpNoZOrder | SwpNoActivate);
            RememberSize(size);
        }
        finally { _applyingBounds = false; }
    }

    private double MinimumScale(NativeRect work) => _minimumScale
        * StartupSize(work, _source != null ? GetDpiForWindow(_source.Handle) : ReferenceDpi).Height / DefaultHeightPixels;

    internal static PixelSize StartupSize(NativeRect work, uint dpi)
    {
        var available = AvailableSize(work);
        // 跟随用户的系统缩放，再为小屏窗口四周保留空间。
        var scale = Math.Min((dpi > 0 ? dpi : ReferenceDpi) / (double)ReferenceDpi,
            StartupWorkAreaFraction * Math.Min(available.Width / (double)DefaultWidthPixels,
                available.Height / (double)DefaultHeightPixels));
        return FitAvailableSize(scale, available.Width, available.Height, 0);
    }

    private static PixelSize AvailableSize(NativeRect work)
    {
        var availableWidth = Math.Max(1, work.Width - Math.Min(WorkAreaInsetPixels * 2, Math.Max(0, work.Width - 1)));
        var availableHeight = Math.Max(1, work.Height - Math.Min(WorkAreaInsetPixels * 2, Math.Max(0, work.Height - 1)));
        return new PixelSize(availableWidth, availableHeight);
    }

    internal static PixelSize FitSize(double requestedScale, NativeRect work, double minimumScale)
    {
        var available = AvailableSize(work);
        return FitAvailableSize(requestedScale, available.Width, available.Height, minimumScale);
    }

    private static PixelSize FitAvailableSize(double requestedScale, double availableWidth,
        double availableHeight, double minimumScale)
    {
        availableWidth = Math.Max(1, availableWidth);
        availableHeight = Math.Max(1, availableHeight);
        var maximum = Math.Min(availableWidth / DefaultWidthPixels, availableHeight / DefaultHeightPixels);
        var scale = Math.Clamp(requestedScale, Math.Min(minimumScale, maximum), maximum);
        return new PixelSize(Math.Max(1, (int)Math.Round(DefaultWidthPixels * scale)),
            Math.Max(1, (int)Math.Round(DefaultHeightPixels * scale)));
    }

    internal static NativeRect ConstrainSizing(NativeRect bounds, int edge, NativeRect work,
        double minimumScale, PixelSize previous)
    {
        // 保留拖动方向的对边/对角；到屏幕边缘时停止放大，避免整窗突然跳位。
        var availableWidth = Math.Max(1, work.Width - WorkAreaInsetPixels * 2);
        var availableHeight = Math.Max(1, work.Height - WorkAreaInsetPixels * 2);
        if (edge is 1 or 4 or 7) availableWidth = Math.Min(availableWidth, bounds.Right - work.Left);
        if (edge is 2 or 5 or 8) availableWidth = Math.Min(availableWidth, work.Right - bounds.Left);
        if (edge is 3 or 4 or 5) availableHeight = Math.Min(availableHeight, bounds.Bottom - work.Top);
        if (edge is 6 or 7 or 8) availableHeight = Math.Min(availableHeight, work.Bottom - bounds.Top);
        if (edge is 1 or 2)
        {
            var center = bounds.Top + bounds.Height / 2d;
            availableHeight = Math.Min(availableHeight, (int)Math.Floor(2 * Math.Min(center - work.Top, work.Bottom - center)));
        }
        if (edge is 3 or 6)
        {
            var center = bounds.Left + bounds.Width / 2d;
            availableWidth = Math.Min(availableWidth, (int)Math.Floor(2 * Math.Min(center - work.Left, work.Right - center)));
        }
        var size = FitAvailableSize(RequestedScale(bounds, edge, previous), availableWidth, availableHeight,
            minimumScale);
        var left = edge is 1 or 4 or 7 ? bounds.Right - size.Width : bounds.Left;
        var top = edge is 3 or 4 or 5 ? bounds.Bottom - size.Height : bounds.Top;
        if (edge is 1 or 2) top = bounds.Top + (bounds.Height - size.Height) / 2;
        if (edge is 3 or 6) left = bounds.Left + (bounds.Width - size.Width) / 2;
        return PlaceWithinWorkArea(left, top, size, work);
    }

    private static double RequestedScale(NativeRect bounds, int edge, PixelSize previous)
    {
        var horizontal = Math.Max(1, bounds.Width) / (double)DefaultWidthPixels;
        var vertical = Math.Max(1, bounds.Height) / (double)DefaultHeightPixels;
        if (edge is 1 or 2) return horizontal;
        if (edge is 3 or 6) return vertical;
        var previousHorizontal = Math.Max(1, previous.Width) / (double)DefaultWidthPixels;
        var previousVertical = Math.Max(1, previous.Height) / (double)DefaultHeightPixels;
        return Math.Abs(horizontal - previousHorizontal) >= Math.Abs(vertical - previousVertical) ? horizontal : vertical;
    }

    private void RememberSize(PixelSize size)
    {
        _preferredSize = size;
        _preferredHeightPixels = Math.Max(1, size.Height);
    }

    private static NativeRect PlaceWithinWorkArea(int left, int top, PixelSize size, NativeRect work)
    {
        left = Math.Clamp(left, work.Left, Math.Max(work.Left, work.Right - size.Width));
        top = Math.Clamp(top, work.Top, Math.Max(work.Top, work.Bottom - size.Height));
        return new NativeRect(left, top, left + size.Width, top + size.Height);
    }

    private NativeRect CurrentBounds()
    {
        if (_source != null && GetWindowRect(_source.Handle, out var bounds)) return bounds;
        var width = _preferredSize.Width > 0 ? _preferredSize.Width : (int)Math.Round(DefaultHeightPixels * DesignWidth / (double)DesignHeight);
        return new NativeRect(0, 0, width, _preferredSize.Height > 0 ? _preferredSize.Height : DefaultHeightPixels);
    }

    private static NativeRect GetWorkArea(NativeRect bounds)
    {
        var monitor = MonitorFromRect(ref bounds, 2);
        var info = new MonitorInfo { Size = Marshal.SizeOf<MonitorInfo>() };
        if (GetMonitorInfo(monitor, ref info)) return info.Work;
        // .NET 10 的 Windows 支持范围有上述 API，只有查询失败时才用保守后备。
        return new NativeRect(0, 0, DesignWidth + WorkAreaInsetPixels * 2,
            DefaultHeightPixels + WorkAreaInsetPixels * 2);
    }

    private void OnClosed(object? sender, EventArgs e) => Dispose();

    public void Dispose()
    {
        if (_disposed) return;
        _window.Dispatcher.VerifyAccess();
        _disposed = true;
        _window.SourceInitialized -= OnSourceInitialized;
        _window.Loaded -= OnLoaded;
        _window.StateChanged -= OnStateChanged;
        _window.SizeChanged -= OnSizeChanged;
        _window.Closed -= OnClosed;
        _source?.RemoveHook(WindowMessage);
        _source = null;
        Instances.Remove(_window);
    }

    internal readonly record struct PixelSize(int Width, int Height);
    [StructLayout(LayoutKind.Sequential)]
    internal struct NativeRect(int left, int top, int right, int bottom)
    {
        public int Left = left, Top = top, Right = right, Bottom = bottom;
        public readonly int Width => Right - Left;
        public readonly int Height => Bottom - Top;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct NativePoint(int x, int y) { public int X = x, Y = y; }
    [StructLayout(LayoutKind.Sequential)]
    private struct MinMaxInfo
    {
        public NativePoint Reserved, MaxSize, MaxPosition, MinTrackSize, MaxTrackSize;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct MonitorInfo { public int Size; public NativeRect Monitor, Work; public uint Flags; }
    [StructLayout(LayoutKind.Sequential)]
    private struct WindowPosition
    {
        public IntPtr Handle, InsertAfter; public int X, Y, Width, Height; public uint Flags;
    }
    [DllImport("user32.dll")] private static extern uint GetDpiForWindow(IntPtr handle);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool IsIconic(IntPtr handle);
    [DllImport("user32.dll")] private static extern bool GetWindowRect(IntPtr handle, out NativeRect bounds);
    [DllImport("user32.dll")] private static extern IntPtr MonitorFromRect(ref NativeRect bounds, uint flags);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern bool GetMonitorInfo(IntPtr monitor, ref MonitorInfo info);
    [DllImport("user32.dll", EntryPoint = "GetWindowLongW")] private static extern int GetWindowLong(IntPtr handle, int index);
    [DllImport("user32.dll", EntryPoint = "SetWindowLongW")] private static extern int SetWindowLong(IntPtr handle, int index, int value);
    [DllImport("user32.dll")] private static extern bool SetWindowPos(IntPtr handle, IntPtr insertAfter,
        int x, int y, int width, int height, uint flags);
}
