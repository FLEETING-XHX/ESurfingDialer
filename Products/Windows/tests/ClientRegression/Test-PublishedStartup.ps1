param(
    [Parameter(Mandatory = $true)][string]$PublishedDirectory,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\..\artifacts\published-startup'),
    [ValidateRange(100, 60000)][int]$StartupTimeoutMilliseconds = 10000,
    [ValidateRange(100, 10000)][int]$ObservationMilliseconds = 3000,
    [ValidateRange(100, 30000)][int]$CleanupTimeoutMilliseconds = 5000
)
$ErrorActionPreference = 'Stop'

function Assert-StartupInputIdle($TestProcess, [int]$TimeoutMilliseconds) {
    if (-not $TestProcess.WaitForInputIdle($TimeoutMilliseconds)) {
        throw '客户端消息循环未在时限内就绪'
    }
}

function Stop-StartupTestProcess($TestProcess, [int]$TimeoutMilliseconds) {
    if ($null -eq $TestProcess) { return }
    try {
        if (-not $TestProcess.HasExited) {
            $TestProcess.Kill($true)
            if (-not $TestProcess.WaitForExit($TimeoutMilliseconds)) { throw '启动验证进程清理超时' }
        }
        $TestProcess.Refresh()
        if (-not $TestProcess.HasExited) { throw '启动验证进程仍未退出' }
    } finally {
        $TestProcess.Dispose()
    }
}

# Hidden WPF windows do not reliably appear in Process.MainWindowHandle.
# Match only this test PID, the WPF window class and the actual main-window title.
if (-not ('ESurfingStartupGate.WindowProbe' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
namespace ESurfingStartupGate {
    public static class WindowProbe {
        private delegate bool EnumWindow(IntPtr window, IntPtr parameter);
        [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindow callback, IntPtr parameter);
        [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr window, StringBuilder value, int count);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr window, StringBuilder value, int count);
        [DllImport("user32.dll", SetLastError = true)] private static extern IntPtr SendMessageTimeout(
            IntPtr window, uint message, UIntPtr wParam, IntPtr lParam, uint flags, uint timeout, out UIntPtr result);
        public static bool IsReady(int processId) {
            bool ready = false;
            EnumWindows((window, parameter) => {
                uint owner;
                GetWindowThreadProcessId(window, out owner);
                if (owner != processId) return true;
                var className = new StringBuilder(256);
                var title = new StringBuilder(256);
                GetClassName(window, className, className.Capacity);
                GetWindowText(window, title, title.Capacity);
                if (!className.ToString().StartsWith("HwndWrapper[", StringComparison.Ordinal)
                    || title.ToString() != "天翼校园 ESurfingDialer") return true;
                UIntPtr result;
                ready = SendMessageTimeout(window, 0, UIntPtr.Zero, IntPtr.Zero, 2, 500, out result) != IntPtr.Zero;
                return !ready;
            }, IntPtr.Zero);
            return ready;
        }
    }
}
'@
}

$published = (Resolve-Path -LiteralPath $PublishedDirectory).Path
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw "启动验证目录已存在，请使用新目录：$output" }
New-Item -ItemType Directory -Path $output | Out-Null
$profile = Join-Path $output 'isolated-data'
New-Item -ItemType Directory -Force -Path $profile | Out-Null
$configPath = Join-Path $profile 'config.json'
@{ AutoStart = $true; StartMinimized = $true; AutoConnect = $false; Accounts = @(); SelectedAccountId = $null } |
    ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding utf8
