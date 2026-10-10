param(
    [string]$OutputRoot,
    [string]$Jdk17 = "D:\Java\JDK17",
    [string]$Jdk21 = "D:\Java\JDK21",
    [switch]$Offline,
    [switch]$Regression
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$appProject = Join-Path $repoRoot "app\ESurfingDialerLite\ESurfingDialerLite.csproj"
$coreDir = Join-Path $repoRoot "core\ESurfingDialerCore"
[xml]$project = Get-Content -LiteralPath $appProject
$version = [string]$project.Project.PropertyGroup.InformationalVersion
if ($version -notmatch '^[0-9A-Za-z][0-9A-Za-z.+-]*$') { throw "客户端版本号无效。" }
$outputRoot = if ($OutputRoot) { [IO.Path]::GetFullPath($OutputRoot) } else { Join-Path $repoRoot "dist\v$version" }
$distRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot "dist")) + [IO.Path]::DirectorySeparatorChar
if (-not $outputRoot.StartsWith($distRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "发布输出必须位于本工程的 dist 目录内。"
}
if (Test-Path -LiteralPath $outputRoot) { throw "发布目录已存在，保留原文件：$outputRoot。请选择新的输出目录。" }
$distDir = Join-Path $outputRoot "ESurfingDialerLite"
$runtimeSource = Join-Path $outputRoot "runtime"
$resourceReadme = Join-Path $repoRoot "resources\README_TEST.txt"

& (Join-Path $PSScriptRoot "Build-Core.ps1") -Jdk17 $Jdk17 -Jdk21 $Jdk21 -Offline:$Offline -Regression:$Regression
$jars = @(Get-ChildItem -LiteralPath (Join-Path $coreDir "build\libs") -Filter "*-all.jar")
if ($jars.Count -ne 1) { throw "认证核心 JAR 数量不是 1，无法确定应打包的文件。" }
New-Item -ItemType Directory -Path $outputRoot | Out-Null
& (Join-Path $PSScriptRoot "Build-Runtime.ps1") -Jdk21 $Jdk21 -OutputDirectory $runtimeSource

$publishArgs = @("publish", $appProject, "-c", "Release", "-r", "win-x64", "--self-contained", "true", "-o", $distDir)
if ($Offline) { $publishArgs += "--no-restore" }
& dotnet @publishArgs
if ($LASTEXITCODE -ne 0) { throw "客户端发布失败。" }

$coreOut = Join-Path $distDir "core"
New-Item -ItemType Directory -Force -Path $coreOut | Out-Null

Copy-Item -LiteralPath $jars[0].FullName -Destination (Join-Path $coreOut "client.jar") -Force

Copy-Item -LiteralPath $runtimeSource -Destination (Join-Path $distDir "runtime") -Recurse -Force
Copy-Item -LiteralPath $resourceReadme -Destination (Join-Path $distDir "README_TEST.txt") -Force

Write-Host "客户端发布目录：$distDir"
