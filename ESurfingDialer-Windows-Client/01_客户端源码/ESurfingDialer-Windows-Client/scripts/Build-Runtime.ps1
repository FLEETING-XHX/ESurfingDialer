$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $repoRoot "dist\runtime"
$jdk21 = "D:\Java\JDK21"
$jlink = Join-Path $jdk21 "bin\jlink.exe"

if (-not (Test-Path $jlink)) {
    throw "jlink was not found at $jlink"
}

if (Test-Path $runtimeDir) {
    Remove-Item -LiteralPath $runtimeDir -Recurse -Force
}

& $jlink `
    --add-modules ALL-MODULE-PATH `
    --strip-debug `
    --no-header-files `
    --no-man-pages `
    --compress=2 `
    --output $runtimeDir

if ($LASTEXITCODE -ne 0) {
    throw "jlink failed."
}

Write-Host "Runtime output: $runtimeDir"
