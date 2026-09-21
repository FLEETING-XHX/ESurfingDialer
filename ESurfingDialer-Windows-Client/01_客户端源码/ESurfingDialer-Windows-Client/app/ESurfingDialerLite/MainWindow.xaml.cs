using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using Brush = System.Windows.Media.Brush;
using Button = System.Windows.Controls.Button;

namespace ESurfingDialerLite;

public partial class MainWindow : Window
{
    private readonly ConfigStore _configStore = new();
    private readonly DialerCoreProcess _dialer = new();
    private readonly DispatcherTimer _healthTimer = new();
    private readonly DispatcherTimer _speedTimer = new();
    private readonly DispatcherTimer _noticeTimer = new();
    private readonly NetworkSpeedSampler _speedSampler = new();
    private readonly ObservableCollection<SummaryEntry> _summaries = [];
    private ClientConfig _config = new();
    private string _currentStatus = "";
    private bool _editingAccounts;
    private string? _editingId;
    private bool _exitRequested;
    private bool _busy;
    private bool _disposed;
    private IInputElement? _previousFocus;
    private DateTimeOffset _lastRecovery = DateTimeOffset.MinValue;

    public MainWindow() : this(false) { }

    public MainWindow(bool previewMode)
    {
        InitializeComponent();
        Directory.CreateDirectory(AppPaths.LogsDirectory);
        _config = _configStore.Load();
        ApplySettings();
        RefreshAccounts();
        SummaryList.ItemsSource = _summaries;
        UpdateStatus("未连接");
        AddSummary("客户端已启动", "点击主页的校园网卡片连接。", "Offline");
        AppendLog("客户端已启动。");
        if (_configStore.LoadError != null) AppendLog("配置读取失败：" + _configStore.LoadError);
        _dialer.RecoveryMessage += message => Dispatcher.BeginInvoke(() =>
        {
            if (_disposed) return;
            AddSummary("自动恢复", message, "Coral");
        });
        _healthTimer.Interval = TimeSpan.FromSeconds(5);
        _healthTimer.Tick += async (_, _) => await RefreshHealthAsync();
        _healthTimer.Start();
        _speedTimer.Interval = TimeSpan.FromSeconds(1);
        _speedTimer.Tick += (_, _) => RefreshSpeed();
        _speedTimer.Start();
        _noticeTimer.Interval = TimeSpan.FromSeconds(4);
        _noticeTimer.Tick += (_, _) => { Notice.Visibility = Visibility.Collapsed; _noticeTimer.Stop(); };
        Loaded += async (_, _) =>
        {
            if (previewMode) return;
            if (_configStore.LoadError != null) Notify("原配置读取失败，已保留原文件。请查看详细日志。");
            var args = Environment.GetCommandLineArgs();
            var startup = args.Contains("--autostart", StringComparer.OrdinalIgnoreCase)
                || args.Contains("--minimized", StringComparer.OrdinalIgnoreCase);
            if (startup && _config.AutoStart && _config.StartMinimized) Hide();
            if (_config.AutoConnect && _config.CurrentAccount != null) await StartDialerAsync();
        };
    }

    private Brush Color(string key) => (Brush)FindResource(key);

    private void ApplySettings()
    {
        AutoStartCheckBox.IsChecked = _config.AutoStart;
        StartMinimizedCheckBox.IsChecked = _config.StartMinimized;
        AutoConnectCheckBox.IsChecked = _config.AutoConnect;
        EnhancedConnectionCheckBox.IsChecked = _config.EnhancedConnection;
        MinimizeSettingRow.Visibility = _config.AutoStart ? Visibility.Visible : Visibility.Collapsed;
        _dialer.EnhancedConnection = _config.EnhancedConnection;
    }

    private void SettingsChanged_Click(object sender, RoutedEventArgs e)
    {
        var previousAutoStart = _config.AutoStart;
        _config.AutoStart = AutoStartCheckBox.IsChecked == true;
        _config.StartMinimized = StartMinimizedCheckBox.IsChecked == true;
        _config.AutoConnect = AutoConnectCheckBox.IsChecked == true;
        _config.EnhancedConnection = EnhancedConnectionCheckBox.IsChecked == true;
        try
        {
            if (previousAutoStart != _config.AutoStart) StartupManager.SetEnabled(_config.AutoStart);
            _configStore.Save(_config);
            ApplySettings();
            Notify("设置已保存");
        }
        catch (Exception ex)
        {
            if (previousAutoStart != _config.AutoStart)
            {
                try { StartupManager.SetEnabled(previousAutoStart); } catch { }
            }
            _config = _configStore.Load();
            ApplySettings();
            Notify("保存设置失败：" + ex.Message);
        }
    }

