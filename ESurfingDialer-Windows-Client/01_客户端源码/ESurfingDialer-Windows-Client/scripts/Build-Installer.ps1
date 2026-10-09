param(
    [string]$OutputRoot,
    [string]$Jdk17 = "D:\Java\JDK17",
    [string]$Jdk21 = "D:\Java\JDK21",
    [switch]$Offline,
    [switch]$Regression
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$iscc = "C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
$script = Join-Path $repoRoot "installer\ESurfingDialerLite.iss"

if (-not (Test-Path $iscc)) {
    throw "未找到 Inno Setup 编译器：$iscc"
}

[xml]$project = Get-Content -LiteralPath (Join-Path $repoRoot "app\ESurfingDialerLite\ESurfingDialerLite.csproj")
$version = [string]$project.Project.PropertyGroup.InformationalVersion
$fileVersion = [string]$project.Project.PropertyGroup.FileVersion
$outputRoot = if ($OutputRoot) { [IO.Path]::GetFullPath($OutputRoot) } else { Join-Path $repoRoot "dist\v$version" }
& (Join-Path $PSScriptRoot "Publish-Client.ps1") -OutputRoot $outputRoot -Jdk17 $Jdk17 -Jdk21 $Jdk21 -Offline:$Offline -Regression:$Regression
& $iscc "/DMyAppVersion=$version" "/DMyAppFileVersion=$fileVersion" "/DSourceDir=$(Join-Path $outputRoot 'ESurfingDialerLite')" "/O$outputRoot" $script
if ($LASTEXITCODE -ne 0) {
    throw "Inno Setup 安装器构建失败。"
}

Write-Host "本地安装包：$(Join-Path $outputRoot "ESurfingDialer-Windows-v$version-Setup.exe")"
