namespace ESurfingDialerLite;

public partial class App : System.Windows.Application
{
    private TrayController? _tray;

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
        _tray?.Dispose();
        base.OnExit(e);
    }
}
