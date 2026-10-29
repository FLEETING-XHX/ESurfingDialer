using System.Collections;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using ESurfingDialerLite;

internal static partial class Program
{
    private const BindingFlags UiFlags = BindingFlags.Instance | BindingFlags.NonPublic;
    private const double WindowPreviewLogicalHeight = 912d * 410 / 449;

    private static object? UiCall(MainWindow window, string method, params object?[] parameters) =>
        typeof(MainWindow).GetMethod(method, UiFlags)!.Invoke(window, parameters);

    private static T UiNode<T>(MainWindow window, string name) where T : class =>
        window.FindName(name) as T ?? throw new InvalidOperationException("缺少界面元素：" + name);

    private static void SaveUiBitmap(Visual visual, int width, int height, double dpiX, double dpiY, string path)
    {
        var bitmap = new RenderTargetBitmap(width, height, dpiX, dpiY, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var stream = File.Create(path);
        encoder.Save(stream);
    }

    // 稿件尺寸用于设计对照，实际窗口另行截图和校验，二者不互相代替。
    private static void RenderPenPage(MainWindow window, string output, string name, double height = WindowPreviewLogicalHeight)
    {
        var design = UiNode<FrameworkElement>(window, "DesignRoot");
        var widthValue = design.ReadLocalValue(FrameworkElement.WidthProperty);
        var heightValue = design.ReadLocalValue(FrameworkElement.HeightProperty);
        try
        {
            System.Windows.Input.Keyboard.ClearFocus();
            design.Width = 410;
            design.Height = height;
            design.Measure(new Size(410, height));
            design.Arrange(new Rect(0, 0, 410, height));
            design.UpdateLayout();
            if (UiNode<UIElement>(window, "HomePage").Visibility == Visibility.Visible)
            {
                foreach (var anchor in new[]
                {
                    (Name: "CurrentAccountCard", Top: 93.955),
                    (Name: "AuthenticationCard", Top: 303.715),
                    (Name: "NetworkStatusCard", Top: 421.705)
                })
                {
                    var card = UiNode<FrameworkElement>(window, anchor.Name);
                    var top = card.TransformToAncestor(design).Transform(new Point()).Y;
                    Check(Math.Abs(top - anchor.Top) <= 1,
                        anchor.Name + " retains its original Pen design position");
                }
            }
            SaveUiBitmap(design, 449, (int)Math.Round(height * 449 / 410), 96d * 449 / 410, 96d * 449 / 410, Path.Combine(output, name + ".png"));
        }
        finally
        {
            if (widthValue == DependencyProperty.UnsetValue) design.ClearValue(FrameworkElement.WidthProperty);
            else design.SetValue(FrameworkElement.WidthProperty, widthValue);
            if (heightValue == DependencyProperty.UnsetValue) design.ClearValue(FrameworkElement.HeightProperty);
            else design.SetValue(FrameworkElement.HeightProperty, heightValue);
            window.UpdateLayout();
        }
    }

    private static PointCollection PenPreviewPoints(string data)
    {
        var flattened = Geometry.Parse(data).GetFlattenedPathGeometry();
        var points = new PointCollection();
        void Add(Point point) => points.Add(new Point(point.X * 334.544 / 412, point.Y * 30.59 / 28));
        foreach (var figure in flattened.Figures)
        {
            Add(figure.StartPoint);
            foreach (var segment in figure.Segments)
            {
                if (segment is PolyLineSegment polyline) foreach (var point in polyline.Points) Add(point);
                else if (segment is LineSegment line) Add(line.Point);
                else throw new InvalidOperationException("原稿预览曲线未展开为折线。");
            }
        }
        return points;
    }

    private static void RenderPenHomeFixture(MainWindow window, string output)
    {
        var account = UiNode<TextBlock>(window, "AccountSummaryText");
        var download = UiNode<System.Windows.Shapes.Polyline>(window, "DownloadLine");
        var upload = UiNode<System.Windows.Shapes.Polyline>(window, "UploadLine");
        var previousName = account.Text;
        var previousDownload = download.Points;
        var previousUpload = upload.Points;
        try
        {
            account.Text = "尚未配置账户";
            download.Points = PenPreviewPoints("M0 21c22-5 27-16 51-7s32 11 56-2 38-8 63 3 38 8 61-6 40-5 66 6 39 10 63-4 30-4 52 4");
            upload.Points = PenPreviewPoints("M0 23c24-5 38 2 59-5s38-10 61 1 38 5 61 1 38-11 63-4 38 9 63-2 40-5 63 4 23-1 42-8");
            RenderPenPage(window, output, "pen-home");
        }
        finally
        {
            account.Text = previousName;
            download.Points = previousDownload;
            upload.Points = previousUpload;
            window.UpdateLayout();
        }
        Check(account.Text == previousName && ReferenceEquals(download.Points, previousDownload) && ReferenceEquals(upload.Points, previousUpload),
            "Pen home fixture restores real account text and trend data after rendering");
    }

    private static void ServiceLinkChecks(MainWindow window, List<ProcessStartInfo> launches, Action rejectNext)
    {
        var configBefore = File.ReadAllBytes(AppPaths.ConfigFile);
        var logBefore = File.ReadAllBytes(AppPaths.ClientLogFile);
        var expected = new[]
        {
            (Name: "RechargeButton", Url: "https://gd.189.cn/service/pay/czPay/index.html?utm_ch=gd_wt&utm_as=cx&utm_bt=czjf"),
            (Name: "ChangePasswordButton", Url: "http://enet.10000.gd.cn:10001/qs/password.jsp?wlanacip=&wlanuserip=")
        };
        foreach (var entry in expected)
        {
            var beforeCount = launches.Count;
            UiNode<Button>(window, entry.Name).RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Check(launches.Count == beforeCount + 1, entry.Name + " launches exactly one browser request");
            var launch = launches.Last();
            Check(launch.FileName == entry.Url && launch.UseShellExecute && launch.Arguments.Length == 0 && launch.ArgumentList.Count == 0,
                entry.Name + " preserves the official URL and uses the default browser");
        }
        rejectNext();
        UiNode<Button>(window, "ChangePasswordButton").RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        var notice = UiNode<TextBlock>(window, "NoticeText").Text;
        Check(notice == "无法打开默认浏览器，请检查浏览器设置后重试。" && !notice.Contains("http", StringComparison.OrdinalIgnoreCase),
            "Browser launch failure gives a Chinese notice without URL or exception details");
        Check(configBefore.SequenceEqual(File.ReadAllBytes(AppPaths.ConfigFile)) && logBefore.SequenceEqual(File.ReadAllBytes(AppPaths.ClientLogFile)),
            "Browser success and failure preserve configuration and logs");
        var dialer = (DialerCoreProcess)typeof(MainWindow).GetField("_dialer", UiFlags)!.GetValue(window)!;
        Check(!dialer.IsRunning && !dialer.IsRequested, "Service page clicks do not start authentication");
        UiNode<UIElement>(window, "Notice").Visibility = Visibility.Collapsed;
    }

    private static void CompactSettingsChecks(MainWindow window)
    {
        UiCall(window, "ShowPage", UiNode<UIElement>(window, "SettingsPage"));
        window.UpdateLayout();
        var scroll = UiNode<ScrollViewer>(window, "SettingsScrollViewer");
        Check(scroll.ScrollableHeight > 0, "Compact settings have a usable vertical scroll range");
        scroll.ScrollToEnd();
        DrainUiQueue();
        window.UpdateLayout();
        foreach (var name in new[] { "ChangePasswordButton", "RechargeButton" })
        {
            var button = UiNode<FrameworkElement>(window, name);
            var bounds = button.TransformToAncestor(scroll).TransformBounds(new Rect(new Point(), button.RenderSize));
            Check(button.IsVisible && button.ActualHeight > 0 && bounds.Top >= -1 && bounds.Bottom <= scroll.ViewportHeight + 1,
                name + " remains visible and clickable after scrolling in the compact window");
        }
        var root = UiNode<FrameworkElement>(window, "DesignRoot");
        var nav = UiNode<FrameworkElement>(window, "NavigationBar");
        var navBounds = nav.TransformToAncestor(root).TransformBounds(new Rect(new Point(), nav.RenderSize));
        var scrollBounds = scroll.TransformToAncestor(root).TransformBounds(new Rect(new Point(), scroll.RenderSize));
        var fadeTop = UiNode<FrameworkElement>(window, "BottomContentFade").TransformToAncestor(root).Transform(new Point()).Y;
        var serviceBounds = UiNode<FrameworkElement>(window, "RechargeButton").TransformToAncestor(root)
            .TransformBounds(new Rect(UiNode<FrameworkElement>(window, "RechargeButton").RenderSize));
        Check(navBounds.Bottom <= root.ActualHeight + 1 && scrollBounds.Bottom > navBounds.Top
            && serviceBounds.Bottom <= fadeTop + 1,
            "Content can scroll beneath the floating navigation while the final service action remains fully above its fade");
        scroll.ScrollToTop();
        window.UpdateLayout();
    }

    private static void HomeGlyphBoundsChecks(MainWindow window)
    {
        foreach (var item in new[]
        {
            (Text: "AccountSummaryText", Card: "CurrentAccountCard"),
            (Text: "CampusStatusText", Card: "CampusConnectionButton"),
            (Text: "AuthenticationTitleText", Card: "AuthenticationCard")
        })
        {
            var text = UiNode<TextBlock>(window, item.Text);
            var card = UiNode<FrameworkElement>(window, item.Card);
            var natural = new TextBlock
            {
                Style = new Style(typeof(TextBlock)), Text = text.Text, FontFamily = text.FontFamily,
                FontSize = text.FontSize, FontWeight = text.FontWeight, Padding = text.Padding
            };
            natural.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
            var bounds = text.TransformToAncestor(card).TransformBounds(new Rect(new Point(), text.RenderSize));
            Check(text.ActualHeight + 1 >= natural.DesiredSize.Height && bounds.Top >= 0 && bounds.Bottom <= card.ActualHeight,
                item.Text + " has natural glyph height and stays within its card in the actual compact window");
        }
        var management = UiNode<Button>(window, "AccountManagementButton");
        var action = UiVisualDescendants(management).OfType<TextBlock>().Single(t => t.Text == "编辑当前账户");
        var actionBounds = action.TransformToAncestor(management).TransformBounds(new Rect(new Point(), action.RenderSize));
        Check(actionBounds.Top >= 0 && actionBounds.Bottom <= management.ActualHeight,
            "Account management action stays inside its card in the actual compact window");
        Check(management.Focus() && management.IsKeyboardFocused, "Account management card supports actual keyboard focus");
        window.UpdateLayout();
        var focusedBounds = action.TransformToAncestor(management).TransformBounds(new Rect(new Point(), action.RenderSize));
        Check(Math.Abs(focusedBounds.Top - actionBounds.Top) < 0.01 && Math.Abs(focusedBounds.Height - actionBounds.Height) < 0.01,
            "Keyboard focus does not shift or compress account management text");
        System.Windows.Input.Keyboard.ClearFocus();
    }

    private static void EmbeddedFontChecks(MainWindow window, string output)
    {
        Check(window.FontFamily.Source.Contains("/ESurfingDialerLite;component/Fonts/#Noto Sans SC", StringComparison.Ordinal),
            "Default UI font explicitly refers to the embedded application font family");
        var results = new List<object>();
        foreach (var weight in new[] { FontWeights.Normal, FontWeights.Medium, FontWeights.SemiBold, FontWeights.Bold })
        {
            var typeface = new Typeface(window.FontFamily, FontStyles.Normal, weight, FontStretches.Normal);
            if (!typeface.TryGetGlyphTypeface(out var glyph) || glyph is null)
                throw new InvalidOperationException("无法解析内嵌字重：" + weight.ToOpenTypeWeight());
            var uri = glyph.FontUri.OriginalString;
            Check(uri.StartsWith("pack://application:", StringComparison.OrdinalIgnoreCase)
                && uri.Contains("ESurfingDialerLite;component/Fonts/", StringComparison.OrdinalIgnoreCase),
                "Font weight " + weight.ToOpenTypeWeight() + " resolves from application resources instead of installed fonts");
            Check(glyph.Weight == weight && glyph.StyleSimulations == StyleSimulations.None,
                "Font weight " + weight.ToOpenTypeWeight() + " uses its actual static face without simulated weight");
            Check("天翼校园主页日志设置".All(character => glyph.CharacterToGlyphMap.ContainsKey(character)),
                "Font weight " + weight.ToOpenTypeWeight() + " contains the main Chinese UI glyphs");
            results.Add(new { Weight = weight.ToOpenTypeWeight(), FontUri = uri,
                ResolvedWeight = glyph.Weight.ToOpenTypeWeight(), Simulation = glyph.StyleSimulations.ToString() });
        }
        File.WriteAllText(Path.Combine(output, "font-typefaces.json"), JsonSerializer.Serialize(results, new JsonSerializerOptions { WriteIndented = true }));
    }

    private static IEnumerable<DependencyObject> UiVisualDescendants(DependencyObject parent)
    {
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(parent); index++)
        {
            var child = VisualTreeHelper.GetChild(parent, index);
            yield return child;
            foreach (var descendant in UiVisualDescendants(child)) yield return descendant;
        }
    }

