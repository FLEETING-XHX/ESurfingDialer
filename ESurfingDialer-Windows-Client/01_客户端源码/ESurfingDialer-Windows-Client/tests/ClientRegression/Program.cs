using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ESurfingDialerLite;

internal static class Program
{
    private static int _checks;
    [STAThread]
    private static void Main(string[] args)
    {
        var output = Path.GetFullPath(args.FirstOrDefault() ?? "artifacts/ui");
        Directory.CreateDirectory(output);
        Environment.SetEnvironmentVariable("ESURFING_CLIENT_HOME", Path.Combine(output, "test-data"));
        Directory.CreateDirectory(AppPaths.AppDataDirectory);
        var secret = "regression-only-password";
        File.WriteAllText(AppPaths.ConfigFile, JsonSerializer.Serialize(new ClientConfig
        {
            UserName = "test-account-001", ProtectedPassword = PasswordProtector.Protect(secret), AutoConnect = false
        }));
        var store = new ConfigStore();
        var config = store.Load();
        Check(config.Accounts.Count == 1 && config.CurrentAccount?.UserName == "test-account-001", "Legacy account migration");
        Check(PasswordProtector.Unprotect(config.ProtectedPassword) == secret, "Password decryption preserved");
        config.Accounts.Add(new CampusAccount { Name = "备用校园网", UserName = "test-account-002", ProtectedPassword = PasswordProtector.Protect("alternate-only-password") });
        config.SelectedAccountId = config.Accounts.Last().Id;
        store.Save(config);
        Check(store.Load().UserName == "test-account-002", "Selected account persisted");
        Check(!File.ReadAllText(AppPaths.ConfigFile).Contains(secret), "No plaintext password in settings");
        var saved = File.ReadAllText(AppPaths.ConfigFile);
        File.WriteAllText(AppPaths.ConfigFile, "{broken");
        store.Load();
        var rejected = false;
        try { store.Save(config); } catch (InvalidOperationException) { rejected = true; }
        Check(rejected && File.ReadAllText(AppPaths.ConfigFile) == "{broken", "Unreadable settings are not overwritten");
        File.WriteAllText(AppPaths.ConfigFile, saved);
        store.Load();
        var now = DateTimeOffset.UtcNow;
        var health = new HealthSnapshot { ProcessAlive = true, Authenticated = true, LastUpdatedAt = now.AddMinutes(-2).ToUnixTimeSeconds(), LastHeartbeatSuccessAt = now.ToUnixTimeSeconds() };
        Check(!health.IsCurrentFor(now.AddMinutes(-1), now), "Previous process health rejected");
        health.LastUpdatedAt = now.ToUnixTimeSeconds();
        Check(health.IsCurrentFor(now.AddMinutes(-1), now) && health.HasRecentAuthentication(now), "Fresh health accepted");
        health.LastHeartbeatSuccessAt = now.AddMinutes(-20).ToUnixTimeSeconds();
        Check(!health.HasRecentAuthentication(now), "Stale heartbeat rejected");

        var app = new System.Windows.Application();
        var window = new MainWindow(previewMode: true);
        try
        {
            var root = (FrameworkElement)window.Content;
            void Render(string name)
            {
                root.Measure(new Size(484, 800)); root.Arrange(new Rect(0, 0, 484, 800)); root.UpdateLayout();
                var image = new RenderTargetBitmap(484, 800, 96, 96, PixelFormats.Pbgra32);
                image.Render(root);
                var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(image));
                using var stream = File.Create(Path.Combine(output, name + ".png")); encoder.Save(stream);
            }
            void Call(string method, params object[] parameters) =>
                typeof(MainWindow).GetMethod(method, BindingFlags.NonPublic | BindingFlags.Instance)!.Invoke(window, parameters);
            T Node<T>(string name) => (T)window.FindName(name);
            var liveConfig = (ClientConfig)typeof(MainWindow).GetField("_config", BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(window)!;
            var selected = liveConfig.CurrentAccount!;
            var remark = selected.Name;
            Check(Node<TextBlock>("AccountSummaryText").Text == remark, "Account summary prefers remark");
            selected.Name = "";
            Call("RefreshAccounts");
            Check(Node<TextBlock>("AccountSummaryText").Text == selected.UserName, "Account summary falls back to username");
            selected.Name = "   ";
            Call("RefreshAccounts");
            Check(Node<TextBlock>("AccountSummaryText").Text == selected.UserName, "Blank remark falls back to username");
            selected.Name = remark;
            Call("RefreshAccounts");
            var summary = Node<Border>("CurrentAccountCard");
            Check(!summary.Focusable, "Account summary is not an interactive button");
            summary.RaiseEvent(new System.Windows.Input.MouseButtonEventArgs(System.Windows.Input.Mouse.PrimaryDevice, 0, System.Windows.Input.MouseButton.Left) { RoutedEvent = UIElement.MouseLeftButtonDownEvent });
            Check(Node<UIElement>("AccountOverlay").Visibility == Visibility.Collapsed, "Account summary click does not open management");
            Check(Node<TextBlock>("DownloadSpeedText").Foreground == window.FindResource("Green") &&
                  Node<TextBlock>("UploadSpeedText").Foreground == window.FindResource("Coral"), "Download and upload use distinct semantic colors");
            Check(Node<TextBlock>("DownloadSpeedText").Foreground == Node<System.Windows.Shapes.Polyline>("DownloadLine").Stroke &&
                  Node<TextBlock>("UploadSpeedText").Foreground == Node<System.Windows.Shapes.Polyline>("UploadLine").Stroke, "Rate colors match trend lines");
            Render("home");
            Check(window.FindName("StatusDot") == null && window.FindName("StatusText") == null,
                "Duplicate header connection indicator removed");
            var valueNames = new[] { "AccountSummaryText", "CampusStatusText", "AuthenticationTitleText" };
            foreach (var name in valueNames)
            {
                var value = Node<TextBlock>(name);
                Check(double.IsNaN(value.LineHeight) && value.LineStackingStrategy == LineStackingStrategy.MaxHeight,
                    name + " uses natural font line height");
            }
            var originalFont = window.FontFamily;
            foreach (var font in new[] { "Noto Sans SC", "Microsoft YaHei UI" })
            {
                window.FontFamily = new FontFamily(font);
                Render(font == "Noto Sans SC" ? "home-noto" : "home-fallback");
                foreach (var name in valueNames)
                {
                    var value = Node<TextBlock>(name);
                    var natural = new TextBlock
                    {
                        Style = new Style(typeof(TextBlock)), Text = value.Text, FontFamily = value.FontFamily,
                        FontSize = value.FontSize, FontWeight = value.FontWeight, Padding = value.Padding
                    };
                    natural.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
                    var container = name switch
                    {
                        "AccountSummaryText" => Node<FrameworkElement>("CurrentAccountCard"),
                        "CampusStatusText" => Node<FrameworkElement>("CampusConnectionButton"),
                        _ => Node<FrameworkElement>("AuthenticationCard")
                    };
                    var top = value.TransformToAncestor(container).Transform(new Point()).Y;
                    Check(value.ActualHeight + 1 >= natural.DesiredSize.Height &&
                          top >= 0 && top + value.ActualHeight <= container.ActualHeight,
                        name + " fits natural glyph height and card: " + font);
                }
            }
            window.FontFamily = originalFont;
            Call("UpdateStatus", "已连接");
            Check(Node<System.Windows.Shapes.Ellipse>("CampusStatusDot").Fill == window.FindResource("Green"),
                "Campus button retains working connection indicator");
            Call("UpdateStatus", "未连接");
            Render("home");
            var navIcons = new[] { "Home", "Logs", "Settings" }.Select(n => Node<ContentControl>(n + "NavIcon")).ToArray();
            Check(navIcons.All(n => n.ActualWidth == 21 && n.ActualHeight == 21), "Navigation icons share Pen dimensions");
            var iconTop = navIcons[0].TransformToAncestor(root).Transform(new Point()).Y;
            Check(navIcons.All(n => Math.Abs(n.TransformToAncestor(root).Transform(new Point()).Y - iconTop) < 0.1), "Navigation icons share baseline");
            Check(Node<FrameworkElement>("SpeedChart").ActualWidth == 412 && Node<FrameworkElement>("SpeedChart").ActualHeight == 28, "Trend chart matches Pen geometry");
            Check(Node<ContentControl>("LogsNavIcon").Content == window.FindResource("PenLogIcon"), "Navigation uses exported Pen glyphs");
            Call("ShowPage", Node<UIElement>("LogsPage")); Render("logs");
            Check(Node<Button>("LogsNavButton").Foreground == window.FindResource("Coral"), "Navigation active color");
            Check(Node<ContentControl>("LogsNavIcon").Foreground == window.FindResource("Coral") &&
                  Node<Button>("LogsNavButton").FontWeight == FontWeights.Bold, "Selected navigation icon and label stay consistent");
            Call("ShowPage", Node<UIElement>("SettingsPage")); Render("settings-collapsed");
            Check(Node<Border>("MinimizeSettingRow").Visibility == Visibility.Collapsed, "Secondary setting hidden");
            liveConfig.AutoStart = true;
            Call("ApplySettings"); Render("settings-expanded");
            Check(Node<Border>("MinimizeSettingRow").Visibility == Visibility.Visible, "Secondary setting displayed");
            var bottom = Node<FrameworkElement>("SettingsPage");
            var settingPanel = (StackPanel)((Grid)bottom).Children[0];
            Check(settingPanel.DesiredSize.Height <= bottom.ActualHeight, "Expanded settings fit above navigation");
            Call("ShowPage", Node<UIElement>("HomePage"));
            Node<Button>("AccountManagementButton").RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Check(Node<UIElement>("AccountOverlay").Visibility == Visibility.Visible, "Management card still opens account sheet");
            Node<System.Windows.Media.TranslateTransform>("SheetTranslation").BeginAnimation(System.Windows.Media.TranslateTransform.YProperty, null);
            Render("accounts");
            Call("EditAccounts_Click", window, new RoutedEventArgs()); Render("accounts-edit");
            Check(Node<ItemsControl>("AccountList").Items.Cast<AccountRow>().All(x => x.Editing), "Edit actions enabled");
            Call("BeginAccountEdit", new object[] { null! }); Render("account-add");
            Node<TextBox>("UserTextBox").Text = "test-account-003";
            Node<PasswordBox>("PasswordBox").Password = "third-only-password";
            Node<TextBox>("AccountNameTextBox").Text = "新校园网";
            Call("SaveAccount_Click", window, new RoutedEventArgs());
            Check(store.Load().Accounts.Count == 3, "Account added and saved");
            Call("BeginAccountEdit", store.Load().Accounts.Last().Id);
            Node<TextBox>("AccountNameTextBox").Text = "修改后的账户";
            Call("SaveAccount_Click", window, new RoutedEventArgs());
            Check(store.Load().Accounts.Last().Name == "修改后的账户", "Account edited and saved");
            Call("CloseAccounts");
            Call("OpenLogsButton_Click", window, new RoutedEventArgs()); Render("detailed-logs");
            ClientLog.Write(AppPaths.CoreLogFile, "password=" + secret + " test-account-001 ticket=sample-ticket");
            var zip = LogExporter.Export(Path.Combine(output, "regression-logs.zip"));
            using var archive = ZipFile.OpenRead(zip);
            Check(archive.GetEntry("config.json") == null, "Credential configuration excluded from ZIP");
            Check(archive.GetEntry("core.log") != null, "Core logs exported");
            foreach (var entry in archive.Entries)
            {
                using var reader = new StreamReader(entry.Open());
                var text = reader.ReadToEnd();
                Check(!text.Contains(secret) && !text.Contains("test-account-001") && !text.Contains("sample-ticket"), "Export redaction: " + entry.FullName);
            }
        }
        finally { window.ShutdownClient(); window.Close(); app.Shutdown(); }
        if (args.Contains("--core")) SupervisorChecks(store.Load());
        Console.WriteLine($"PASS: {_checks} checks; WPF renders: {output}");
    }
    private static void Check(bool condition, string name)
    {
        if (!condition) throw new Exception("FAIL: " + name);
        _checks++; Console.WriteLine("PASS: " + name);
    }