    private async void CampusConnectionButton_Click(object sender, RoutedEventArgs e)
    {
        if (_busy) return;
        if (_dialer.IsRequested)
        {
            await StopConnectionAsync();
            return;
        }
        if (_config.CurrentAccount == null)
        {
            ShowAccounts();
            BeginAccountEdit(null);
            return;
        }
        await StartDialerAsync();
    }

    private async Task StartDialerAsync()
    {
        if (_busy || _disposed) return;
        _busy = true;
        CampusConnectionButton.IsEnabled = false;
        try
        {
            var password = PasswordProtector.Unprotect(_config.ProtectedPassword);
            if (string.IsNullOrWhiteSpace(_config.UserName) || string.IsNullOrEmpty(password))
                throw new InvalidOperationException("请添加账户并填写正确的账号和密码。");
            UpdateStatus("连接中");
            AppendLog("准备启动认证核心。");
            await _dialer.StartAsync(_config, password, AppendLog);
        }
        catch (Exception ex)
        {
            UpdateStatus("连接失败");
            AppendLog("启动失败：" + ex.Message);
            Notify("连接失败：" + ex.Message);
        }
        finally { _busy = false; CampusConnectionButton.IsEnabled = true; }
    }

    private async Task<bool> StopConnectionAsync()
    {
        if (_busy) return false;
        _busy = true;
        CampusConnectionButton.IsEnabled = false;
        AccountSheet.IsEnabled = false;
        try
        {
            await Task.Run(_dialer.Stop);
            UpdateStatus("已断开");
            AppendLog("用户停止认证核心。");
            return true;
        }
        catch (Exception ex) { Notify("停止失败：" + ex.Message); return false; }
        finally { _busy = false; CampusConnectionButton.IsEnabled = true; AccountSheet.IsEnabled = true; }
    }

    private async Task RefreshHealthAsync()
    {
        if (_disposed || _busy) return;
        var health = HealthSnapshot.Load();
        var now = DateTimeOffset.UtcNow;
        var usable = _dialer.IsRunning && health?.IsCurrentFor(_dialer.StartedAt, now) == true;
        HealthTextBlock.Text = _dialer.IsRunning
            ? health == null ? "核心运行中，等待健康状态。" : "核心状态：" + health.ToDisplayText()
            : "认证核心未运行。";
        if (usable && health!.Authenticated && health.HasRecentAuthentication(now))
            UpdateStatus("已连接");
        else if (_dialer.IsRequested)
            UpdateStatus("连接中");
        else if (_currentStatus is "已连接" or "连接中")
            UpdateStatus("已断开");

        // A captive portal alone must not restart a live core that is already recovering.
        if (_config.EnhancedConnection && _dialer.IsRunning
            && now - _dialer.StartedAt > TimeSpan.FromSeconds(120)
            && now - _lastRecovery > TimeSpan.FromSeconds(120)
            && (!usable || health is { ClientThreadAlive: false } || health is { NetworkCheckThreadAlive: false }))
        {
            _lastRecovery = now;
            AddSummary("恢复认证核心", "检测到核心无响应，正在重新启动。", "Coral");
            if (await StopConnectionAsync()) await StartDialerAsync();
        }
        if (DetailsOverlay.Visibility == Visibility.Visible) RefreshDetails();
    }

    private void UpdateStatus(string status)
    {
        if (_currentStatus == status) return;
        var previous = _currentStatus;
        _currentStatus = status;
        CampusStatusText.Text = HomeStatusText.Text = status;
        SpeedStatusText.Text = status == "已连接" ? "已连接" : status == "连接中" ? "正在连接" : "等待连接";
        var tone = status switch { "已连接" => "Green", "连接中" => "Coral", "连接失败" => "Coral", _ => "Offline" };
        var brush = Color(tone);
        BrandSignalBar1.Background = BrandSignalBar2.Background = BrandSignalBar3.Background = brush;
        CampusStatusDot.Fill = brush;
        SignalBar1.Background = SignalBar2.Background = SignalBar3.Background = brush;
        CampusConnectionButton.ToolTip = _dialer.IsRequested ? "点击断开校园网" : "点击连接校园网";
        var tray = status switch { "已连接" => TrayStatus.Connected, "连接中" => TrayStatus.Connecting, "连接失败" => TrayStatus.Error, _ => TrayStatus.Disconnected };
        (System.Windows.Application.Current as App)?.SetTrayStatus(tray, "天翼校园：" + status);
        if (status == "已连接") AddSummary("认证成功", "校园网认证和心跳已确认。", "Green");
        else if (status == "连接失败") AddSummary("连接失败", "请在设置中查看详细日志。", "Coral");
        else if (status == "连接中") AddSummary(previous == "已连接" ? "正在重新认证" : "正在连接", "正在检测校园网并验证当前账户。", "Coral");
        else if (previous.Length > 0) AddSummary("连接断开", "认证连接已停止。", "Offline");
        RefreshAccounts();
    }

