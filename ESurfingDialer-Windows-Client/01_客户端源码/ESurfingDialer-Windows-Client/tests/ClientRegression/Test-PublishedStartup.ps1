param(
    [Parameter(Mandatory = $true)][string]$PublishedDirectory,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\..\artifacts\published-startup')
)
$ErrorActionPreference = 'Stop'
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
try {
    $process = [Diagnostics.Process]::Start($info)
    $ready = $process.WaitForInputIdle(10000)
    Start-Sleep -Seconds 3
    if ($process.HasExited) { throw ('客户端提前退出：' + $process.ExitCode) }
    $log = Join-Path $profile 'logs/client.log'
    if (-not (Test-Path -LiteralPath $log) -or (Get-Content -LiteralPath $log -Raw) -notmatch '客户端已启动') {
        throw '启动日志未生成'
    }
    if ((Get-FileHash -LiteralPath $configPath).Hash -ne $configBefore) { throw '无账户启动修改了测试配置' }
    if (Get-ChildItem -LiteralPath $profile -Recurse -Filter 'health.json') { throw '无账户启动意外创建核心健康状态' }
    [ordered]@{
        Passed = $true; InputIdle = $ready; Pid = $process.Id
        Version = [Diagnostics.FileVersionInfo]::GetVersionInfo($info.FileName).FileVersion
        ClientDllSha256 = (Get-FileHash -LiteralPath (Join-Path $published 'ESurfingDialerLite.dll')).Hash.ToLowerInvariant()
        AccountCount = 0; AutoConnect = $false; ConfigurationUnchanged = $true
        AuthenticationTested = $false; CompletedAtUtc = [DateTimeOffset]::UtcNow
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
}
finally {
    if ($process) {
        if (-not $process.HasExited) { $process.Kill($true); $process.WaitForExit(5000) | Out-Null }
        $process.Dispose()
    }
}
Get-Content -LiteralPath (Join-Path $output 'result.json')
