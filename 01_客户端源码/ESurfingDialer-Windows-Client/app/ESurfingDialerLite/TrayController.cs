using System.Drawing;
using System.Windows.Forms;

namespace ESurfingDialerLite;

public sealed class TrayController : IDisposable
{
    private readonly NotifyIcon _notifyIcon;

    public event EventHandler? ShowRequested;
    public event EventHandler? ExitRequested;

    public TrayController()
    {
        var menu = new ContextMenuStrip();
        menu.Items.Add("打开", null, (_, _) => ShowRequested?.Invoke(this, EventArgs.Empty));
        menu.Items.Add("退出", null, (_, _) => ExitRequested?.Invoke(this, EventArgs.Empty));

        _notifyIcon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "ESurfingDialer Lite",
            Visible = true,
            ContextMenuStrip = menu
        };
        _notifyIcon.DoubleClick += (_, _) => ShowRequested?.Invoke(this, EventArgs.Empty);
    }

    public void Dispose()
    {
        _notifyIcon.Visible = false;
        _notifyIcon.Dispose();
    }
}
