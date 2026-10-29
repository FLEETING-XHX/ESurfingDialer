param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$source = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$project = Join-Path $source 'app/ESurfingDialerLite/ESurfingDialerLite.csproj'
$output = if ($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) } else { Join-Path $source ('artifacts/core-jar-selection-' + [guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $output) { throw '测试输出已存在，请选择新目录。' }
New-Item -ItemType Directory -Path $output | Out-Null
$count = 0
function Check([bool]$Value, [string]$Name) {
    if (-not $Value) { throw $Name }
    $script:count++
    Write-Host "PASS: $Name"
}
$version = (& dotnet msbuild $project -getProperty:CoreVersion -nologo).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^\d+\.\d+\.\d+$') { throw '核心版本解析失败。' }
$fixture = Join-Path $output 'synthetic-libs'
New-Item -ItemType Directory -Path $fixture | Out-Null
$old = Join-Path $fixture 'ESurfingDialer-0.0.1-all.jar'
$current = Join-Path $fixture "ESurfingDialer-$version-all.jar"
[IO.File]::WriteAllText($old, 'synthetic old jar; never executed')
[IO.File]::WriteAllText($current, 'synthetic current jar; never executed')
function Resolve {
    $json = (& dotnet msbuild $project "-p:CoreJarDirectory=$fixture" -getItem:Content -nologo) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'MSBuild求值失败。' }
    @((ConvertFrom-Json $json).Items.Content | Where-Object Link -eq 'core\client.jar')
}
$items = @(Resolve)
Check ($items.Count -eq 1 -and $items[0].FullPath -eq $current) 'Old and current jars coexist but only the current version is mapped'
Check ((Get-Content -LiteralPath $old -Raw) -eq 'synthetic old jar; never executed') 'Historical jar is untouched'
& dotnet msbuild $project "-p:CoreJarDirectory=$fixture" -t:ValidateCurrentCoreJar -nologo > (Join-Path $output 'valid.log')
Check ($LASTEXITCODE -eq 0) 'Unique current jar passes validation'
Move-Item -LiteralPath $current -Destination ($current + '.retained')
& dotnet msbuild $project "-p:CoreJarDirectory=$fixture" -t:ValidateCurrentCoreJar -nologo > (Join-Path $output 'missing.log')
Check ($LASTEXITCODE -ne 0) 'Missing current version fails even while an old jar exists'
Move-Item -LiteralPath $old -Destination ($old + '.retained')
& dotnet msbuild $project "-p:CoreJarDirectory=$fixture" -t:ValidateCurrentCoreJar -nologo > (Join-Path $output 'empty.log')
Check ($LASTEXITCODE -ne 0) 'No jar fails explicitly instead of publishing an incomplete client'
Move-Item -LiteralPath ($current + '.retained') -Destination $current
$items = @(Resolve)
Check ($items.Count -eq 1 -and $items[0].FullPath -eq $current) 'Current-only directory selects exactly one jar'
@{ Passed = $true; Checks = $count; CoreVersion = $version } | ConvertTo-Json | Set-Content (Join-Path $output 'result.json') -Encoding utf8