$configBefore = (Get-FileHash -LiteralPath $configPath).Hash
$info = [Diagnostics.ProcessStartInfo]::new((Join-Path $published 'ESurfingDialerLite.exe'))
$info.UseShellExecute = $false
$info.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
$info.WorkingDirectory = $published
$info.ArgumentList.Add('--minimized')
$info.Environment['ESURFING_CLIENT_HOME'] = $profile
$info.Environment['DOTNET_HOST_TRACE'] = '1'
$info.Environment['DOTNET_HOST_TRACEFILE'] = Join-Path $output 'runtime-host-trace.log'
$process = $null
$failure = $null
$cleanupFailure = $null
$stage = 'start'
$result = [ordered]@{
    Passed = $false; InputIdle = $false; WpfWindowReady = $false; CleanupSucceeded = $false; Pid = $null
    Version = [Diagnostics.FileVersionInfo]::GetVersionInfo($info.FileName).FileVersion
    ClientDllSha256 = (Get-FileHash -LiteralPath (Join-Path $published 'ESurfingDialerLite.dll')).Hash.ToLowerInvariant()
    AccountCount = 0; AutoConnect = $false; ConfigurationUnchanged = $false
    AuthenticationTested = $false; FailureStage = $null; FailureType = $null; CompletedAtUtc = $null
}
try {
    $process = [Diagnostics.Process]::Start($info)
    if ($null -eq $process) { throw '未创建启动验证进程' }
    $result.Pid = $process.Id
    $stage = 'input-idle'
    Assert-StartupInputIdle $process $StartupTimeoutMilliseconds
    $result.InputIdle = $true
    $stage = 'wpf-window'
    $wait = [Diagnostics.Stopwatch]::StartNew()
    while (-not [ESurfingStartupGate.WindowProbe]::IsReady($process.Id)) {
        if ($process.HasExited) { throw ('客户端提前退出：' + $process.ExitCode) }
        if ($wait.ElapsedMilliseconds -ge $StartupTimeoutMilliseconds) { throw 'WPF 主窗口未在时限内就绪' }
        Start-Sleep -Milliseconds 100
    }
    $stage = 'observation'
    Start-Sleep -Milliseconds $ObservationMilliseconds
    $process.Refresh()
    if ($process.HasExited) { throw ('客户端提前退出：' + $process.ExitCode) }
    if (-not [ESurfingStartupGate.WindowProbe]::IsReady($process.Id)) { throw '观察期后 WPF 主窗口不存在或无响应' }
    $result.WpfWindowReady = $true
    $stage = 'isolated-profile'
    $log = Join-Path $profile 'logs/client.log'
    if (-not (Test-Path -LiteralPath $log) -or (Get-Content -LiteralPath $log -Raw) -notmatch '客户端已启动') {
        throw '启动日志未生成'
    }
    if ((Get-FileHash -LiteralPath $configPath).Hash -ne $configBefore) { throw '无账户启动修改了测试配置' }
    if (Get-ChildItem -LiteralPath $profile -Recurse -Filter 'health.json') { throw '无账户启动意外创建核心健康状态' }
} catch {
    $failure = $_
    $result.FailureStage = $stage
    $result.FailureType = $_.Exception.GetType().FullName
}
finally {
    try {
        Stop-StartupTestProcess $process $CleanupTimeoutMilliseconds
        $result.CleanupSucceeded = $true
    } catch {
        $cleanupFailure = $_
        $result.FailureStage = 'cleanup'
        $result.FailureType = $_.Exception.GetType().FullName
    }
}
if ($null -eq $failure -and $null -eq $cleanupFailure) {
    try {
        if ((Get-FileHash -LiteralPath $configPath).Hash -ne $configBefore) { throw '清理后测试配置已改变' }
        if (Get-ChildItem -LiteralPath $profile -Recurse -Filter 'health.json') { throw '清理后发现意外核心健康状态' }
        $result.ConfigurationUnchanged = $true
        $result.Passed = $true
    } catch {
        $failure = $_
        $result.FailureStage = 'post-cleanup-profile'
        $result.FailureType = $_.Exception.GetType().FullName
    }
}
$result.CompletedAtUtc = [DateTimeOffset]::UtcNow
$result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
Get-Content -LiteralPath (Join-Path $output 'result.json')
if ($null -ne $cleanupFailure) { throw $cleanupFailure }
if ($null -ne $failure) { throw $failure }