    private static void AccountGlyphBoundsChecks(MainWindow window)
    {
        var list = UiNode<ItemsControl>(window, "AccountList");
        foreach (var name in new[] { "校园网账号1", "校园网账号2", "备用账号" })
        {
            var text = UiVisualDescendants(list).OfType<TextBlock>().Single(t => t.Text == name);
            DependencyObject? parent = text;
            while (parent is not Button && parent != list) parent = VisualTreeHelper.GetParent(parent);
            var button = parent as Button ?? throw new InvalidOperationException("账户文字缺少点击容器。");
            var bounds = text.TransformToAncestor(button).TransformBounds(new Rect(new Point(), text.RenderSize));
            Check(bounds.Top >= 0 && bounds.Bottom <= button.ActualHeight,
                "Account name stays inside its selectable row: " + name);
        }
    }

    private static void FixedCanvasScalingChecks(MainWindow window)
    {
        window.UpdateLayout();
        var design = UiNode<FrameworkElement>(window, "DesignRoot");
        var root = (FrameworkElement)window.Content;
        Check(design.Width == 410 && Math.Abs(design.Height - WindowPreviewLogicalHeight) < 0.001
            && design.ActualWidth == 410 && Math.Abs(design.ActualHeight - WindowPreviewLogicalHeight) < 0.001,
            "Resizing preserves the approved 449 by 912 window preview without reflow");
        var transform = design.TransformToAncestor(root);
        var origin = transform.Transform(new Point());
        var horizontal = transform.Transform(new Point(410, 0));
        var vertical = transform.Transform(new Point(0, WindowPreviewLogicalHeight));
        var scaleX = (horizontal.X - origin.X) / 410;
        var scaleY = (vertical.Y - origin.Y) / WindowPreviewLogicalHeight;
        Check(scaleX > 0 && Math.Abs(scaleX - scaleY) < 0.000001,
            "The complete design uses one uniform scale for both axes");
        Check(origin.X >= -1 && origin.Y >= -1 && horizontal.X <= root.ActualWidth + 1 && vertical.Y <= root.ActualHeight + 1,
            "The entire original canvas fits the actual client viewport");
    }

