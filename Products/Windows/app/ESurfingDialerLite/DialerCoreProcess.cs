using System.Diagnostics;
using System.IO;

namespace ESurfingDialerLite;

public sealed class DialerCoreProcess
{
    private readonly object _gate = new();
    private readonly Action<Process> _startProcess;
    private readonly Action<Process> _terminateProcess;
    private readonly Func<int, TimeSpan>? _recoveryDelay;
    private Process? _process;
    private CoreProcessJob? _processJob;
    private int _generation;
    private bool _requested;
    private bool _enhanced = true;
    private bool _recovering;
    private bool _recoveryExhausted;
    private bool _coreStateInUse;
    private bool _autoReauthEnabled = true;
    private int _reauthStartHour = 4;
    private int _reauthEndHour = 6;
    private int _crashStreak;
    private DateTimeOffset _startedAt;
    private string? _instanceId;
    private CancellationTokenSource? _recoveryCancellation;
    public event Action<string>? RecoveryMessage;

    public DialerCoreProcess() : this(process => process.Start(), null) { }
    internal DialerCoreProcess(Action<Process> startProcess, Func<int, TimeSpan>? recoveryDelay, Action<Process>? terminateProcess = null)
    {
        _startProcess = startProcess;
        _recoveryDelay = recoveryDelay;
        _terminateProcess = terminateProcess ?? (process => process.Kill(entireProcessTree: true));
    }

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
                if (!value) _recoveryExhausted = false;
                process = _process is { HasExited: false } ? _process : null;
                if (!value && (_recovering || process == null))
                {
                    CancelPendingRecovery();
                    _recovering = false;
                    if (process == null) { _requested = false; ++_generation; }
                }
            }
            if (process != null) TrySendControl(process, value ? "enhanced on" : "enhanced off");
        }
    }
    public bool IsRequested { get { lock (_gate) return _requested; } }
    public bool IsRunning { get { lock (_gate) return _process is { HasExited: false }; } }
    public bool IsRecovering { get { lock (_gate) return _recovering; } }
    public bool RecoveryExhausted { get { lock (_gate) return _recoveryExhausted; } }
    public bool CoreStateInUse { get { lock (_gate) return _coreStateInUse; } }
    public DateTimeOffset StartedAt { get { lock (_gate) return _startedAt; } }
    public string? InstanceId { get { lock (_gate) return _instanceId; } }

    public Task StartAsync(ClientConfig config, string password, Action<string> log)
    {
        lock (_gate)
        {
            if (_requested) return Task.CompletedTask;
            if (_process is { HasExited: false }) throw new InvalidOperationException("旧认证核心尚未退出，请稍后再试。");
            ValidateCredentials(config.UserName, password);
            ConfigureDailyReauth(config.AutoReauthEnabled, config.ReauthStartHour, config.ReauthEndHour);
            _requested = true;
            _crashStreak = 0;
            _recoveryExhausted = false;
            _coreStateInUse = false;
            var generation = ++_generation;
            CancelPendingRecovery();
            _recoveryCancellation = new CancellationTokenSource();
            try { StartProcess(config.UserName, password, generation, log); }
            catch { _requested = false; CancelPendingRecovery(); throw; }
        }
        return Task.CompletedTask;
    }

    public Task<bool> RestartForRecoveryAsync(ClientConfig config, string password, Action<string> log)
    {
        lock (_gate)
        {
            if (!_requested || !_enhanced || _recovering) return Task.FromResult(false);
            ValidateCredentials(config.UserName, password);
            ConfigureDailyReauth(config.AutoReauthEnabled, config.ReauthStartHour, config.ReauthEndHour);
            // Unlike a manual stop, a health restart retains the user's connection intent.
            var generation = ++_generation;
            CancelPendingRecovery();
            _recoveryCancellation = new CancellationTokenSource();
            var token = _recoveryCancellation.Token;
            _recovering = true;
            var user = config.UserName;
            return Task.Run(() => RecoverAsync(generation, user, password, log, token,
                EnhancedConnectionPolicy.MaximumAutomaticRecoveries, 0));
        }
    }

    private static void ValidateCredentials(string user, string password)
    {
        if (string.IsNullOrWhiteSpace(user) || string.IsNullOrEmpty(password))
            throw new InvalidOperationException("请先填写账号和密码。");
        if (!File.Exists(AppPaths.CoreJar))
            throw new FileNotFoundException("未找到认证核心 core/client.jar，请使用完整客户端包。");
    }

    private void StartProcess(string user, string password, int generation, Action<string> log)
    {
        Directory.CreateDirectory(AppPaths.LogsDirectory);
        Directory.CreateDirectory(AppPaths.DataDirectory);
        var info = new ProcessStartInfo
        {
            FileName = JavaLocator.FindJavaExe(), UseShellExecute = false,
            RedirectStandardOutput = true, RedirectStandardError = true, RedirectStandardInput = true, CreateNoWindow = true,
            WorkingDirectory = AppContext.BaseDirectory
        };
        foreach (var arg in new[] { "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2", "-Xms16m", "-jar", AppPaths.CoreJar, "-u", user, "-p", password, "-d", "--control-stdin" })
            info.ArgumentList.Add(arg);
        _instanceId = Guid.NewGuid().ToString();
        AppPaths.UseHealthInstance(_instanceId);
        info.Environment["CORE_INSTANCE_ID"] = _instanceId;
        info.Environment["CORE_PARENT_PID"] = Environment.ProcessId.ToString();
        info.Environment["HEALTH_FILE_NAME"] = Path.GetFileName(AppPaths.HealthFile);
        info.Environment["STATE_DIR"] = AppPaths.DataDirectory;
        info.Environment["AUTO_REAUTH_ENABLED"] = _autoReauthEnabled ? "1" : "0";
        info.Environment["AUTO_REAUTH_SAFE_WINDOW_START_HOUR"] = _reauthStartHour.ToString();
        info.Environment["AUTO_REAUTH_SAFE_WINDOW_END_HOUR"] = _reauthEndHour.ToString();
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
            _processJob = new CoreProcessJob();
            _startProcess(process);
            _processJob.Assign(process);
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            SafeLog(log, "认证核心已启动。");
            SafeLog(log, $"认证核心归属：PID={process.Id}，实例={_instanceId}，健康文件={Path.GetFileName(AppPaths.HealthFile)}。");
        }
        catch (Exception startError)
        {
            var associated = false;
            try { _ = process.Id; associated = true; }
            catch (InvalidOperationException) { }
            try
            {
                if (associated && !process.HasExited)
                {
                    _terminateProcess(process);
                    if (!process.WaitForExit(2000)) throw new TimeoutException("启动失败的认证核心尚未退出。");
                }
            }
            catch (Exception cleanupError)
            {
                // Keep ownership until termination is confirmed; a new start must reject this live process.
                throw new AggregateException("启动失败且核心尚未清理，请停止旧核心后再试。", startError, cleanupError);
            }
            _process = null;
            ReleaseProcessJob();
            process.Dispose();
            throw;
        }
    }

    public void Stop()
    {
        lock (_gate)
        {
            _requested = false;
            ++_generation;
            _recovering = false;
            _recoveryExhausted = false;
            _coreStateInUse = false;
            CancelPendingRecovery();
            StopProcess();
        }
    }

    // All callers hold _gate. This deliberately does not alter the user's connection intent.
    private void StopProcess()
    {
        var process = _process;
        if (process == null) return;
        if (!process.HasExited)
        {
            TrySendControl(process, "stop");
            if (!process.WaitForExit(4000)) _terminateProcess(process);
            if (!process.WaitForExit(2000))
                throw new InvalidOperationException("认证核心仍在退出，请稍后再试。");
        }
        _process = null;
        ReleaseProcessJob();
        process.Dispose();
    }

    private void ReleaseProcessJob()
    {
        _processJob?.Dispose();
        _processJob = null;
    }

    public bool ConfigureDailyReauth(bool enabled, int startHour, int endHour)
    {
        if (startHour is < 0 or > 23 || endHour is < 0 or > 24 || endHour < startHour)
            throw new ArgumentException("重新登录结束时间不能早于起始时间，且必须在当天时间范围内。");
        lock (_gate)
        {
            _autoReauthEnabled = enabled;
            _reauthStartHour = startHour;
            _reauthEndHour = endHour;
            if (_process is { HasExited: false } process)
                return TrySendControl(process, $"reauth {(enabled ? "on" : "off")} {startHour} {endHour}");
            return true; // No live process: the next launch receives these settings in its environment.
        }
    }

    public bool RequestNetworkRecheck()
    {
        lock (_gate)
            return _requested && _process is { HasExited: false } process && TrySendControl(process, "network recheck");
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
        Task<bool> recovery;
        lock (_gate)
        {
            if (_generation != generation || _process != exited) return;
            var stateInUse = exited.ExitCode == 73;
            if (DateTimeOffset.UtcNow - _startedAt > TimeSpan.FromMinutes(2)) _crashStreak = 0;
            _crashStreak++;
            _process = null;
            ReleaseProcessJob();
            exited.Dispose();
            SafeLog(log, "认证核心已退出。");
            if (stateInUse)
            {
                _coreStateInUse = true;
                _requested = false;
                _recovering = false;
                _recoveryExhausted = true;
                CancelPendingRecovery();
                SafeLog(log, "认证核心状态目录已被占用，已暂停自动恢复，请退出旧客户端或旧核心后重新连接。");
                PublishRecovery("认证核心已被其他实例占用，请退出旧客户端或旧核心后重新连接。");
                return;
            }
            if (!_requested || !_enhanced || _crashStreak > 5)
            {
                _requested = false; _recovering = false; CancelPendingRecovery();
                _recoveryExhausted = _enhanced && _crashStreak > 5;
                if (_recoveryExhausted) PublishRecovery("认证核心连续退出，请查看详细日志后手动连接。");
                return;
            }
            _recoveryCancellation ??= new CancellationTokenSource();
            _recovering = true;
            recovery = RecoverAsync(generation, user, password, log, _recoveryCancellation.Token, 5, _crashStreak);
        }
        await recovery.ConfigureAwait(false);
    }

    private async Task<bool> RecoverAsync(int generation, string user, string password, Action<string> log,
        CancellationToken token, int maximumAttempts, int crashStreak)
    {
        for (var attempt = 1; attempt <= maximumAttempts; attempt++)
        {
            var delay = _recoveryDelay?.Invoke(attempt) ?? (crashStreak > 0
                ? TimeSpan.FromSeconds(Math.Min(60, 5 * (1 << Math.Min(4, attempt + crashStreak - 2))))
                : attempt == 1 ? TimeSpan.Zero : EnhancedConnectionPolicy.RecoveryCooldown(attempt - 1));
            if (delay > TimeSpan.Zero)
            {
                PublishRecovery($"{delay.TotalSeconds:0} 秒后尝试重新启动认证核心。");
                SafeLog(log, $"增强连接恢复计划：恢复次数={attempt}，冷却={delay.TotalSeconds:0}s。");
            }
            try { await Task.Delay(delay, token).ConfigureAwait(false); }
            catch (OperationCanceledException) { return false; }
            lock (_gate)
            {
                if (_generation != generation || token.IsCancellationRequested || !_requested || !_enhanced) return false;
                try
                {
                    StopProcess();
                    StartProcess(user, password, generation, log);
                    _recovering = false;
                    return true;
                }
                catch (Exception ex) { SafeLog(log, "自动重启失败：" + ex.Message); }
            }
        }
        lock (_gate)
        {
            if (_generation != generation || token.IsCancellationRequested) return false;
            _requested = false; _recovering = false; CancelPendingRecovery();
            _recoveryExhausted = true;
        }
        PublishRecovery("多次启动失败，请查看详细日志后手动连接。");
        return false;
    }

    private static void SafeLog(Action<string> log, string message)
    {
        try { log(message); }
        catch (Exception ex) { ClientLog.Write(AppPaths.ClientLogFile, "核心日志回调失败：" + ex.GetType().Name); }
    }

    private void PublishRecovery(string message)
    {
        var handlers = RecoveryMessage;
        if (handlers == null) return;
        foreach (Action<string> handler in handlers.GetInvocationList())
        {
            try { handler(message); }
            catch (Exception ex) { ClientLog.Write(AppPaths.ClientLogFile, "恢复通知回调失败：" + ex.GetType().Name); }
        }
    }
}
