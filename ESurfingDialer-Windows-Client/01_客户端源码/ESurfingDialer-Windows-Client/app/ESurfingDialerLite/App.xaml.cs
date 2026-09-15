namespace ESurfingDialerLite;

public partial class App : System.Windows.Application
{
    private TrayController? _tray;

    public void SetTrayStatus(TrayStatus status, string tooltip)
    {
        _tray?.SetStatus(status, tooltip);
    }

    protected override void OnStartup(System.Windows.StartupEventArgs e)
    {
        base.OnStartup(e);
        ShutdownMode = System.Windows.ShutdownMode.OnExplicitShutdown;
        _tray = new TrayController();
        _tray.ShowRequested += (_, _) => MainWindow?.Show();
        _tray.ExitRequested += (_, _) => Shutdown();
    }

    protected override void OnExit(System.Windows.ExitEventArgs e)
    {
        (MainWindow as MainWindow)?.ShutdownClient();
        _tray?.Dispose();
        base.OnExit(e);
    }
}
