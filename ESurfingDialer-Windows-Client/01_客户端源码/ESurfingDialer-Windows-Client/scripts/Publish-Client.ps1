$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$appProject = Join-Path $repoRoot "app\ESurfingDialerLite\ESurfingDialerLite.csproj"
$coreDir = Join-Path $repoRoot "core\ESurfingDialerCore"
$distDir = Join-Path $repoRoot "dist\ESurfingDialerLite"
$runtimeSource = Join-Path $repoRoot "dist\runtime"
$resourceReadme = Join-Path $repoRoot "resources\README_TEST.txt"

& (Join-Path $PSScriptRoot "Build-Core.ps1")
& (Join-Path $PSScriptRoot "Build-Runtime.ps1")

if (Test-Path $distDir) {
    Remove-Item -LiteralPath $distDir -Recurse -Force
}

dotnet publish $appProject -c Release -r win-x64 --self-contained true -o $distDir

$coreOut = Join-Path $distDir "core"
New-Item -ItemType Directory -Force -Path $coreOut | Out-Null

$jar = Get-ChildItem (Join-Path $coreDir "build\libs") -Filter "*-all.jar" | Select-Object -First 1
Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $coreOut "client.jar") -Force

Copy-Item -LiteralPath $runtimeSource -Destination (Join-Path $distDir "runtime") -Recurse -Force
Copy-Item -LiteralPath $resourceReadme -Destination (Join-Path $distDir "README_TEST.txt") -Force

Write-Host "Published to $distDir"
