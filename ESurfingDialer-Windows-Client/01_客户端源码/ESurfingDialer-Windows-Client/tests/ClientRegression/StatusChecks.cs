using System.IO;
using System.Reflection;
using System.Text.Json;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ESurfingDialerLite;

internal static partial class Program
{
    private static void StatusChecks(MainWindow window, string output)
    {
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic;
        var dialer = (DialerCoreProcess)typeof(MainWindow).GetField("_dialer", flags)!.GetValue(window)!;
        var worker = Worker("--idle");
        var now = DateTimeOffset.UtcNow;
        void SetCore(string name, object value) => typeof(DialerCoreProcess).GetField(name, flags)!.SetValue(dialer, value);
        void SetWindow(string name, object value) => typeof(MainWindow).GetField(name, flags)!.SetValue(window, value);
        void Refresh() => ((Task)typeof(MainWindow).GetMethod("RefreshHealthAsync", flags)!.Invoke(window, null)!).GetAwaiter().GetResult();
        void Expect(string title) => Check(((TextBlock)window.FindName("CampusStatusText")).Text == title
            && ((TextBlock)window.FindName("HomeStatusText")).Text == title, "Home and campus status agree: " + title);
        SetCore("_process", worker); SetCore("_requested", true); SetCore("_instanceId", "status-fixture"); SetCore("_startedAt", now);
        SetWindow("_nextRecoveryAllowedAt", now.AddHours(1));
        var health = new HealthSnapshot { ProcessAlive = true, ProcessInstanceId = "status-fixture",
            ClientThreadAlive = true, NetworkCheckThreadAlive = true, LastUpdatedAt = now.ToUnixTimeSeconds(),
            AuthenticationFailure = "SYNTHETIC_FAILURE", AuthenticationBlocked = true };
        try
        {
            File.WriteAllText(AppPaths.HealthFile, JsonSerializer.Serialize(health));
            Refresh(); Expect("认证已暂停");
            Check(ConnectionPresentation.For("认证已暂停").Tray == TrayStatus.Error
                && ((TextBlock)window.FindName("HomeStatusText")).ToolTip.ToString()!.Contains("重新连接"), "Paused state includes an actionable explanation and error tray");
            health.ProcessInstanceId = "old-core";
            File.WriteAllText(AppPaths.HealthFile, JsonSerializer.Serialize(health));
            Refresh(); Expect("连接中");
            SetCore("_recovering", true); Refresh(); Expect("正在恢复");
            Check(ConnectionPresentation.For("正在恢复").Tray == TrayStatus.Connecting, "Recovery uses connecting tray state");
            SetCore("_recovering", false); SetWindow("_automaticRecoveryExhausted", true);
            Refresh(); Expect("恢复已暂停");
            typeof(MainWindow).GetMethod("CloseDetails_Click", flags)!.Invoke(window, new object[] { window, new System.Windows.RoutedEventArgs() });
            typeof(MainWindow).GetMethod("ShowPage", flags)!.Invoke(window, new object[] { window.FindName("HomePage") });
            ((System.Windows.UIElement)window.FindName("Notice")).Visibility = System.Windows.Visibility.Collapsed;
            var root = (System.Windows.FrameworkElement)window.Content;
            root.Measure(new System.Windows.Size(484, 800)); root.Arrange(new System.Windows.Rect(0, 0, 484, 800)); root.UpdateLayout();
            var image = new RenderTargetBitmap(484, 800, 96, 96, PixelFormats.Pbgra32);
            image.Render(root);
            var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(image));
            using (var file = File.Create(Path.Combine(output, "status-paused.png"))) encoder.Save(file);
            SetWindow("_automaticRecoveryExhausted", false);
            dialer.Stop();
            typeof(MainWindow).GetMethod("UpdateStatus", flags)!.Invoke(window, new object[] { "用户已停止" });
            Refresh(); Expect("用户已停止");
            Check(((Button)window.FindName("CampusConnectionButton")).ToolTip.ToString()!.Contains("点击连接"), "Stopped state offers connect instead of disconnect");
            var text = new HealthSnapshot().ToDisplayText();
            Check(!text.Contains("False") && text.Contains("暂无有效记录"), "Health details use Chinese boolean and missing-time labels");
        }
        finally
        {
            dialer.Stop();
            typeof(MainWindow).GetMethod("ResetEnhancedRecoveryState", flags)!.Invoke(window, null);
            typeof(MainWindow).GetMethod("UpdateStatus", flags)!.Invoke(window, new object[] { "未连接" });
        }
    }
}