    private static object CaptureActualWindow(MainWindow window, string output, string name)
    {
        DrainUiQueue();
        window.UpdateLayout();
        var handle = new WindowInteropHelper(window).Handle;
        if (!UiGetWindowRect(handle, out var outer) || !UiGetClientRect(handle, out var client))
            throw new InvalidOperationException("无法读取测试窗口尺寸。");
        var root = (FrameworkElement)window.Content;
        var dpi = VisualTreeHelper.GetDpi(root);
        var design = UiNode<FrameworkElement>(window, "DesignRoot");
        SaveUiBitmap(root, (int)Math.Ceiling(root.ActualWidth * dpi.DpiScaleX), (int)Math.Ceiling(root.ActualHeight * dpi.DpiScaleY),
            dpi.PixelsPerInchX, dpi.PixelsPerInchY, Path.Combine(output, name + ".png"));
        return new
        {
            Name = name, OuterPixels = new { Width = outer.Right - outer.Left, Height = outer.Bottom - outer.Top },
            ClientPixels = new { Width = client.Right - client.Left, Height = client.Bottom - client.Top },
            Dpi = UiGetDpiForWindow(handle), WpfClientDip = new { Width = root.ActualWidth, Height = root.ActualHeight },
            DesignDip = new { Width = design.ActualWidth, Height = design.ActualHeight }
        };
    }

