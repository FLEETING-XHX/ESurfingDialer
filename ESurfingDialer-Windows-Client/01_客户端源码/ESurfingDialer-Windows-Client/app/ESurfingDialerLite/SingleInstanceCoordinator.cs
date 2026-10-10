using System.IO;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;

namespace ESurfingDialerLite;

/// <summary>Hold the data-directory lock until the owned core has stopped. No stale PID files.</summary>
public sealed class SingleInstanceCoordinator : IDisposable
{
    private FileStream? _ownership;
    private readonly EventWaitHandle _activation;
    private RegisteredWaitHandle? _listener;
    public bool IsOwner => _ownership != null;

    public SingleInstanceCoordinator(string directory, Action activate)
    {
        directory = Path.GetFullPath(directory);
        Directory.CreateDirectory(directory);
        var identity = WindowsIdentity.GetCurrent().User?.Value + "|" + Path.TrimEndingDirectorySeparator(directory).ToUpperInvariant();
        var key = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(identity)));
        _activation = new EventWaitHandle(false, EventResetMode.AutoReset, "Local\\ESurfingDialer-" + key);
        try
        {
            // File sharing exclusion also protects this directory across Windows sessions.
            _ownership = new FileStream(Path.Combine(directory, "client-instance.lock"), FileMode.OpenOrCreate,
                FileAccess.ReadWrite, FileShare.Read);
            _listener = ThreadPool.RegisterWaitForSingleObject(_activation, (_, _) => activate(), null,
                Timeout.Infinite, executeOnlyOnce: false);
        }
        catch (IOException ex) when ((ex.HResult & 0xffff) is 32 or 33)
        {
            _activation.Set();
        }
        catch { Dispose(); throw; }
    }

    public void Dispose()
    {
        _listener?.Unregister(null);
        _listener = null;
        _ownership?.Dispose();
        _ownership = null;
        _activation.Dispose();
    }
}