    private static void SupervisorChecks(ClientConfig config)
    {
        Check(File.Exists(AppPaths.CoreJar), "Built core present for process tests");
        var listener = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0);
        listener.Start();
        var port = ((System.Net.IPEndPoint)listener.LocalEndpoint).Port;
        var oldUrl = Environment.GetEnvironmentVariable("NETWORK_CHECK_URLS");
        Environment.SetEnvironmentVariable("NETWORK_CHECK_URLS", $"http://127.0.0.1:{port}/generate_204");
        using var cancellation = new CancellationTokenSource();
        var server = Task.Run(async () =>
        {
            try
            {
                while (!cancellation.IsCancellationRequested)
                {
                    using var socket = await listener.AcceptTcpClientAsync(cancellation.Token);
                    using var stream = socket.GetStream();
                    using var reader = new StreamReader(stream, System.Text.Encoding.ASCII, leaveOpen: true);
                    while (!string.IsNullOrEmpty(await reader.ReadLineAsync(cancellation.Token))) { }
                    await stream.WriteAsync(System.Text.Encoding.ASCII.GetBytes("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"), cancellation.Token);
                }
            }
            catch (OperationCanceledException) { }
        });
        var core = new DialerCoreProcess();
        var processField = typeof(DialerCoreProcess).GetField("_process", BindingFlags.NonPublic | BindingFlags.Instance)!;
        System.Diagnostics.Process Process() => (System.Diagnostics.Process)processField.GetValue(core)!;
        void Wait(Func<bool> test, string name)
        {
            var timeout = System.Diagnostics.Stopwatch.StartNew();
            while (!test() && timeout.Elapsed < TimeSpan.FromSeconds(12)) Thread.Sleep(50);
            Check(test(), name);
        }
        try
        {
            core.StartAsync(config, PasswordProtector.Unprotect(config.ProtectedPassword), _ => { }).GetAwaiter().GetResult();
            Wait(() => core.IsRunning && HealthSnapshot.Load()?.IsCurrentFor(core.StartedAt, DateTimeOffset.UtcNow) == true, "Core startup creates fresh health");
            var first = core.StartedAt;
            Process().Kill(entireProcessTree: true);
            Wait(() => core.IsRunning && core.StartedAt > first, "Enhanced connection restarts a crashed core");
            Process().Kill(entireProcessTree: true);
            Wait(() => !core.IsRunning, "Crash observed before pending recovery");
            core.Stop();
            Thread.Sleep(11200);
            Check(!core.IsRequested && !core.IsRunning, "Stop cancels delayed recovery");
            core.EnhancedConnection = false;
            core.StartAsync(config, PasswordProtector.Unprotect(config.ProtectedPassword), _ => { }).GetAwaiter().GetResult();
            Wait(() => core.IsRunning, "Core can restart manually");
            Process().Kill(entireProcessTree: true);
            Wait(() => !core.IsRequested && !core.IsRunning, "Disabled enhancement does not restart");
            core.StartAsync(config, PasswordProtector.Unprotect(config.ProtectedPassword), _ => { }).GetAwaiter().GetResult();
            Wait(() => HealthSnapshot.Load()?.IsCurrentFor(core.StartedAt, DateTimeOffset.UtcNow) == true, "Fresh health after manual restart");
            core.Stop();
            Check(!core.IsRunning && !core.IsRequested, "Clean stop releases the core process");
        }
        finally
        {
            core.Stop(); cancellation.Cancel(); listener.Stop();
            Environment.SetEnvironmentVariable("NETWORK_CHECK_URLS", oldUrl);
            server.GetAwaiter().GetResult();
        }
    }
}