    private static void ProportionalWindowChecks(MainWindow window, string output, List<object> metrics)
    {
        var handle = new WindowInteropHelper(window).Handle;
        if (!UiGetWindowRect(handle, out var initial) || !UiGetClientRect(handle, out var initialClient))
            throw new InvalidOperationException("无法读取测试窗口尺寸。");
        var frameWidth = initial.Right - initial.Left - initialClient.Right;
        var frameHeight = initial.Bottom - initial.Top - initialClient.Bottom;
        var work = UiWorkArea(handle);
        var requestedWidth = (int)Math.Round((initial.Right - initial.Left) * 1.07);
        var requestedHeight = (int)Math.Round((initial.Bottom - initial.Top) * 0.82);
        var centerY = (initial.Top + initial.Bottom) / 2d;
        var horizontalScale = Math.Min(requestedWidth / 449d,
            Math.Min(Math.Min(work.Right - initial.Left, work.Right - work.Left - 24) / 449d,
                Math.Min(work.Bottom - work.Top - 24,
                    2 * Math.Min(centerY - work.Top, work.Bottom - centerY)) / 912d));
        var memory = Marshal.AllocHGlobal(Marshal.SizeOf<UiRect>());
        try
        {
            var proposed = initial;
            proposed.Right = proposed.Left + requestedWidth;
            Marshal.StructureToPtr(proposed, memory, false);
            UiSendMessage(handle, 0x0214, (IntPtr)2, memory); // WM_SIZING，拖动右侧边框。
            proposed = Marshal.PtrToStructure<UiRect>(memory);
            var width = proposed.Right - proposed.Left;
            var height = proposed.Bottom - proposed.Top;
            Check(width == (int)Math.Round(449 * horizontalScale) && height == (int)Math.Round(912 * horizontalScale)
                && width > initial.Right - initial.Left
                && height <= work.Bottom - work.Top - 24 && Math.Abs(height - width * 912.0 / 449) <= 1.1,
                "Horizontal drag preserves the approved 449 by 912 window preview ratio");
            Check(UiSetWindowPos(handle, IntPtr.Zero, proposed.Left, proposed.Top, width, height, 0x0014),
                "Proportional resize applies to the actual shown HWND");
            DrainUiQueue();
            Check(UiGetWindowRect(handle, out var actual) && actual.Right - actual.Left == width && actual.Bottom - actual.Top == height,
                "Actual resized window retains the constrained physical dimensions");
            FixedCanvasScalingChecks(window);
            window.Hide();
            window.Show();
            DrainUiQueue();
            Check(UiGetWindowRect(handle, out var shownAgain) && shownAgain.Right - shownAgain.Left == width && shownAgain.Bottom - shownAgain.Top == height,
                "Hiding and showing preserves the user-resized window dimensions");
            Check(UiSetWindowPos(handle, IntPtr.Zero, -64, proposed.Top, width, height, 0x0014),
                "Same-size move applies to the actual shown HWND");
            DrainUiQueue();
            Check(UiGetWindowRect(handle, out var moved) && moved.Left == -64 && moved.Right - moved.Left == width && moved.Bottom - moved.Top == height,
                "Same-size moves preserve the requested position without clamping or resizing");
            Check(UiSetWindowPos(handle, IntPtr.Zero, proposed.Left, proposed.Top, width, height, 0x0014),
                "Window position restores after the isolated movement check");
            DrainUiQueue();
            CompactSettingsChecks(window);
            metrics.Add(CaptureActualWindow(window, output, "enlarged-settings"));

            proposed = initial;
            proposed.Right = proposed.Left + 100;
            Marshal.StructureToPtr(proposed, memory, false);
            UiSendMessage(handle, 0x0214, (IntPtr)2, memory);
            proposed = Marshal.PtrToStructure<UiRect>(memory);
            width = proposed.Right - proposed.Left;
            height = proposed.Bottom - proposed.Top;
            Check(width >= (initial.Right - initial.Left) * 0.8 - 1 && height >= (initial.Bottom - initial.Top) * 0.8 - 1
                && Math.Abs(height - width * 912.0 / 449) <= 1,
                "Minimum drag size preserves the preview aspect ratio and usable content size");
            Check(UiSetWindowPos(handle, IntPtr.Zero, proposed.Left, proposed.Top, width, height, 0x0014),
                "Minimum proportional size applies to the actual shown HWND");
            DrainUiQueue();
            FixedCanvasScalingChecks(window);
            CompactSettingsChecks(window);
            metrics.Add(CaptureActualWindow(window, output, "minimum-settings"));
            proposed = initial;
            proposed.Bottom = proposed.Top + requestedHeight;
            Marshal.StructureToPtr(proposed, memory, false);
            UiSendMessage(handle, 0x0214, (IntPtr)6, memory); // WM_SIZING，拖动下侧边框。
            proposed = Marshal.PtrToStructure<UiRect>(memory);
            Check(proposed.Bottom - proposed.Top == requestedHeight &&
                Math.Abs((proposed.Right - proposed.Left) - requestedHeight * 449.0 / 912) <= 1,
                "Vertical drag preserves the approved window preview ratio");
        }
        finally
        {
            Marshal.FreeHGlobal(memory);
            if (!UiSetWindowPos(handle, IntPtr.Zero, initial.Left, initial.Top, initial.Right - initial.Left,
                    initial.Bottom - initial.Top, 0x0014))
                throw new InvalidOperationException("无法还原测试窗口尺寸。");
            DrainUiQueue();
        }
    }

    private static Rect WindowPreviewBounds(MainWindow window, string name)
    {
        var element = UiNode<FrameworkElement>(window, name);
        var bounds = element.TransformToAncestor(UiNode<FrameworkElement>(window, "DesignRoot"))
            .TransformBounds(new Rect(element.RenderSize));
        return new Rect(bounds.X * 449 / 410, bounds.Y * 449 / 410, bounds.Width * 449 / 410, bounds.Height * 449 / 410);
    }

