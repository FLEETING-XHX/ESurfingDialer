$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$iscc = "C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
$script = Join-Path $repoRoot "installer\ESurfingDialerLite.iss"

if (-not (Test-Path $iscc)) {
    throw "Inno Setup compiler was not found at $iscc"
}

& (Join-Path $PSScriptRoot "Publish-Client.ps1")
& $iscc $script
if ($LASTEXITCODE -ne 0) {
    throw "Inno Setup build failed."
}

Write-Host "Installer output: $(Join-Path $repoRoot 'dist\ESurfingDialer-Lite-v1.01-Setup.exe')"
