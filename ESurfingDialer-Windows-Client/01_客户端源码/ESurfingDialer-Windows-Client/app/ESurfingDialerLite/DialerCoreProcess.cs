using System.Diagnostics;
using System.IO;

namespace ESurfingDialerLite;

public sealed class DialerCoreProcess
{
    private Process? _process;
    private ClientConfig? _lastConfig;
    private string _lastPassword = "";
    private Action<string>? _log;
    private bool _stopRequested;

    public bool IsRunning => _process is { HasExited: false };

    public Task StartAsync(ClientConfig config, string password, Action<string> log)
    {
        if (_process is { HasExited: false })
        {
            log("认证核心已经在运行。");
            return Task.CompletedTask;
        }

        if (string.IsNullOrWhiteSpace(config.UserName) || string.IsNullOrWhiteSpace(password))
        {
            throw new InvalidOperationException("请先填写账号和密码。");
        }

        if (!File.Exists(AppPaths.CoreJar))
        {
            log("尚未找到认证核心 core/client.jar。当前先完成客户端壳开发，后续构建脚本会复制它。");
            return Task.CompletedTask;
        }

        _lastConfig = config;
        _lastPassword = password;
        _log = log;
        _stopRequested = false;

        StartProcess(config, password, log);
        return Task.CompletedTask;
    }

    private void StartProcess(ClientConfig config, string password, Action<string> log)
    {
        Directory.CreateDirectory(AppPaths.LogsDirectory);
        Directory.CreateDirectory(AppPaths.DataDirectory);

        var startInfo = new ProcessStartInfo
        {
            FileName = JavaLocator.FindJavaExe(),
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
        };
        startInfo.ArgumentList.Add("-jar");
        startInfo.ArgumentList.Add(AppPaths.CoreJar);
        startInfo.ArgumentList.Add("-u");
        startInfo.ArgumentList.Add(config.UserName);
        startInfo.ArgumentList.Add("-p");
        startInfo.ArgumentList.Add(password);
        startInfo.ArgumentList.Add("-d");

        startInfo.Environment["STATE_DIR"] = AppPaths.DataDirectory;

        _process = new Process { StartInfo = startInfo, EnableRaisingEvents = true };
        _process.OutputDataReceived += (_, e) => HandleCoreLine(e.Data, log);
        _process.ErrorDataReceived += (_, e) => HandleCoreLine(e.Data, log);
        _process.Exited += (_, _) => HandleExit();
        _process.Start();
        _process.BeginOutputReadLine();
        _process.BeginErrorReadLine();
        log("认证核心已启动。");
    }

    public void Stop()
    {
        _stopRequested = true;
        if (_process is not { HasExited: false }) return;
        try
        {
            _process.Kill(entireProcessTree: true);
            _process.Dispose();
        }
        catch
        {
            // Best-effort stop; the UI can keep running.
        }
        finally
        {
            _process = null;
        }
    }

    private void HandleCoreLine(string? data, Action<string> log)
    {
        if (string.IsNullOrWhiteSpace(data)) return;
        var line = $"[{DateTime.Now:HH:mm:ss}] {data}";
        File.AppendAllText(AppPaths.CoreLogFile, line + Environment.NewLine);
        log(data);
    }

    private async void HandleExit()
    {
        _log?.Invoke("认证核心已退出。");
        if (_stopRequested || _lastConfig == null || string.IsNullOrWhiteSpace(_lastPassword)) return;

        _log?.Invoke("5 秒后自动重启认证核心。");
        await Task.Delay(TimeSpan.FromSeconds(5));
        if (_stopRequested) return;

        try
        {
            StartProcess(_lastConfig, _lastPassword, _log ?? (_ => { }));
        }
        catch (Exception ex)
        {
            _log?.Invoke("自动重启失败: " + ex.Message);
        }
    }
}