    private static void ContinuousResizeChecks(MainWindow window, string output)
    {
        var handle = new WindowInteropHelper(window).Handle;
        Check(UiGetWindowRect(handle, out var initial), "Continuous resize starts from measured native bounds");
        var memory = Marshal.AllocHGlobal(Marshal.SizeOf<UiRect>());
        var chrome = System.Windows.Shell.WindowChrome.GetWindowChrome(window);
        var chromeChanges = 0;
        EventHandler changed = (_, _) => chromeChanges++;
        chrome.Changed += changed;
        var samples = new List<object>();
        try
        {
            for (var edge = 1; edge <= 8; edge++)
            {
                chromeChanges = 0;
                var monotonic = true;
                var proportional = true;
                var previousWidth = initial.Right - initial.Left;
                UiSendMessage(handle, 0x0231, IntPtr.Zero, IntPtr.Zero);
                foreach (var step in Enumerable.Range(1, 40).Concat(Enumerable.Range(0, 40).Reverse()))
                {
                    // Raw mouse coordinates stay relative to the rectangle at mouse-down, including when direction reverses.
                    var proposed = initial;
                    if (edge is 1 or 4 or 7) proposed.Left -= step;
                    if (edge is 2 or 5 or 8) proposed.Right += step;
                    if (edge is 3 or 4 or 5) proposed.Top -= step;
                    if (edge is 6 or 7 or 8) proposed.Bottom += step;
                    Marshal.StructureToPtr(proposed, memory, false);
                    UiSendMessage(handle, 0x0214, (IntPtr)edge, memory);
                    proposed = Marshal.PtrToStructure<UiRect>(memory);
                    var width = proposed.Right - proposed.Left;
                    var height = proposed.Bottom - proposed.Top;
                    monotonic &= step <= 39 && samples.Count % 80 >= 40 ? width <= previousWidth : width >= previousWidth;
                    proportional &= Math.Abs(height - width * 912d / 449) <= 1.1;
                    previousWidth = width;
                    if (!UiSetWindowPos(handle, IntPtr.Zero, proposed.Left, proposed.Top, width, height, 0x0014))
                        throw new InvalidOperationException("连续缩放无法应用原生尺寸。");
                    DrainUiQueue();
                    samples.Add(new { Edge = edge, Step = step, Width = width, Height = height });
                }
                File.WriteAllText(Path.Combine(output, "continuous-resize.json"), JsonSerializer.Serialize(new { LastEdge = edge, Monotonic = monotonic, Proportional = proportional, ChromeChangesDuringDrag = chromeChanges, Samples = samples }, new JsonSerializerOptions { WriteIndented = true }));
                Check(monotonic && proportional, $"Continuous resize from edge {edge} follows outward/inward motion without reversing size and retains the portrait ratio");
                Check(chromeChanges == 0, $"Dragging edge {edge} does not repeatedly repaint the native corner properties");
                UiSendMessage(handle, 0x0232, IntPtr.Zero, IntPtr.Zero);
                DrainUiQueue();
                if (!UiSetWindowPos(handle, IntPtr.Zero, initial.Left, initial.Top, initial.Right - initial.Left, initial.Bottom - initial.Top, 0x0014))
                    throw new InvalidOperationException("无法还原连续缩放测试窗口。");
                DrainUiQueue();
            }
        }
        finally
        {
            UiSendMessage(handle, 0x0232, IntPtr.Zero, IntPtr.Zero); // WM_EXITSIZEMOVE
            chrome.Changed -= changed;
            Marshal.FreeHGlobal(memory);
            if (!UiSetWindowPos(handle, IntPtr.Zero, initial.Left, initial.Top, initial.Right - initial.Left, initial.Bottom - initial.Top, 0x0014))
                throw new InvalidOperationException("无法还原连续缩放测试窗口。");
            DrainUiQueue();
        }
    }

