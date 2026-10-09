namespace ESurfingDialerLite;

public partial class App : System.Windows.Application
{
    private TrayController? _tray;
    private SingleInstanceCoordinator? _instance;
    private bool _backgroundTipsEnabled = true;

    public void SetBackgroundTipsEnabled(bool enabled) => _backgroundTipsEnabled = enabled;

    public void SetTrayStatus(TrayStatus status, string tooltip)
    {
        var background = MainWindow != null && (!MainWindow.IsVisible || MainWindow.WindowState == System.Windows.WindowState.Minimized);
        _tray?.SetStatus(status, tooltip, background && _backgroundTipsEnabled);
    }

    protected override void OnStartup(System.Windows.StartupEventArgs e)
    {
        base.OnStartup(e);
        ShutdownMode = System.Windows.ShutdownMode.OnExplicitShutdown;
        try
        {
            _instance = new SingleInstanceCoordinator(AppPaths.AppDataDirectory, () =>
            {
                if (Dispatcher.HasShutdownStarted) return;
                Dispatcher.BeginInvoke(() =>
                {
                    if (MainWindow == null) return;
                    MainWindow.Show();
                    if (MainWindow.WindowState == System.Windows.WindowState.Minimized)
                        MainWindow.WindowState = System.Windows.WindowState.Normal;
                    MainWindow.Activate();
                });
            });
            if (!_instance.IsOwner) { Shutdown(); return; }
        }
        catch (Exception ex)
        {
            System.Windows.MessageBox.Show("无法初始化客户端：" + ex.Message, "天翼校园");
            Shutdown(1);
            return;
        }
        _tray = new TrayController();
        _tray.ShowRequested += (_, _) =>
        {
            if (MainWindow == null) return;
            MainWindow.Show();
            if (MainWindow.WindowState == System.Windows.WindowState.Minimized)
                MainWindow.WindowState = System.Windows.WindowState.Normal;
            MainWindow.Activate();
        };
        _tray.ExitRequested += (_, _) => Shutdown();
        MainWindow = new MainWindow();
        MainWindow.Show();
    }

    protected override void OnExit(System.Windows.ExitEventArgs e)
    {
        (MainWindow as MainWindow)?.ShutdownClient();
        _tray?.Dispose();
        _instance?.Dispose();
        base.OnExit(e);
    }
}
