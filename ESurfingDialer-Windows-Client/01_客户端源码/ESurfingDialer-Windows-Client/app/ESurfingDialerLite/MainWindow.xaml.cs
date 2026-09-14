using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Navigation;
using System.Windows.Threading;

namespace ESurfingDialerLite;

public partial class MainWindow : Window
{
    private readonly ConfigStore _configStore = new();
    private readonly DialerCoreProcess _dialer = new();
    private readonly DispatcherTimer _healthTimer = new();
    private bool _exitRequested;
    private string _currentPassword = "";

    public MainWindow()
    {
        InitializeComponent();
        Directory.CreateDirectory(AppPaths.LogsDirectory);
        LoadConfig();
        AppendLog("客户端已启动。");

        _healthTimer.Interval = TimeSpan.FromSeconds(5);
        _healthTimer.Tick += (_, _) => RefreshHealth();
        _healthTimer.Start();

        Loaded += async (_, _) =>
        {
            var config = _configStore.Load();
            if (config.AutoConnect && !string.IsNullOrWhiteSpace(config.UserName) && !string.IsNullOrWhiteSpace(_currentPassword))
            {
                await StartDialerAsync();
            }

            if (config.StartMinimized || Environment.GetCommandLineArgs().Any(a => a.Equals("--minimized", StringComparison.OrdinalIgnoreCase)))
            {
                Hide();
            }
        };
    }

    private void LoadConfig()
    {
        var config = _configStore.Load();
        UserTextBox.Text = config.UserName;
        _currentPassword = PasswordProtector.Unprotect(config.ProtectedPassword);
        PasswordBox.Password = _currentPassword;
        AutoStartCheckBox.IsChecked = config.AutoStart;
        StartMinimizedCheckBox.IsChecked = config.StartMinimized;
        AutoConnectCheckBox.IsChecked = config.AutoConnect;
        UpdateStatus("未连接");
    }

    private void SaveButton_Click(object sender, RoutedEventArgs e)
    {
        SaveConfig();
        AppendLog("配置已保存。");
    }

    private async void ConnectButton_Click(object sender, RoutedEventArgs e)
    {
        SaveConfig();
        await StartDialerAsync();
    }

    private async Task StartDialerAsync()
    {
        UpdateStatus("启动中");
        AppendLog("准备启动认证核心。");

        try
        {
            await _dialer.StartAsync(_configStore.Load(), _currentPassword, AppendLog);
            UpdateStatus("运行中");
        }
        catch (Exception ex)
        {
            UpdateStatus("启动失败");
            AppendLog("启动失败: " + ex.Message);
        }
    }

    private void DisconnectButton_Click(object sender, RoutedEventArgs e)
    {
        _dialer.Stop();
        UpdateStatus("已断开");
        AppendLog("认证核心已停止。");
    }

    private void OpenLogsButton_Click(object sender, RoutedEventArgs e)
    {
        Directory.CreateDirectory(AppPaths.LogsDirectory);
        Process.Start(new ProcessStartInfo
        {
            FileName = AppPaths.LogsDirectory,
            UseShellExecute = true
        });
    }

    private void ExportLogsButton_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var zip = LogExporter.Export();
            AppendLog("日志包已导出: " + zip);
            Process.Start(new ProcessStartInfo
            {
                FileName = "explorer.exe",
                Arguments = "/select,\"" + zip + "\"",
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            AppendLog("导出日志失败: " + ex.Message);
        }
    }

    private void ExitButton_Click(object sender, RoutedEventArgs e)
    {
        _exitRequested = true;
        _dialer.Stop();
        System.Windows.Application.Current.Shutdown();
    }

    private void SourceLink_RequestNavigate(object sender, RequestNavigateEventArgs e)
    {
        Process.Start(new ProcessStartInfo
        {
            FileName = e.Uri.AbsoluteUri,
            UseShellExecute = true
        });
        e.Handled = true;
    }

    private void Window_Closing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (_exitRequested) return;
        e.Cancel = true;
        Hide();
        AppendLog("窗口已隐藏到托盘。");
    }

    private void SaveConfig()
    {
        var config = new ClientConfig
        {
            UserName = UserTextBox.Text.Trim(),
            ProtectedPassword = PasswordProtector.Protect(PasswordBox.Password),
            AutoStart = AutoStartCheckBox.IsChecked == true,
            StartMinimized = StartMinimizedCheckBox.IsChecked == true,
            AutoConnect = AutoConnectCheckBox.IsChecked == true
        };
        _currentPassword = PasswordBox.Password;
        _configStore.Save(config);
        StartupManager.SetEnabled(config.AutoStart);
    }

    private void UpdateStatus(string text)
    {
        StatusText.Text = text;
    }

    private void AppendLog(string message)
    {
        Dispatcher.Invoke(() =>
        {
            var line = $"[{DateTime.Now:HH:mm:ss}] {message}";
            LogTextBox.AppendText(line + Environment.NewLine);
            LogTextBox.ScrollToEnd();
            File.AppendAllText(AppPaths.ClientLogFile, line + Environment.NewLine);
        });
    }

    private void RefreshHealth()
    {
        var health = HealthSnapshot.Load();
        if (health == null)
        {
            HealthTextBlock.Text = _dialer.IsRunning ? "健康状态：等待核心写入 health.json" : "健康状态：核心未运行";
            return;
        }

        HealthTextBlock.Text = "健康状态：" + health.ToDisplayText();
        if (health.Authenticated)
        {
            UpdateStatus("已连接");
        }
        else if (_dialer.IsRunning)
        {
            UpdateStatus("运行中");
        }
    }
}