    private static void WindowPreviewInteractionChecks(MainWindow window, string output, List<object> metrics)
    {
        var handle = new WindowInteropHelper(window).Handle;
        var chrome = System.Windows.Shell.WindowChrome.GetWindowChrome(window);
        Check(window.WindowStyle == WindowStyle.None && chrome is { CaptionHeight: 0, GlassFrameThickness.Left: 0, UseAeroCaptionButtons: false },
            "Native title text and caption buttons are replaced by the approved in-page controls");
        var expectedNav = new Rect(18, 803, 413, 79);
        var expectedControls = new Rect(359, 24, 72, 32);
        foreach (var page in new[] { "HomePage", "LogsPage", "SettingsPage", "HomePage", "SettingsPage", "LogsPage" })
        {
            UiCall(window, "ShowPage", UiNode<UIElement>(window, page));
            DrainUiQueue();
            var nav = WindowPreviewBounds(window, "NavigationBar");
            var controls = WindowPreviewBounds(window, "WindowControls");
            Check(Math.Abs(nav.X - expectedNav.X) < 0.01 && Math.Abs(nav.Y - expectedNav.Y) < 0.01
                && Math.Abs(nav.Width - expectedNav.Width) < 0.01 && Math.Abs(nav.Height - expectedNav.Height) < 0.01,
                "Floating navigation keeps identical Pen geometry while switching to " + page);
            Check(Math.Abs(controls.X - expectedControls.X) < 0.01 && Math.Abs(controls.Y - expectedControls.Y) < 0.01
                && Math.Abs(controls.Width - expectedControls.Width) < 0.01 && Math.Abs(controls.Height - expectedControls.Height) < 0.01,
                "Window controls keep identical Pen geometry while switching to " + page);
        }
        UiCall(window, "ShowPage", UiNode<UIElement>(window, "SettingsPage"));
        var scroll = UiNode<ScrollViewer>(window, "SettingsScrollViewer");
        scroll.ScrollToTop(); DrainUiQueue();
        var header = WindowPreviewBounds(window, "SettingsHeader");
        Check(UiNode<UIElement>(window, "TopContentFade").Visibility == Visibility.Collapsed,
            "Top fade is inactive before the settings content has scrolled");
        scroll.ScrollToEnd(); DrainUiQueue();
        Check(UiNode<UIElement>(window, "TopContentFade").Visibility == Visibility.Visible
            && WindowPreviewBounds(window, "SettingsHeader") == header
            && Math.Abs(WindowPreviewBounds(window, "NavigationBar").Top - expectedNav.Top) < 0.01,
            "Scrolling fades content without moving the fixed header or navigation");
        Check(!UiNode<UIElement>(window, "TopContentFade").IsHitTestVisible && !UiNode<UIElement>(window, "BottomContentFade").IsHitTestVisible,
            "Both fade overlays leave mouse and keyboard interaction available to the content");
        scroll.ScrollToTop(); DrainUiQueue();
        Check(UiNode<Button>(window, "RechargeButton").Focus(), "The final service action accepts real keyboard focus");
        DrainUiQueue();
        var focused = WindowPreviewBounds(window, "RechargeButton");
        Check(focused.Top >= 164 - 1 && focused.Bottom <= 710 + 1,
            "Keyboard focus scrolls the final service action fully clear of both fades");
        metrics.Add(CaptureActualWindow(window, output, "compact-settings-focused"));
        System.Windows.Input.Keyboard.ClearFocus();

        var config = (ClientConfig)typeof(MainWindow).GetField("_config", UiFlags)!.GetValue(window)!;
        var selected = config.SelectedAccountId;
        var accountIds = config.Accounts.Select(a => a.Id).ToArray();
        var tips = UiNode<CheckBox>(window, "BackgroundTipsCheckBox");
        tips.IsChecked = false; tips.RaiseEvent(new RoutedEventArgs(CheckBox.ClickEvent));
        var saved = new ConfigStore().Load();
        Check(!saved.BackgroundTips && saved.SelectedAccountId == selected && saved.Accounts.Select(a => a.Id).SequenceEqual(accountIds),
            "Background-tip preference persists without changing accounts or selection");
        var copied = (ClientConfig)typeof(ClientConfig).GetMethod("Copy", UiFlags)!.Invoke(saved, null)!;
        Check(!copied.BackgroundTips, "Account configuration copies retain the background-tip preference");
        tips.IsChecked = true; tips.RaiseEvent(new RoutedEventArgs(CheckBox.ClickEvent));
        UiNode<UIElement>(window, "Notice").Visibility = Visibility.Collapsed;

        Check(UiGetWindowRect(handle, out var before), "Window controls start with measurable native bounds");
        Check(window.FindName("ExpandWindowButton") == null && (UiGetWindowLong(handle, -16) & 0x00010000) == 0,
            "Both the in-page and native maximize buttons are removed");
        UiSendMessage(handle, 0x0112, (IntPtr)0xF030, IntPtr.Zero); DrainUiQueue();
        Check(window.WindowState == WindowState.Normal && UiGetWindowRect(handle, out var ignoredMaximize) && ignoredMaximize.Equals(before),
            "System maximize commands preserve the selected window size and position");
        window.WindowState = WindowState.Maximized; DrainUiQueue();
        Check(window.WindowState == WindowState.Normal && UiGetWindowRect(handle, out var rejectedMaximizedState) && rejectedMaximizedState.Equals(before),
            "Programmatic maximization also returns to the same normal window geometry");
        UiNode<Button>(window, "MinimizeWindowButton").RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); DrainUiQueue();
        Check(window.WindowState == WindowState.Minimized, "Minimize reaches the native minimized state");
        window.WindowState = WindowState.Normal; DrainUiQueue();
        Check(UiGetWindowRect(handle, out var unminimized) && unminimized.Equals(before),
            "Restoring from minimization preserves the selected size and position");
        UiNode<Button>(window, "CloseWindowButton").RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); DrainUiQueue();
        Check(!window.IsVisible, "The close control hides the client for its existing background behavior");
        window.Show(); DrainUiQueue();
        Check(UiGetWindowRect(handle, out var shown) && shown.Equals(before),
            "Showing the hidden client restores the same window geometry");
    }

    private static void UiOnlyChecks(string[] args)
    {
        StartupSizingMathChecks();
        var output = Path.GetFullPath(args.FirstOrDefault(a => !a.StartsWith("--", StringComparison.Ordinal)) ?? "artifacts/pen-ui-preview");
        Directory.CreateDirectory(output);
        var isolatedHome = Path.Combine(output, "preview-data");
        Check(!Directory.Exists(isolatedHome), "UI preview starts in a fresh isolated data directory");
        Environment.SetEnvironmentVariable("ESURFING_CLIENT_HOME", isolatedHome);
        Directory.CreateDirectory(AppPaths.AppDataDirectory);
        var config = new ClientConfig
        {
            AutoConnect = false, AutoStart = false, StartMinimized = false, EnhancedConnection = true,
            Accounts = new[] { "校园网账号1", "校园网账号2", "备用账号" }.Select((name, index) => new CampusAccount
            {
                Name = name, UserName = "ui-preview-" + index, ProtectedPassword = PasswordProtector.Protect("preview-only-password")
            }).ToList()
        };
        config.NormalizeAccounts();
        new ConfigStore().Save(config);
        var previousContext = SynchronizationContext.Current;
        var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        app.DispatcherUnhandledException += (_, e) => { _dispatcherFailure = e.Exception; e.Handled = true; };
        SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(app.Dispatcher));
        var launches = new List<ProcessStartInfo>();
        var rejectLaunch = false;
        MainWindow? window = null;
        try
        {
            window = new MainWindow(true, info =>
            {
                launches.Add(info);
                if (rejectLaunch) throw new InvalidOperationException("synthetic launch rejection token=preview-only");
            });
            EmbeddedFontChecks(window, output);
            ((DispatcherTimer)typeof(MainWindow).GetField("_healthTimer", UiFlags)!.GetValue(window)!).Stop();
            ((DispatcherTimer)typeof(MainWindow).GetField("_speedTimer", UiFlags)!.GetValue(window)!).Stop();
            var summaries = (IList)typeof(MainWindow).GetField("_summaries", UiFlags)!.GetValue(window)!;
            summaries.Clear();
            summaries.Add(new SummaryEntry("08:42", "认证成功", "校园网认证已完成，连接保持正常", (Brush)window.FindResource("Green")));
            summaries.Add(new SummaryEntry("昨天 22:16", "连接断开", "检测到网络不可用，等待重新连接", (Brush)window.FindResource("Coral")));
            summaries.Add(new SummaryEntry("昨天 22:14", "自动重连", "已启动增强连接，正在尝试恢复网络", new SolidColorBrush(Color.FromRgb(0xD7, 0x9B, 0x45))));
            window.Show();
            ((DispatcherTimer)typeof(MainWindow).GetField("_speedTimer", UiFlags)!.GetValue(window)!).Stop();
            DrainUiQueue();
            var handle = new WindowInteropHelper(window).Handle;
            var startupWork = UiWorkArea(handle);
            var startupDpi = UiGetDpiForWindow(handle);
            var startupScale = Math.Min((startupDpi > 0 ? startupDpi : 120) / 120d,
                0.9 * Math.Min((startupWork.Right - startupWork.Left - 24) / 449d,
                    (startupWork.Bottom - startupWork.Top - 24) / 912d));
            var expectedWidth = Math.Max(1, (int)Math.Round(449 * startupScale));
            var expectedHeight = Math.Max(1, (int)Math.Round(912 * startupScale));
            var outerRead = UiGetWindowRect(handle, out var outer);
            var clientRead = UiGetClientRect(handle, out var client);
            Check(outerRead && clientRead
                && outer.Bottom - outer.Top == expectedHeight && outer.Right - outer.Left == expectedWidth
                && client.Right == expectedWidth && client.Bottom == expectedHeight,
                "First visible frameless window adapts to its actual work area and DPI");
            Check(Math.Abs(client.Right - client.Bottom * 449d / 912) <= 0.8
                && outer.Left >= startupWork.Left + 12 && outer.Top >= startupWork.Top + 12
                && outer.Right <= startupWork.Right - 12 && outer.Bottom <= startupWork.Bottom - 12,
                "Adaptive startup retains the preview ratio and stays inside the work area with margins");
            File.WriteAllText(Path.Combine(output, "startup-size.json"), JsonSerializer.Serialize(new {
                Dpi = startupDpi, WorkArea = new { startupWork.Left, startupWork.Top, startupWork.Right, startupWork.Bottom },
                Expected = new { Width = expectedWidth, Height = expectedHeight },
                Actual = new { Width = client.Right, Height = client.Bottom }
            }, new JsonSerializerOptions { WriteIndented = true }));
            FixedCanvasScalingChecks(window);
            var metrics = new List<object> { CaptureActualWindow(window, output, "compact-home") };
            HomeGlyphBoundsChecks(window);
            CompactSettingsChecks(window);
            metrics.Add(CaptureActualWindow(window, output, "compact-settings-top"));
            UiNode<ScrollViewer>(window, "SettingsScrollViewer").ScrollToEnd();
            metrics.Add(CaptureActualWindow(window, output, "compact-settings-bottom"));
            ServiceLinkChecks(window, launches, () => rejectLaunch = true);
            WindowPreviewInteractionChecks(window, output, metrics);
            ContinuousResizeChecks(window, output);
            ProportionalWindowChecks(window, output, metrics);
            var activeConfig = (ClientConfig)typeof(MainWindow).GetField("_config", UiFlags)!.GetValue(window)!;
            activeConfig.AutoStart = true;
            activeConfig.StartMinimized = true;
            activeConfig.AutoConnect = true;
            UiCall(window, "ApplySettings");
            UiNode<ScrollViewer>(window, "SettingsScrollViewer").ScrollToTop();
            UiCall(window, "ShowPage", UiNode<UIElement>(window, "HomePage"));
            ((DispatcherTimer)typeof(MainWindow).GetField("_speedTimer", UiFlags)!.GetValue(window)!).Stop();
            RenderPenHomeFixture(window, output);
            UiCall(window, "ShowPage", UiNode<UIElement>(window, "LogsPage"));
            metrics.Add(CaptureActualWindow(window, output, "compact-logs"));
            RenderPenPage(window, output, "pen-logs");
            UiCall(window, "ShowPage", UiNode<UIElement>(window, "SettingsPage"));
            RenderPenPage(window, output, "pen-settings");
            UiNode<ScrollViewer>(window, "SettingsScrollViewer").ScrollToEnd();
            DrainUiQueue();
            RenderPenPage(window, output, "pen-settings-scrolled");
            UiCall(window, "ShowPage", UiNode<UIElement>(window, "HomePage"));
            ((DispatcherTimer)typeof(MainWindow).GetField("_speedTimer", UiFlags)!.GetValue(window)!).Stop();
            UiCall(window, "ShowAccounts");
            UiNode<TranslateTransform>(window, "SheetTranslation").BeginAnimation(TranslateTransform.YProperty, null);
            metrics.Add(CaptureActualWindow(window, output, "compact-accounts"));
            var viewport = (FrameworkElement)window.Content;
            var viewportDpi = VisualTreeHelper.GetDpi(viewport);
            var cornerBitmap = new RenderTargetBitmap((int)Math.Ceiling(viewport.ActualWidth * viewportDpi.DpiScaleX),
                (int)Math.Ceiling(viewport.ActualHeight * viewportDpi.DpiScaleY), viewportDpi.PixelsPerInchX, viewportDpi.PixelsPerInchY, PixelFormats.Pbgra32);
            cornerBitmap.Render(viewport);
            var corner = new byte[4];
            cornerBitmap.CopyPixels(new Int32Rect(3, 3, 1, 1), corner, 4, 0);
            Check(corner[0] < 170 && corner[1] < 170 && corner[2] < 170 && corner[3] == 255,
                "The rounded viewport corners share the account mask color instead of exposing the light window background");
            var sheet = UiNode<FrameworkElement>(window, "AccountSheet");
            var design = UiNode<FrameworkElement>(window, "DesignRoot");
            var sheetTop = sheet.TransformToAncestor(design).Transform(new Point()).Y;
            Check(Math.Abs(sheet.Height + sheetTop - WindowPreviewLogicalHeight) < 0.001 && Math.Abs(sheetTop * 449 / 410 - 389.0335975609755) < 0.001,
                "Account sheet preserves its Pen top anchor and fills the approved window height");
            var backgroundButton = UiNode<Button>(window, "CampusConnectionButton");
            Check(!backgroundButton.IsEnabled && ((Border)backgroundButton.Template.FindName("ButtonSurface", backgroundButton)).Opacity == 1,
                "Account background blocks interaction while the single 40 percent mask alone dims the cards");
            var selectedLabel = UiVisualDescendants(UiNode<ItemsControl>(window, "AccountList")).OfType<TextBlock>().Single(t => t.Text == "当前使用");
            Check(selectedLabel.Foreground == window.FindResource("Green"),
                "Current account uses the short green selection label from Pen without hiding status information in its tooltip");
            AccountGlyphBoundsChecks(window);
            var addButton = UiNode<FrameworkElement>(window, "AddAccountButton");
            var clientRoot = (FrameworkElement)window.Content;
            var addBounds = addButton.TransformToAncestor(clientRoot).TransformBounds(new Rect(new Point(), addButton.RenderSize));
            Check(addButton.IsVisible && addBounds.Top >= 0 && addBounds.Bottom <= clientRoot.ActualHeight,
                "Account add action remains reachable in the compact window");
            var plus = UiVisualDescendants(addButton).OfType<TextBlock>().Single(t => t.Text == "+");
            System.Windows.Input.Keyboard.ClearFocus();
            window.UpdateLayout();
            var plusBounds = plus.TransformToAncestor(addButton).TransformBounds(new Rect(new Point(), plus.RenderSize));
            Check(addButton.Focus() && addButton.IsKeyboardFocused, "Account add action supports actual keyboard focus");
            window.UpdateLayout();
            var focusedPlus = plus.TransformToAncestor(addButton).TransformBounds(new Rect(new Point(), plus.RenderSize));
            Check(Math.Abs(focusedPlus.Top - plusBounds.Top) < 0.01 && Math.Abs(focusedPlus.Height - plusBounds.Height) < 0.01
                && ((Button)addButton).Background == window.FindResource("SoftGreen"),
                "Focused account add action preserves its glyph position and soft-green fill");
            RenderPenPage(window, output, "pen-accounts");
            UiCall(window, "CloseAccounts");
            metrics.Add(CaptureActualWindow(window, output, "compact-restored-home"));
            File.WriteAllText(Path.Combine(output, "window-metrics.json"), JsonSerializer.Serialize(metrics, new JsonSerializerOptions { WriteIndented = true }));
            File.WriteAllText(Path.Combine(output, "preview-scope.json"), JsonSerializer.Serialize(new
            {
                PreviewMode = true, IsolatedDataDirectory = isolatedHome,
                SyntheticInput = new[] { "three preview accounts", "three summary events", "injected browser launcher" },
                PenHomeOnly = "temporarily uses the reference account label and reference trend geometry, then restores both",
                ActualWindowImages = "compact/enlarged/minimum captures preserve current account text and real trend state",
                AuthenticationCoreStarted = false, StartupRegistryActions = false
            }, new JsonSerializerOptions { WriteIndented = true }));
            var dialer = (DialerCoreProcess)typeof(MainWindow).GetField("_dialer", UiFlags)!.GetValue(window)!;
            Check(!dialer.IsRunning && !dialer.IsRequested && !File.Exists(AppPaths.HealthFile),
                "Pure UI preview never starts the authentication core");
        }
        finally
        {
            try { window?.ShutdownClient(); window?.Close(); app.Shutdown(); }
            finally { SynchronizationContext.SetSynchronizationContext(previousContext); }
        }
        async Task UiPreviewContinuation() { await Task.Yield(); }
        Check(UiPreviewContinuation().Wait(TimeSpan.FromSeconds(2)), "UI preview restores the synchronization context after closing");
        Console.WriteLine($"PASS: {_checks} pure UI checks; renders: {output}");
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct UiRect { public int Left, Top, Right, Bottom; }

    [StructLayout(LayoutKind.Sequential)]
    private struct UiMonitorInfo { public int Size; public UiRect Monitor, Work; public uint Flags; }

    private static UiRect UiWorkArea(IntPtr handle)
    {
        var info = new UiMonitorInfo { Size = Marshal.SizeOf<UiMonitorInfo>() };
        if (!UiGetMonitorInfo(UiMonitorFromWindow(handle, 2), ref info))
            throw new InvalidOperationException("无法读取测试窗口所在显示器的工作区。");
        return info.Work;
    }

    [DllImport("user32.dll", EntryPoint = "MonitorFromWindow")]
    private static extern IntPtr UiMonitorFromWindow(IntPtr window, uint flags);

    [DllImport("user32.dll", EntryPoint = "GetMonitorInfoW", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UiGetMonitorInfo(IntPtr monitor, ref UiMonitorInfo info);

    [DllImport("user32.dll", EntryPoint = "GetWindowRect", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UiGetWindowRect(IntPtr window, out UiRect rect);

    [DllImport("user32.dll", EntryPoint = "GetClientRect", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UiGetClientRect(IntPtr window, out UiRect rect);

    [DllImport("user32.dll", EntryPoint = "GetDpiForWindow")]
    private static extern uint UiGetDpiForWindow(IntPtr window);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongW")]
    private static extern int UiGetWindowLong(IntPtr window, int index);

    [DllImport("user32.dll", EntryPoint = "SendMessageW")]
    private static extern IntPtr UiSendMessage(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", EntryPoint = "SetWindowPos", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UiSetWindowPos(IntPtr window, IntPtr insertAfter, int x, int y, int width, int height, uint flags);
}
