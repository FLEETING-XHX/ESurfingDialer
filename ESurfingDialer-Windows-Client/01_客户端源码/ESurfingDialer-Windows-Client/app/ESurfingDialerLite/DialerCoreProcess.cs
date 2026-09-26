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
    private CancellationTokenSource? _recoveryCancellation;
    public event Action<string>? RecoveryMessage;
    public bool EnhancedConnection
    {
        get { lock (_gate) return _enhanced; }
        set
        {
            Process? process;
            lock (_gate)
            {
                if (_enhanced == value) return;
                _enhanced = value;
                process = _process is { HasExited: false } ? _process : null;
                if (!value && process == null)
                {
                    _requested = false;
                    ++_generation;
                    CancelPendingRecovery();
                }
            }
            if (process != null) TrySendControl(process, value ? "enhanced on" : "enhanced off");
        }
    }
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
            CancelPendingRecovery();
            _recoveryCancellation = new CancellationTokenSource();
            try { StartProcess(config.UserName, password, generation, log); }
            catch { _requested = false; CancelPendingRecovery(); throw; }
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
        foreach (var arg in new[] { "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2", "-Xms16m", "-jar", AppPaths.CoreJar, "-u", user, "-p", password, "-d", "--control-stdin" })
            info.ArgumentList.Add(arg);
        info.Environment["STATE_DIR"] = AppPaths.DataDirectory;
        info.Environment["AUTO_REAUTH_ENABLED"] = "0";
        info.Environment["ENHANCED_CONNECTION_ENABLED"] = _enhanced ? "1" : "0";
        info.Environment["ENHANCED_NETWORK_CHECK_INTERVAL_SECONDS"] = EnhancedConnectionPolicy.EnhancedCoreNetworkCheckSeconds.ToString();
        info.Environment["CONSERVATIVE_NETWORK_CHECK_INTERVAL_SECONDS"] = EnhancedConnectionPolicy.ConservativeCoreNetworkCheckSeconds.ToString();
        info.Environment["ENHANCED_HEALTH_WRITE_INTERVAL_SECONDS"] = EnhancedConnectionPolicy.EnhancedCoreHealthWriteSeconds.ToString();
        info.Environment["CONSERVATIVE_HEALTH_WRITE_INTERVAL_SECONDS"] = EnhancedConnectionPolicy.ConservativeCoreHealthWriteSeconds.ToString();
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
            CancelPendingRecovery();
            var process = _process;
            if (process == null) return;
            try
            {
                if (!process.HasExited)
                {
                    TrySendControl(process, "stop");
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

    public bool RequestNetworkRecheck()
    {
        lock (_gate)
        {
            return _requested && _process is { HasExited: false } process
                && TrySendControl(process, "network recheck");
        }
    }

    private static bool TrySendControl(Process process, string command)
    {
        try
        {
            if (process.HasExited) return false;
            process.StandardInput.WriteLine(command);
            process.StandardInput.Flush();
            return true;
        }
        catch (IOException) { }
        catch (ObjectDisposedException) { }
        catch (InvalidOperationException) { }
        return false;
    }

    // Called under _gate; cancel the timer itself rather than retaining a stale recovery until it expires.
    private void CancelPendingRecovery()
    {
        var cancellation = _recoveryCancellation;
        _recoveryCancellation = null;
        cancellation?.Cancel();
        cancellation?.Dispose();
    }

    private async Task HandleExitAsync(Process exited, int generation, string user, string password, Action<string> log)
    {
        await Task.Yield();
        CancellationToken recoveryToken;
        lock (_gate)
        {
            if (_generation != generation || _process != exited) return;
            if (DateTimeOffset.UtcNow - _startedAt > TimeSpan.FromMinutes(2)) _crashStreak = 0;
            _crashStreak++;
            _process = null;
            exited.Dispose();
            log("认证核心已退出。");
            if (!_requested || !_enhanced) { _requested = false; CancelPendingRecovery(); return; }
            if (_crashStreak > 5) {
                _requested = false;
                CancelPendingRecovery();
                RecoveryMessage?.Invoke("认证核心连续退出，请查看详细日志后手动连接。");
                return;
            }
            recoveryToken = _recoveryCancellation?.Token ?? CancellationToken.None;
            log($"增强连接检测到认证核心退出：检测时间={DateTimeOffset.UtcNow:O}，连续退出次数={_crashStreak}。");
        }
        for (var attempt = 1; attempt <= 5; attempt++)
        {
            var delay = Math.Min(60, 5 * (1 << Math.Min(4, attempt + _crashStreak - 2)));
            RecoveryMessage?.Invoke($"{delay} 秒后尝试重新启动认证核心。");
            log($"增强连接恢复计划：恢复次数={attempt}，冷却={delay}s。");
            try { await Task.Delay(TimeSpan.FromSeconds(delay), recoveryToken); }
            catch (OperationCanceledException) { return; }
            lock (_gate)
            {
                // A stopped/switched account invalidates every pending restart.
                if (_generation != generation) return;
                if (!_requested || !_enhanced) { _requested = false; CancelPendingRecovery(); return; }
                try { StartProcess(user, password, generation, log); return; }
                catch (Exception ex) { log("自动重启失败：" + ex.Message); }
            }
        }
        lock (_gate)
        {
            if (_generation == generation) { _requested = false; CancelPendingRecovery(); }
        }
        RecoveryMessage?.Invoke("多次启动失败，请查看详细日志后手动连接。");
    }
}
