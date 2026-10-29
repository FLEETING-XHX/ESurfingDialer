param(
    [string]$Jdk21 = "D:\Java\JDK21",
    [string]$OutputDirectory
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$distRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot "dist")) + [IO.Path]::DirectorySeparatorChar
$runtimeDir = if ($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) } else { Join-Path $repoRoot "dist\runtime" }
if (-not $runtimeDir.StartsWith($distRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "运行时输出必须位于本工程的 dist 目录内。"
}
$jlink = Join-Path $Jdk21 "bin\jlink.exe"

if (-not (Test-Path $jlink)) {
    throw "未找到 jlink：$jlink"
}

if (Test-Path -LiteralPath $runtimeDir) {
    throw "运行时输出已存在，保留原文件：$runtimeDir。请选择新的输出目录。"
}

& $jlink `
    --add-modules ALL-MODULE-PATH `
    --strip-debug `
    --no-header-files `
    --no-man-pages `
    --compress=2 `
    --output $runtimeDir

if ($LASTEXITCODE -ne 0) {
    throw "Java 运行时构建失败。"
}

Write-Host "Java 运行时输出：$runtimeDir"
