using System.Diagnostics;
using System.IO;

namespace ESurfingDialerLite;

public sealed class DialerCoreProcess
{
    private readonly object _gate = new();
    private Process? _process;
    private int _generation;
    private bool _requested;
    private bool _enhanced = true;
    private int _crashStreak;
    private DateTimeOffset _startedAt;
    public event Action<string>? RecoveryMessage;
    public bool EnhancedConnection { get { lock (_gate) return _enhanced; } set { lock (_gate) _enhanced = value; } }
    public bool IsRequested { get { lock (_gate) return _requested; } }
    public bool IsRunning { get { lock (_gate) return _process is { HasExited: false }; } }
    public DateTimeOffset StartedAt { get { lock (_gate) return _startedAt; } }

    public Task StartAsync(ClientConfig config, string password, Action<string> log)
    {
        lock (_gate)
        {
            if (_requested) return Task.CompletedTask;
            if (_process is { HasExited: false }) throw new InvalidOperationException("旧认证核心尚未退出，请稍后再试。");
            if (string.IsNullOrWhiteSpace(config.UserName) || string.IsNullOrEmpty(password))
                throw new InvalidOperationException("请先填写账号和密码。");
            if (!File.Exists(AppPaths.CoreJar))
                throw new FileNotFoundException("未找到认证核心 core/client.jar，请使用完整客户端包。");
            _requested = true;
            _crashStreak = 0;
            var generation = ++_generation;
            try { StartProcess(config.UserName, password, generation, log); }
            catch { _requested = false; throw; }
        }
        return Task.CompletedTask;
    }

    private void StartProcess(string user, string password, int generation, Action<string> log)
    {
        Directory.CreateDirectory(AppPaths.LogsDirectory);
        Directory.CreateDirectory(AppPaths.DataDirectory);
        // Old health files must never authenticate a newly started process.
        if (File.Exists(AppPaths.HealthFile)) File.Delete(AppPaths.HealthFile);
        var info = new ProcessStartInfo
        {
            FileName = JavaLocator.FindJavaExe(), UseShellExecute = false,
            RedirectStandardOutput = true, RedirectStandardError = true, RedirectStandardInput = true, CreateNoWindow = true,
            WorkingDirectory = AppContext.BaseDirectory
        };
        foreach (var arg in new[] { "-jar", AppPaths.CoreJar, "-u", user, "-p", password, "-d", "--control-stdin" })
            info.ArgumentList.Add(arg);
        info.Environment["STATE_DIR"] = AppPaths.DataDirectory;
        info.Environment["AUTO_REAUTH_ENABLED"] = "0";
        var process = new Process { StartInfo = info, EnableRaisingEvents = true };
        process.OutputDataReceived += (_, e) => { if (e.Data != null) ClientLog.Write(AppPaths.CoreLogFile, e.Data); };
        process.ErrorDataReceived += (_, e) => { if (e.Data != null) ClientLog.Write(AppPaths.CoreLogFile, e.Data); };
        process.Exited += (_, _) => _ = HandleExitAsync(process, generation, user, password, log);
        _process = process;
        _startedAt = DateTimeOffset.UtcNow;
        try
        {
            process.Start();
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            log("认证核心已启动。");
        }
        catch
        {
            _process = null;
            // Output setup can fail after Start succeeds; do not leave an orphan core.
            try { if (!process.HasExited) { process.Kill(entireProcessTree: true); process.WaitForExit(2000); } }
            catch (InvalidOperationException) { }
            finally { process.Dispose(); }
            throw;
        }
    }

    public void Stop()
    {
        lock (_gate)
        {
            _requested = false;
            ++_generation;
            var process = _process;
            if (process == null) return;
            try
            {
                if (!process.HasExited)
                {
                    try { process.StandardInput.WriteLine("stop"); process.StandardInput.Flush(); } catch (IOException) { }
                    if (!process.WaitForExit(4000)) process.Kill(entireProcessTree: true);
                    if (!process.WaitForExit(2000))
                        throw new InvalidOperationException("认证核心仍在退出，请稍后再试。");
                }
                _process = null;
                process.Dispose();
            }
            catch (InvalidOperationException) when (process.HasExited) { _process = null; process.Dispose(); }
        }
    }

    private async Task HandleExitAsync(Process exited, int generation, string user, string password, Action<string> log)
    {
        await Task.Yield();
        lock (_gate)
        {
            if (_generation != generation || _process != exited) return;
            if (DateTimeOffset.UtcNow - _startedAt > TimeSpan.FromMinutes(2)) _crashStreak = 0;
            _crashStreak++;
            _process = null;
            exited.Dispose();
            log("认证核心已退出。");
            if (!_requested || !_enhanced) { _requested = false; return; }
            if (_crashStreak > 5) {
                _requested = false;
                RecoveryMessage?.Invoke("认证核心连续退出，请查看详细日志后手动连接。");
                return;
            }
        }
        for (var attempt = 1; attempt <= 5; attempt++)
        {
            var delay = Math.Min(60, 5 * (1 << Math.Min(4, attempt + _crashStreak - 2)));
            RecoveryMessage?.Invoke($"{delay} 秒后尝试重新启动认证核心。");
            log($"{delay} 秒后自动重启认证核心。");
            await Task.Delay(TimeSpan.FromSeconds(delay));
            lock (_gate)
            {
                // A stopped/switched account invalidates every pending restart.
                if (_generation != generation) return;
                if (!_requested || !_enhanced) { _requested = false; return; }
                try { StartProcess(user, password, generation, log); return; }
                catch (Exception ex) { log("自动重启失败：" + ex.Message); }
            }
        }
        lock (_gate) { if (_generation == generation) _requested = false; }
        RecoveryMessage?.Invoke("多次启动失败，请查看详细日志后手动连接。");
    }
}
