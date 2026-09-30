using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ESurfingDialerLite;

internal static partial class Program
{
    private static int _checks;
    [STAThread]
    private static int Main(string[] args) => RunHarness(args);

    private static void Run(string[] args)
    {
        if (args.Contains("--idle")) { Console.ReadLine(); return; }
        if (args.Length == 2 && args[0] == "--instance")
        {
            using var instance = new SingleInstanceCoordinator(args[1], () => { });
            Console.WriteLine(instance.IsOwner ? "owner" : "secondary");
            if (instance.IsOwner) Console.ReadLine();
            return;
        }
        if (args.Contains("--failure-fixture") || args.Contains("--dispatcher-failure-fixture"))
        {
            FailureFixture(args);
            return;
        }
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
        Check(EnhancedConnectionPolicy.HealthCheckInterval(true) < EnhancedConnectionPolicy.HealthCheckInterval(false), "Enhanced health checks are faster");
        Check(EnhancedConnectionPolicy.HealthCheckInterval(true) == TimeSpan.FromSeconds(5), "Enhanced health polling stays lightweight");
        Check(EnhancedConnectionPolicy.CoreNetworkCheckSeconds(true) == 5
              && EnhancedConnectionPolicy.CoreNetworkCheckSeconds(false) == 20, "Core network polling follows enhanced mode");
        Check(EnhancedConnectionPolicy.CoreHealthWriteSeconds(true) == 15
              && EnhancedConnectionPolicy.CoreHealthWriteSeconds(false) == 30, "Core health writes use a conservative cadence");
        Check(EnhancedConnectionPolicy.RecoveryCooldown(1) == TimeSpan.FromSeconds(30)
              && EnhancedConnectionPolicy.RecoveryCooldown(2) == TimeSpan.FromSeconds(60)
              && EnhancedConnectionPolicy.RecoveryCooldown(3) == TimeSpan.FromSeconds(120), "Enhanced recovery uses bounded backoff");
        var healthySnapshot = new HealthSnapshot
        {
            ProcessAlive = true, ClientThreadAlive = true, NetworkCheckThreadAlive = true, Authenticated = true,
            LastUpdatedAt = now.ToUnixTimeSeconds(), LastHeartbeatSuccessAt = now.ToUnixTimeSeconds(),
            AuthenticationStage = "heartbeat-wait", ClientProgressAgeMillis = 0, ClientProgressTimeoutMillis = 330_000
        };
        Check(EnhancedConnectionPolicy.RecoverableFailureReason(healthySnapshot, now.AddMinutes(-1), now) == null, "Healthy core does not trigger enhanced recovery");
        healthySnapshot.LastHeartbeatSuccessAt = now.AddMinutes(-4).ToUnixTimeSeconds();
        healthySnapshot.ClientProgressAgeMillis = 240_000;
        Check(healthySnapshot.IsConnectedFor(now.AddMinutes(-5), now), "Negotiated long heartbeat interval remains connected within its monotonic deadline");
        healthySnapshot.ClientProgressAgeMillis = 330_000;
        Check(!healthySnapshot.IsConnectedFor(now.AddMinutes(-5), now) &&
            EnhancedConnectionPolicy.RecoverableFailureReason(healthySnapshot, now.AddMinutes(-5), now) != null,
            "Expired heartbeat progress is neither connected nor ignored by recovery");
        healthySnapshot.ClientProgressAgeMillis = -1;
        Check(!healthySnapshot.IsConnectedFor(now.AddMinutes(-5), now), "Missing progress cannot certify an authenticated session");
        healthySnapshot.ClientProgressAgeMillis = 0;
        healthySnapshot.ClientProgressTimeoutMillis = 90_000;
        healthySnapshot.AuthenticationStage = null;
        healthySnapshot.LastHeartbeatSuccessAt = now.ToUnixTimeSeconds();
        healthySnapshot.Authenticated = false;
        healthySnapshot.LastHeartbeatSuccessAt = now.AddSeconds(-46).ToUnixTimeSeconds();
        Check(EnhancedConnectionPolicy.RecoverableFailureReason(healthySnapshot, now.AddMinutes(-1), now) == "认证状态持续丢失", "Sustained authentication loss triggers enhanced recovery");
        healthySnapshot.LastHeartbeatSuccessAt = now.AddMinutes(-11).ToUnixTimeSeconds();
        Check(EnhancedConnectionPolicy.RecoverableFailureReason(healthySnapshot, now.AddMinutes(-1), now) == "认证心跳长时间未确认", "Unauthenticated core with stale heartbeat triggers enhanced recovery");
        healthySnapshot.AuthenticationStage = "session";
        healthySnapshot.AuthenticationStageStartedAt = now.AddSeconds(-50).ToUnixTimeSeconds();
        healthySnapshot.ClientProgressAgeMillis = 50_000;
        Check(EnhancedConnectionPolicy.RecoverableFailureReason(healthySnapshot, now.AddMinutes(-2), now) == null,
            "In-flight authentication is not restarted before bounded stage grace");
        healthySnapshot.ProcessInstanceId = "current-run";
        Check(EnhancedConnectionPolicy.RecoverableFailureReason(healthySnapshot, now.AddMinutes(-2), now, "other-run") != null,
            "Another process instance cannot suppress recovery");
        healthySnapshot.AuthenticationStageStartedAt = now.AddHours(1).ToUnixTimeSeconds();
        Check(EnhancedConnectionPolicy.RecoverableFailureReason(healthySnapshot, now.AddMinutes(-2), now, "current-run") == null,
            "Wall-clock changes do not invalidate live monotonic operation progress");
        healthySnapshot.ClientProgressAgeMillis = 90_000;
        Check(EnhancedConnectionPolicy.RecoverableFailureReason(healthySnapshot, now.AddMinutes(-2), now) != null,
            "Hung authentication stage expires at ninety seconds");
        healthySnapshot.AuthenticationStageStartedAt = now.ToUnixTimeSeconds();
        healthySnapshot.ClientProgressAgeMillis = 0;
        healthySnapshot.AuthenticationStage = "unknown";
        Check(EnhancedConnectionPolicy.RecoverableFailureReason(healthySnapshot, now.AddMinutes(-2), now) != null,
            "Unknown stage cannot grant authentication grace");
        healthySnapshot.AuthenticationStage = "login";
        healthySnapshot.ClientThreadAlive = false;
        Check(EnhancedConnectionPolicy.RecoverableFailureReason(healthySnapshot, now.AddMinutes(-2), now) == "认证线程未运行",
            "Stage lease never hides a dead authentication thread");
        healthySnapshot.ClientThreadAlive = true;
        healthySnapshot.LastUpdatedAt = now.AddMinutes(-1).ToUnixTimeSeconds();
        Check(EnhancedConnectionPolicy.RecoverableFailureReason(healthySnapshot, now.AddMinutes(-2), now) == "健康状态缺失或已过期",
            "Stage lease never hides an expired health snapshot");
        healthySnapshot.LastUpdatedAt = now.ToUnixTimeSeconds();
        healthySnapshot.AuthenticationStage = null;
        healthySnapshot.AuthenticationFailure = "NATIVE_SESSION_REJECTED";
        healthySnapshot.AuthenticationFailureDeterministic = true;
        healthySnapshot.AuthenticationBlocked = true;
        Check(healthySnapshot.HasAuthenticationFailureFor(now.AddMinutes(-1), now), "Current protocol failure is visible to UI");
        Check(!healthySnapshot.HasAuthenticationFailureFor(now.AddSeconds(1), now), "Previous process protocol failure is rejected");
        Check(EnhancedConnectionPolicy.RecoverableFailureReason(healthySnapshot, now.AddMinutes(-1), now) == null,
            "Supervisor does not reset core protocol retry budget");
        healthySnapshot.AuthenticationFailureDeterministic = false;
        Check(EnhancedConnectionPolicy.RecoverableFailureReason(healthySnapshot, now.AddMinutes(-1), now) != null,
            "Transient authentication failure remains recoverable");
        healthySnapshot.AuthenticationFailureDeterministic = true;
        healthySnapshot.ClientThreadAlive = false;
        Check(EnhancedConnectionPolicy.RecoverableFailureReason(healthySnapshot, now.AddMinutes(-1), now) == "认证线程未运行",
            "Protocol failure does not suppress a dead thread");
        healthySnapshot.ClientThreadAlive = true;
        healthySnapshot.Authenticated = true;
        Check(!healthySnapshot.HasAuthenticationFailureFor(now.AddMinutes(-1), now), "Confirmed connection overrides old failure");
        ClientLog.SetSecrets(new[] { "old-regression-secret" });
        Check(ClientLog.Redact("old-regression-secret") == "[REDACTED]", "Current log secret is redacted");
        ClientLog.SetSecrets(new[] { "new-regression-secret" });
        Check(ClientLog.Redact("old-regression-secret") == "old-regression-secret"
              && ClientLog.Redact("new-regression-secret") == "[REDACTED]", "Log secret cache replaces stale values");

        var app = new System.Windows.Application();
        app.DispatcherUnhandledException += (_, e) => { _dispatcherFailure = e.Exception; e.Handled = true; };
        SynchronizationContext.SetSynchronizationContext(new System.Windows.Threading.DispatcherSynchronizationContext(app.Dispatcher));
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
            object? Call(string method, params object[] parameters) =>
                typeof(MainWindow).GetMethod(method, BindingFlags.NonPublic | BindingFlags.Instance)!.Invoke(window, parameters);
            T Node<T>(string name) => (T)window.FindName(name);
            var liveConfig = (ClientConfig)typeof(MainWindow).GetField("_config", BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(window)!;
            var healthTimer = (System.Windows.Threading.DispatcherTimer)typeof(MainWindow).GetField("_healthTimer", BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(window)!;
            var speedTimer = (System.Windows.Threading.DispatcherTimer)typeof(MainWindow).GetField("_speedTimer", BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(window)!;
            Check(!speedTimer.IsEnabled, "Speed sampling stays stopped while the window is hidden");
            window.Show();
            Call("ShowPage", Node<UIElement>("HomePage"));
            Check(speedTimer.IsEnabled, "Speed sampling starts on the visible home page");
            Call("ShowPage", Node<UIElement>("LogsPage"));
            Check(!speedTimer.IsEnabled, "Speed sampling stops away from the home page");
            Call("ShowPage", Node<UIElement>("HomePage"));
            window.Hide();
            Check(!speedTimer.IsEnabled, "Speed sampling stops again when the window is hidden");
            Check(healthTimer.Interval == EnhancedConnectionPolicy.EnhancedHealthCheckInterval, "Enhanced mode configures fast health polling");
            liveConfig.EnhancedConnection = false;
            Call("ApplySettings");
            Check(healthTimer.Interval == EnhancedConnectionPolicy.ConservativeHealthCheckInterval, "Disabled enhancement uses conservative health polling");
            liveConfig.EnhancedConnection = true;
            Call("ApplySettings");
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
            Check(Node<ContentControl>("CampusIconControl").Content == window.FindResource("PenCampusIcon"), "Campus card uses Pen icon");
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
            Call("UpdateStatus", "连接失败");
            Check(Node<TextBlock>("CampusStatusText").Text == "连接失败"
                  && Node<System.Windows.Shapes.Ellipse>("CampusStatusDot").Fill == window.FindResource("Coral"),
                "Authentication failure is visible on connection card");
            Call("UpdateStatus", "未连接");
            Render("home");
            var navIcons = new[] { "Home", "Logs", "Settings" }.Select(n => Node<ContentControl>(n + "NavIcon")).ToArray();
            var dpi = VisualTreeHelper.GetDpi(root);
            Check(navIcons.All(n => Math.Abs(n.ActualWidth - 21) <= 1 / dpi.DpiScaleX
                                   && Math.Abs(n.ActualHeight - 21) <= 1 / dpi.DpiScaleY),
                "Navigation icons share Pen dimensions");
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
            Check(window.FindName("EditAccountsButton") == null && Node<ItemsControl>("AccountList").Items.Cast<AccountRow>().All(x => x.Editing), "Account actions match latest Pen layout");
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
            Call("HandleSystemResume");
            var resumeField = typeof(MainWindow).GetField("_resumeRecoveryStartedAt", BindingFlags.NonPublic | BindingFlags.Instance)!;
            Check(resumeField.GetValue(window) == null, "Resume callback leaves manually disconnected client stopped");
            resumeField.SetValue(window, System.Diagnostics.Stopwatch.GetTimestamp());
            Check((bool)Call("IsResumeRecoveryGraceActive")!, "Resume recovery gate grants a bounded refresh window");
            resumeField.SetValue(window, System.Diagnostics.Stopwatch.GetTimestamp() - 21 * System.Diagnostics.Stopwatch.Frequency);
            Check(!(bool)Call("IsResumeRecoveryGraceActive")!, "Resume grace expires without extending on health polls");
            resumeField.SetValue(window, System.Diagnostics.Stopwatch.GetTimestamp() + System.Diagnostics.Stopwatch.Frequency);
            Check(!(bool)Call("IsResumeRecoveryGraceActive")!, "Future monotonic resume stamp does not suppress recovery");
            resumeField.SetValue(window, null);
            Call("Notify", "设置已保存");
            Render("notice");
            var notice = Node<Border>("Notice");
            Check(notice.HorizontalAlignment == HorizontalAlignment.Right && notice.ActualWidth < 300 && Node<TextBlock>("NoticeText").TextAlignment == TextAlignment.Right, "Notice fits content and aligns right");
            Call("OpenLogsButton_Click", window, new RoutedEventArgs()); Render("detailed-logs");
            WindowHealthChecks(window);
            PasswordChecks(window);
            StatusChecks(window, output);
            AccountFailureChecks(window);
            HarnessChecks(output);
            InstanceChecks(output);
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
    private static System.Diagnostics.Process Worker(params string[] arguments)
    {
        var info = new System.Diagnostics.ProcessStartInfo("dotnet") {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true
        };
        info.ArgumentList.Add(Assembly.GetExecutingAssembly().Location);
        foreach (var argument in arguments) info.ArgumentList.Add(argument);
        var process = System.Diagnostics.Process.Start(info)!;
        OwnedWorkers.TryAdd(process.Id, process.StartTime.ToUniversalTime());
        return process;
    }

    private static void InstanceChecks(string output)
    {
        using var activated = new ManualResetEventSlim();
        var directory = Path.Combine(output, "instance-fixture");
        using (var owner = new SingleInstanceCoordinator(directory, () => activated.Set()))
        {
            Check(owner.IsOwner, "First instance owns its configuration directory");
            using var second = Worker("--instance", directory);
            try {
                Check(second.WaitForExit(5000) && second.StandardOutput.ReadToEnd().Trim() == "secondary",
                    "Second process cannot own the same configuration");
            } finally { if (!second.HasExited) { second.Kill(entireProcessTree: true); second.WaitForExit(); } }
            Check(activated.Wait(3000), "Second launch signals existing instance activation");
            using var other = new SingleInstanceCoordinator(Path.Combine(output, "other-instance-fixture"), () => { });
            Check(other.IsOwner, "Independent configuration directories may run concurrently");
        }
        using var reopened = new SingleInstanceCoordinator(directory, () => { });
        Check(reopened.IsOwner, "Normal shutdown releases the instance lock");
    }

    private static void WindowHealthChecks(MainWindow window)
    {
        const BindingFlags flags = BindingFlags.NonPublic | BindingFlags.Instance;
        var dialer = (DialerCoreProcess)typeof(MainWindow).GetField("_dialer", flags)!.GetValue(window)!;
        var worker = Worker("--idle");
        var now = DateTimeOffset.UtcNow;
        typeof(DialerCoreProcess).GetField("_process", flags)!.SetValue(dialer, worker);
        typeof(DialerCoreProcess).GetField("_requested", flags)!.SetValue(dialer, true);
        typeof(DialerCoreProcess).GetField("_startedAt", flags)!.SetValue(dialer, now.AddMinutes(-2));
        typeof(DialerCoreProcess).GetField("_instanceId", flags)!.SetValue(dialer, "ui-fixture");
        var health = new HealthSnapshot { ProcessAlive = true, ProcessInstanceId = "ui-fixture", Authenticated = true,
            ClientThreadAlive = false, NetworkCheckThreadAlive = true, LastUpdatedAt = now.ToUnixTimeSeconds(),
            LastHeartbeatSuccessAt = now.ToUnixTimeSeconds(), AuthenticationStage = "heartbeat-wait",
            ClientProgressAgeMillis = 0, ClientProgressTimeoutMillis = 330_000 };
        Directory.CreateDirectory(AppPaths.DataDirectory);
        void Refresh() {
            File.WriteAllText(AppPaths.HealthFile, JsonSerializer.Serialize(health));
            ((Task)typeof(MainWindow).GetMethod("RefreshHealthAsync", flags)!.Invoke(window, null)!).GetAwaiter().GetResult();
        }
        int Count(string name) => (int)typeof(MainWindow).GetField(name, flags)!.GetValue(window)!;
        try
        {
            typeof(MainWindow).GetField("_nextRecoveryAllowedAt", flags)!.SetValue(window, now.AddHours(1));
            Refresh(); Refresh();
            Check(Count("_consecutiveUnhealthyChecks") == 2 &&
                (string)typeof(MainWindow).GetField("_currentStatus", flags)!.GetValue(window)! != "已连接",
                "Dead authenticated thread accumulates consecutive faults without reporting connected");
            typeof(MainWindow).GetField("_automaticRecoveryAttempts", flags)!.SetValue(window, EnhancedConnectionPolicy.MaximumAutomaticRecoveries);
            typeof(MainWindow).GetField("_nextRecoveryAllowedAt", flags)!.SetValue(window, DateTimeOffset.MinValue);
            Refresh();
            Check((bool)typeof(MainWindow).GetField("_automaticRecoveryExhausted", flags)!.GetValue(window)!,
                "Invalid authenticated snapshot does not clear recovery limit");
            health.ClientThreadAlive = true; health.NetworkCheckThreadAlive = false;
            Refresh();
            Check(Count("_consecutiveUnhealthyChecks") >= 4, "Dead network thread also preserves fault history");
            health.NetworkCheckThreadAlive = true; health.ProcessInstanceId = "another-process";
            Refresh();
            Check(Count("_consecutiveUnhealthyChecks") >= 5, "Other process health cannot reset recovery budget");
            health.ProcessInstanceId = "ui-fixture";
            Refresh();
            Check(Count("_consecutiveUnhealthyChecks") == 0 && Count("_automaticRecoveryAttempts") == 0,
                "Genuine healthy confirmation resets anomaly and recovery budgets");
        }
        finally { dialer.Stop(); }
    }

    private static void Check(bool condition, string name)
    {
        if (_dispatcherFailure != null) System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(_dispatcherFailure).Throw();
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
            core.EnhancedConnection = false;
            Wait(() => HealthSnapshot.Load() is { EnhancedConnection: false, NetworkCheckIntervalSeconds: 20, HealthWriteIntervalSeconds: 30 },
                "Running core switches to conservative cadence without restart");
            var unchangedProcess = Process().Id;
            var unchangedStart = core.StartedAt;
            Thread.Sleep(1200);
            var priorNetworkCheck = HealthSnapshot.Load()?.LastNetworkCheckAt ?? 0;
            var recheckWatch = System.Diagnostics.Stopwatch.StartNew();
            Check(core.RequestNetworkRecheck(), "Resume recheck accepted by a requested live core");
            Wait(() => (HealthSnapshot.Load()?.LastNetworkCheckAt ?? 0) > priorNetworkCheck,
                "Resume recheck wakes conservative core network monitoring");
            Check(recheckWatch.Elapsed < TimeSpan.FromSeconds(6) && Process().Id == unchangedProcess && core.StartedAt == unchangedStart,
                "Resume recheck refreshes health without process restart");
            core.EnhancedConnection = true;
            Wait(() => HealthSnapshot.Load() is { EnhancedConnection: true, NetworkCheckIntervalSeconds: 5, HealthWriteIntervalSeconds: 15 },
                "Running core restores enhanced cadence without restart");
            var first = core.StartedAt;
            Process().Kill(entireProcessTree: true);
            Wait(() => core.IsRunning && core.StartedAt > first, "Enhanced connection restarts a crashed core");
            Process().Kill(entireProcessTree: true);
            Wait(() => !core.IsRunning, "Crash observed before pending recovery");
            var cancellationField = typeof(DialerCoreProcess).GetField("_recoveryCancellation", BindingFlags.NonPublic | BindingFlags.Instance)!;
            var pendingToken = ((CancellationTokenSource)cancellationField.GetValue(core)!).Token;
            core.Stop();
            Check(pendingToken.IsCancellationRequested, "Manual stop cancels the pending recovery timer itself");
            Thread.Sleep(11200);
            Check(!core.IsRequested && !core.IsRunning, "Stop cancels delayed recovery");
            Check(!core.RequestNetworkRecheck(), "Resume recheck cannot restart a manually stopped core");
            core.StartAsync(config, PasswordProtector.Unprotect(config.ProtectedPassword), _ => { }).GetAwaiter().GetResult();
            Wait(() => core.IsRunning, "Core restarts for pending-recovery cancellation check");
            var modeCancellation = ((CancellationTokenSource)cancellationField.GetValue(core)!).Token;
            Process().Kill(entireProcessTree: true);
            Wait(() => !core.IsRunning, "Crash creates pending recovery before enhancement switch");
            core.EnhancedConnection = false;
            Check(modeCancellation.IsCancellationRequested && !core.IsRequested,
                "Disabling enhancement cancels pending timer and clears connection intent immediately");
            core.StartAsync(config, PasswordProtector.Unprotect(config.ProtectedPassword), _ => { }).GetAwaiter().GetResult();
            Wait(() => core.IsRunning, "Core can restart manually");
            Process().Kill(entireProcessTree: true);
            Wait(() => !core.IsRequested && !core.IsRunning, "Disabled enhancement does not restart");
            core.StartAsync(config, PasswordProtector.Unprotect(config.ProtectedPassword), _ => { }).GetAwaiter().GetResult();
            Wait(() => HealthSnapshot.Load()?.IsCurrentFor(core.StartedAt, DateTimeOffset.UtcNow) == true, "Fresh health after manual restart");
            core.Stop();
            Check(!core.IsRunning && !core.IsRequested, "Clean stop releases the core process");
            RecoveryStartFailureChecks(config);
        }
        finally
        {
            core.Stop(); cancellation.Cancel(); listener.Stop();
            Environment.SetEnvironmentVariable("NETWORK_CHECK_URLS", oldUrl);
            server.GetAwaiter().GetResult();
        }
    }

    private static void RecoveryStartFailureChecks(ClientConfig config)
    {
        var starts = 0;
        var failures = 0;
        using var failed = new ManualResetEventSlim();
        var holdRetry = false;
        var core = new DialerCoreProcess(process => {
            Interlocked.Increment(ref starts);
            if (Volatile.Read(ref failures) > 0) {
                Interlocked.Decrement(ref failures); failed.Set();
                throw new IOException("synthetic start failure");
            }
            process.Start();
        }, attempt => attempt == 1 ? TimeSpan.Zero : TimeSpan.FromMilliseconds(holdRetry ? 2000 : 100));
        var password = PasswordProtector.Unprotect(config.ProtectedPassword);
        try
        {
            core.StartAsync(config, password, _ => { }).GetAwaiter().GetResult();
            var originalId = core.InstanceId;
            failures = 1;
            Check(core.RestartForRecoveryAsync(config, password, _ => { }).GetAwaiter().GetResult()
                && starts == 3 && core.IsRequested && core.IsRunning && core.InstanceId != originalId,
                "Health restart retains intent and retries one failed start successfully with a new instance id");

            failures = 10; failed.Reset(); holdRetry = true;
            var cancelled = core.RestartForRecoveryAsync(config, password, _ => { });
            Check(failed.Wait(5000), "Recovery reaches failed-start backoff before manual stop");
            core.Stop();
            var beforeStop = starts;
            Check(!cancelled.GetAwaiter().GetResult() && !core.IsRequested && !core.IsRunning && starts == beforeStop,
                "Manual stop cancels failed-start retry without resurrecting the account");

            failures = 0;
            var switched = new ClientConfig { UserName = "switched-fixture" };
            core.StartAsync(switched, "switched-fixture-password", _ => { }).GetAwaiter().GetResult();
            var switchedId = core.InstanceId;
            Check(core.IsRequested && core.IsRunning && switchedId != originalId, "Manual account switch owns a fresh process after cancellation");
            failures = 10; failed.Reset();
            var disabled = core.RestartForRecoveryAsync(switched, "switched-fixture-password", _ => { });
            Check(failed.Wait(5000), "Recovery reaches failed-start backoff before disabling enhancement");
            core.EnhancedConnection = false;
            Check(!disabled.GetAwaiter().GetResult() && !core.IsRequested && !core.IsRunning,
                "Disabling enhancement cancels failed-start recovery immediately");

            core.EnhancedConnection = true; failures = 0; holdRetry = false;
            core.StartAsync(config, password, _ => { }).GetAwaiter().GetResult();
            var beforeLimit = starts;
            failures = 10;
            Check(!core.RestartForRecoveryAsync(config, password, _ => { }).GetAwaiter().GetResult()
                && starts - beforeLimit == EnhancedConnectionPolicy.MaximumAutomaticRecoveries
                && !core.IsRequested && !core.IsRunning, "Repeated startup failure exhausts bounded recovery and clears intent");
        }
        finally { core.Stop(); }
    }
}
