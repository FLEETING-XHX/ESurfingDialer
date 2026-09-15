using System.Drawing;
using System.IO;
using System.Windows.Forms;

namespace ESurfingDialerLite;

public sealed class TrayController : IDisposable
{
    private readonly NotifyIcon _notifyIcon;
    private readonly Dictionary<TrayStatus, Icon> _icons = new();

    public event EventHandler? ShowRequested;
    public event EventHandler? ExitRequested;

    public TrayController()
    {
        var menu = new ContextMenuStrip();
        menu.Items.Add("打开", null, (_, _) => ShowRequested?.Invoke(this, EventArgs.Empty));
        menu.Items.Add("退出", null, (_, _) => ExitRequested?.Invoke(this, EventArgs.Empty));

        _notifyIcon = new NotifyIcon
        {
            Text = "ESurfingDialer Lite",
            Visible = true,
            ContextMenuStrip = menu
        };
        SetStatus(TrayStatus.Idle, "ESurfingDialer Lite：未连接");
        _notifyIcon.DoubleClick += (_, _) => ShowRequested?.Invoke(this, EventArgs.Empty);
    }

    public void SetStatus(TrayStatus status, string tooltip)
    {
        var icon = GetIcon(status);
        _notifyIcon.Icon = icon;
        _notifyIcon.Text = tooltip.Length > 63 ? tooltip[..63] : tooltip;
    }

    private Icon GetIcon(TrayStatus status)
    {
        if (_icons.TryGetValue(status, out var icon)) return icon;

        var fileName = status switch
        {
            TrayStatus.Connecting => "ESurfingDialer-connecting.ico",
            TrayStatus.Connected => "ESurfingDialer-connected.ico",
            TrayStatus.Disconnected => "ESurfingDialer-disconnected.ico",
            TrayStatus.Error => "ESurfingDialer-error.ico",
            _ => "ESurfingDialer-default.ico"
        };
        var path = Path.Combine(AppContext.BaseDirectory, "resources", "icons", fileName);
        icon = File.Exists(path) ? new Icon(path) : SystemIcons.Application;
        _icons[status] = icon;
        return icon;
    }

    public void Dispose()
    {
        _notifyIcon.Visible = false;
        _notifyIcon.Dispose();
        foreach (var icon in _icons.Values) icon.Dispose();
    }
}

public enum TrayStatus
{
    Idle,
    Connecting,
    Connected,
    Disconnected,
    Error
}