    private void AddSummary(string title, string detail, string color)
    {
        _summaries.Insert(0, new SummaryEntry(DateTime.Now.ToString("MM-dd HH:mm:ss"), title, detail, Color(color)));
        while (_summaries.Count > 100) _summaries.RemoveAt(_summaries.Count - 1);
    }

    private void AppendLog(string message)
    {
        ClientLog.Write(AppPaths.ClientLogFile, message);
    }

    private void RefreshSpeed()
    {
        if (!IsVisible || HomePage.Visibility != Visibility.Visible) { _speedSampler.Reset(); return; }
        var sample = _speedSampler.Sample();
        DownloadSpeedText.Text = sample == null ? "-- KB/s" : NetworkSpeedSampler.Format(sample.Value.Download);
        UploadSpeedText.Text = sample == null ? "-- KB/s" : NetworkSpeedSampler.Format(sample.Value.Upload);
        DownloadLine.Points = _speedSampler.Points(true, Math.Max(1, SpeedChart.ActualWidth), Math.Max(1, SpeedChart.ActualHeight));
        UploadLine.Points = _speedSampler.Points(false, Math.Max(1, SpeedChart.ActualWidth), Math.Max(1, SpeedChart.ActualHeight));
    }

    private void ManageAccountsButton_Click(object sender, RoutedEventArgs e) => ShowAccounts();
    private void ShowAccounts()
    {
        _previousFocus = Keyboard.FocusedElement;
        _editingAccounts = true;
        ShowAccountList();
        AccountOverlay.Visibility = Visibility.Visible;
        MainContent.IsEnabled = false;
        AddAccountButton.Focus();
        if (SystemParameters.ClientAreaAnimation && Mouse.LeftButton == MouseButtonState.Released)
            SheetTranslation.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(530, 0, TimeSpan.FromMilliseconds(240)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
    }

    private void RefreshAccounts()
    {
        ClientLog.SetSecrets(_config.Accounts.SelectMany(a => new[] { a.UserName, PasswordProtector.Unprotect(a.ProtectedPassword) }));
        AccountSummaryText.Text = _config.CurrentAccount?.DisplayName ?? "尚未配置账户";
        AccountSummaryText.ToolTip = _config.CurrentAccount?.DisplayName;
        AccountList.ItemsSource = _config.Accounts.Select(a => new AccountRow(a.Id, a.DisplayName,
            a.Id == _config.SelectedAccountId ? "当前使用 · " + (_currentStatus.Length > 0 ? _currentStatus : "未连接") : "点击切换",
            _editingAccounts, a.Id == _config.SelectedAccountId ? Color("SoftGreen") : System.Windows.Media.Brushes.White,
            a.Id == _config.SelectedAccountId ? Color("Green") : System.Windows.Media.Brushes.Transparent)).ToList();
        AccountHint.Text = _config.Accounts.Count == 0 ? "还没有账户，点击右上角 ＋ 添加" : "点击账号即可切换当前使用的校园网账户";
    }

    private void ShowAccountList()
    {
        AccountEditor.Visibility = Visibility.Collapsed;
        AccountListScroll.Visibility = AccountActions.Visibility = Visibility.Visible;
        CancelAccountEditButton.Visibility = Visibility.Collapsed;
        SheetTitle.Text = "账户管理";
        PasswordBox.Clear();
        RefreshAccounts();
    }
    private void EditAccounts_Click(object sender, RoutedEventArgs e)
    {
        _editingAccounts = true;
        RefreshAccounts();
    }
    private void AddAccount_Click(object sender, RoutedEventArgs e) => BeginAccountEdit(null);
    private void EditAccount_Click(object sender, RoutedEventArgs e) => BeginAccountEdit((sender as Button)?.Tag as string);
    private void BeginAccountEdit(string? id)
    {
        _editingId = id;
        var account = _config.Accounts.FirstOrDefault(a => a.Id == id);
        SheetTitle.Text = account == null ? "添加账户" : "编辑账户";
        AccountNameTextBox.Text = account?.Name ?? "";
        UserTextBox.Text = account?.UserName ?? "";
        PasswordBox.Password = account == null ? "" : PasswordProtector.Unprotect(account.ProtectedPassword);
        AccountErrorText.Text = "";
        AccountListScroll.Visibility = AccountActions.Visibility = Visibility.Collapsed;
        AccountEditor.Visibility = CancelAccountEditButton.Visibility = Visibility.Visible;
        UserTextBox.Focus();
    }
    private void CancelAccountEdit_Click(object sender, RoutedEventArgs e) => ShowAccountList();

    private async void SaveAccount_Click(object sender, RoutedEventArgs e)
    {
        if (_busy) return;
        var username = UserTextBox.Text.Trim();
        if (username.Length == 0 || PasswordBox.Password.Length == 0)
        {
            AccountErrorText.Text = "请填写校园网账号和密码。";
            return;
        }
        if (_config.Accounts.Any(a => a.Id != _editingId && a.UserName == username))
        {
            AccountErrorText.Text = "这个账号已经添加，请直接编辑已有账户。";
            return;
        }
        var account = _config.Accounts.FirstOrDefault(a => a.Id == _editingId) ?? new CampusAccount();
        var wasCurrent = _config.SelectedAccountId == account.Id;
        var reconnect = wasCurrent && _dialer.IsRequested;
        try
        {
            var encrypted = PasswordProtector.Protect(PasswordBox.Password);
            if (reconnect && !await StopConnectionAsync()) return;
            account.UserName = username;
            account.Name = AccountNameTextBox.Text.Trim();
            account.ProtectedPassword = encrypted;
            if (!_config.Accounts.Contains(account)) _config.Accounts.Add(account);
            _config.SelectedAccountId ??= account.Id;
            _configStore.Save(_config);
            ShowAccountList();
            Notify("账户已保存");
            if (reconnect) await StartDialerAsync();
        }
        catch (Exception ex)
        {
            _config = _configStore.Load();
            RefreshAccounts();
            AccountErrorText.Text = "保存失败：" + ex.Message;
        }
    }

    private async void SelectAccount_Click(object sender, RoutedEventArgs e)
    {
        if (_busy || (sender as Button)?.Tag is not string id) return;
        if (_config.SelectedAccountId == id) { CloseAccounts(); return; }
        var reconnect = _dialer.IsRequested;
        if (reconnect && !await StopConnectionAsync()) return;
        _config.SelectedAccountId = id;
        try
        {
            _configStore.Save(_config);
            RefreshAccounts();
            CloseAccounts();
            AddSummary("账户已切换", "当前校园网账户已更新。", "Offline");
            if (reconnect) await StartDialerAsync();
        }
        catch (Exception ex) { _config = _configStore.Load(); RefreshAccounts(); Notify("切换失败：" + ex.Message); }
    }

    private async void DeleteAccount_Click(object sender, RoutedEventArgs e)
    {
        if (_busy) return;
        if ((sender as Button)?.Tag is not string id) return;
        var account = _config.Accounts.FirstOrDefault(a => a.Id == id);
        if (account == null) return;
        if (System.Windows.MessageBox.Show(this, $"删除账户“{account.DisplayName}”？删除当前账户会停止连接。", "删除账户", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        if (_config.SelectedAccountId == id && !await StopConnectionAsync()) return;
        _config.Accounts.Remove(account);
        if (_config.SelectedAccountId == id) _config.SelectedAccountId = _config.Accounts.FirstOrDefault()?.Id;
        try { _configStore.Save(_config); Notify("账户已删除"); }
        catch (Exception ex) { _config = _configStore.Load(); Notify("删除失败：" + ex.Message); }
        RefreshAccounts();
    }

    private void CloseAccounts()
    {
        SheetTranslation.BeginAnimation(TranslateTransform.YProperty, null);
        AccountOverlay.Visibility = Visibility.Collapsed;
        PasswordBox.Clear();
        MainContent.IsEnabled = true;
        _previousFocus?.Focus();
    }
    private void CloseSheet_Click(object sender, RoutedEventArgs e) => CloseAccounts();
    private void Overlay_MouseLeftButtonDown(object sender, MouseButtonEventArgs e) => CloseAccounts();
    private void Window_PreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key != Key.Escape) return;
        if (DetailsOverlay.Visibility == Visibility.Visible) CloseDetails_Click(sender, e);
        else if (AccountOverlay.Visibility == Visibility.Visible) CloseAccounts();
        e.Handled = true;
    }

    private void HomeNavButton_Click(object sender, RoutedEventArgs e) => ShowPage(HomePage);
    private void LogsNavButton_Click(object sender, RoutedEventArgs e) => ShowPage(LogsPage);
    private void SettingsNavButton_Click(object sender, RoutedEventArgs e) => ShowPage(SettingsPage);
    private void ShowPage(UIElement page)
    {
        HomePage.Visibility = page == HomePage ? Visibility.Visible : Visibility.Collapsed;
        LogsPage.Visibility = page == LogsPage ? Visibility.Visible : Visibility.Collapsed;
        SettingsPage.Visibility = page == SettingsPage ? Visibility.Visible : Visibility.Collapsed;
        HomeNavButton.Foreground = Color(page == HomePage ? "Coral" : "Muted");
        LogsNavButton.Foreground = Color(page == LogsPage ? "Coral" : "Muted");
        SettingsNavButton.Foreground = Color(page == SettingsPage ? "Coral" : "Muted");
    }

    private void OpenLogsButton_Click(object sender, RoutedEventArgs e)
    {
        _previousFocus = Keyboard.FocusedElement;
        DetailsOverlay.Visibility = Visibility.Visible;
        MainContent.IsEnabled = false;
        RefreshDetails();
        LogTextBox.Focus();
    }
    private void CloseDetails_Click(object sender, RoutedEventArgs e)
    {
        DetailsOverlay.Visibility = Visibility.Collapsed;
        MainContent.IsEnabled = true;
        _previousFocus?.Focus();
    }
    private void RefreshDetails_Click(object sender, RoutedEventArgs e) => RefreshDetails();
    private void RefreshDetails()
    {
        try
        {
            var text = "客户端日志（最近记录）" + Environment.NewLine + ClientLog.ReadTail(AppPaths.ClientLogFile)
                + Environment.NewLine + "认证核心日志（最近记录）" + Environment.NewLine + ClientLog.ReadTail(AppPaths.CoreLogFile);
            if (LogTextBox.Text == text) return;
            var follow = LogTextBox.VerticalOffset >= LogTextBox.ExtentHeight - LogTextBox.ViewportHeight - 2;
            LogTextBox.Text = text;
            if (follow) LogTextBox.ScrollToEnd();
        }
        catch (Exception ex) { LogTextBox.Text = "读取日志失败：" + ex.Message; }
    }

    private async void ExportLogsButton_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.SaveFileDialog { Filter = "ZIP 日志包 (*.zip)|*.zip", FileName = $"ESurfingDialer-logs-{DateTime.Now:yyyyMMdd-HHmmss}.zip", DefaultExt = ".zip" };
        if (dialog.ShowDialog(this) != true) return;
        var button = sender as Button;
        if (button != null) button.IsEnabled = false;
        try
        {
            await Task.Run(() => LogExporter.Export(dialog.FileName));
            Notify("日志 ZIP 已导出");
        }
        catch (Exception ex) { Notify("导出失败：" + ex.Message); }
        finally { if (button != null) button.IsEnabled = true; }
    }
    private void GitHubButton_Click(object sender, RoutedEventArgs e)
    {
        try { Process.Start(new ProcessStartInfo("https://github.com/FLEETING-XHX/ESurfingDialer") { UseShellExecute = true }); }
        catch (Exception ex) { Notify("无法打开浏览器：" + ex.Message); }
    }
    private void Notify(string message)
    {
        var isError = message.Contains("失败", StringComparison.Ordinal) || message.Contains("错误", StringComparison.Ordinal) || message.Contains("无法", StringComparison.Ordinal);
        var tone = isError ? "Coral" : "Green";
        Notice.Background = Color(isError ? "SoftCoral" : "SoftGreen");
        Notice.BorderBrush = Color(tone);
        NoticeText.Foreground = Color(tone);
        NoticeAccent.Fill = Color(tone);
        NoticeText.Text = message;
        Notice.Visibility = Visibility.Visible;
        _noticeTimer.Stop();
        _noticeTimer.Start();
    }
    private void Window_Closing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (_exitRequested) return;
        e.Cancel = true;
        Hide();
    }
    public void ShutdownClient()
    {
        if (_disposed) return;
        _disposed = true;
        _exitRequested = true;
        _healthTimer.Stop();
        _speedTimer.Stop();
        _noticeTimer.Stop();
        try { _dialer.Stop(); } catch (Exception ex) { AppendLog("退出时停止核心失败：" + ex.Message); }
    }
}

public sealed record SummaryEntry(string Time, string Title, string Detail, Brush Color);
public sealed record AccountRow(string Id, string Name, string Status, bool Editing, Brush Background, Brush DotColor);
