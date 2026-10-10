param(
    [string]$OutputRoot,
    [string]$ValidationDirectory,
    [string]$DeliveryDirectory,
    [string]$Jdk17 = 'D:\Java\JDK17',
    [string]$Jdk21 = 'D:\Java\JDK21',
    [switch]$Offline,
    [switch]$Release
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$sourceRoot = Split-Path -Parent $PSScriptRoot
$repository = [IO.Path]::GetFullPath((Join-Path $sourceRoot '../../..'))
[xml]$project = Get-Content -LiteralPath (Join-Path $sourceRoot 'app/ESurfingDialerLite/ESurfingDialerLite.csproj')
$version = [string]$project.Project.PropertyGroup.InformationalVersion
$fileVersion = [string]$project.Project.PropertyGroup.FileVersion
if ($Release) {
    if ($version -notmatch '^[0-9]+\.[0-9]+$') { throw '正式交付入口需要主版本.次版本格式。' }
} elseif ($version -notmatch '^[0-9]+\.[0-9]+beta\.[0-9]+$') { throw '正式版本请使用 Build-Release.ps1。' }
$runId = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
$output = if ($OutputRoot) { [IO.Path]::GetFullPath($OutputRoot) } else { Join-Path $sourceRoot "dist/v$version" }
$validation = if ($ValidationDirectory) { [IO.Path]::GetFullPath($ValidationDirectory) } else { Join-Path $sourceRoot "artifacts/local-beta-$version-$runId" }
$deliveryBase = Join-Path $repository 'ESurfingDialer-Windows-Client/00_最终安装包'
$delivery = if ($DeliveryDirectory) { [IO.Path]::GetFullPath($DeliveryDirectory) } else { Join-Path $deliveryBase "Windows客户端_v$version" }
function Assert-NewChild([string]$Path, [string]$Parent) {
    $prefix = [IO.Path]::GetFullPath($Parent).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $Path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "输出目录必须位于指定根目录内：$Parent" }
    if (Test-Path -LiteralPath $Path) { throw "保留已有目录，请选择新的输出目录：$Path" }
}
Assert-NewChild $output (Join-Path $sourceRoot 'dist')
Assert-NewChild $validation (Join-Path $sourceRoot 'artifacts')
Assert-NewChild $delivery $deliveryBase
$shell = (Get-Process -Id $PID).Path
function Invoke-Logged([string]$Command, [string[]]$Arguments, [string]$LogName) {
    & $Command @Arguments 2>&1 | Tee-Object -FilePath (Join-Path $validation $LogName) | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "验证命令失败，退出码 $LASTEXITCODE，参见 $LogName" }
}
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Save-Json($Value, [string]$Path) { $Value | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Path -Encoding utf8 }
function Source-Inventory {
    $paths = @(git -c core.quotePath=false ls-files --cached --others --exclude-standard -- app core scripts installer resources tests | Sort-Object -Unique)
    if ($LASTEXITCODE -ne 0 -or $paths.Count -eq 0) { throw '无法枚举构建源码输入。' }
    @($paths | ForEach-Object {
        $exists = Test-Path -LiteralPath $_ -PathType Leaf
        [ordered]@{ Path = $_.Replace('\', '/'); Exists = $exists; Sha256 = $(if ($exists) { Hash $_ } else { $null }) }
    })
}
function Protected-Inventory {
    $paths = @(git -c core.quotePath=false ls-files -- 'ESurfingDialer-Docker/01_Docker源码')
    if ($LASTEXITCODE -ne 0) { throw '无法枚举Docker保护范围。' }
    $paths += @(Get-ChildItem -LiteralPath $deliveryBase -Recurse -File | ForEach-Object { [IO.Path]::GetRelativePath($repository, $_.FullName) })
    @($paths | Sort-Object -Unique | ForEach-Object {
        $absolute = Join-Path $repository $_
        $exists = Test-Path -LiteralPath $absolute -PathType Leaf
        [ordered]@{ Path = $_.Replace('\', '/'); Exists = $exists; Sha256 = $(if ($exists) { Hash $absolute } else { $null }) }
    })
}
function Check-Links {
    $docs = @('README.md', 'core/ESurfingDialerCore/README.md', 'core/ESurfingDialerCore/CHANGELOG.md', 'tests/ClientRegression/README.md') | ForEach-Object { Join-Path $sourceRoot $_ }
    $docs += @('README.md', '修复与优化总记录.md', 'WINDOWS-BOOTSTRAP-UA-FIX-2026-10-08.md', 'WINDOWS-AUTH-SESSION-ISSUE.md', 'WINDOWS-STABILITY-AND-MIGRATION-BASELINE.md') | ForEach-Object { Join-Path $repository "项目文档/$_" }
    $count = 0
    foreach ($doc in $docs) {
        if (-not (Test-Path -LiteralPath $doc)) { throw "文档缺失：$doc" }
        foreach ($match in [regex]::Matches((Get-Content -LiteralPath $doc -Raw), '\]\(([^)]+)\)')) {
            $target = $match.Groups[1].Value.Trim('<', '>')
            if ($target -match '^[A-Za-z]+:' -or $target.StartsWith('#')) { continue }
            $target = [Uri]::UnescapeDataString(($target -split '#', 2)[0])
            if (-not (Test-Path -LiteralPath (Join-Path (Split-Path -Parent $doc) $target))) { throw "文档链接缺失：$doc -> $target" }
            $count++
        }
    }
    [ordered]@{ Documents = $docs.Count; LocalLinks = $count; Passed = $true }
}

Push-Location $sourceRoot
try {
    New-Item -ItemType Directory -Path $validation | Out-Null
    $buildHead = (git rev-parse HEAD).Trim()
    $branch = (git branch --show-current).Trim()
    if ($branch -notlike 'codex/windows-*') { throw 'Windows交付必须使用Windows专属分支。' }
    $inputs = @(Source-Inventory)
    Save-Json $inputs (Join-Path $validation 'source-before.json')
    Push-Location $repository
    try { $protected = @(Protected-Inventory) }
    finally { Pop-Location }
    Save-Json $protected (Join-Path $validation 'protected-before.json')
    Invoke-Logged $shell @('-NoProfile', '-File', (Join-Path $sourceRoot 'tests/ClientRegression/Test-StartupGateRegression.ps1'), '-OutputDirectory', (Join-Path $validation 'startup-gate-regression')) 'startup-gate-regression.log'
    $startupGate = Get-Content -LiteralPath (Join-Path $validation 'startup-gate-regression/result.json') -Raw | ConvertFrom-Json
    if (-not $startupGate.Passed -or $startupGate.Checks -lt 13) { throw '启动门禁自身回归未通过。' }
    $feed = Join-Path $validation 'cache-only-feed'
    if ($Offline) { New-Item -ItemType Directory -Path $feed | Out-Null }
    foreach ($restoreProject in @('app/ESurfingDialerLite/ESurfingDialerLite.csproj', 'tests/ClientRegression/ClientRegression.csproj')) {
        $restoreArgs = @('restore', $restoreProject, '-r', 'win-x64')
        if ($Offline) { $restoreArgs += @('--source', $feed) }
        Invoke-Logged 'dotnet' $restoreArgs ("restore-" + [IO.Path]::GetFileNameWithoutExtension($restoreProject) + '.log')
    }
    $buildArgs = @('-NoProfile', '-File', (Join-Path $PSScriptRoot 'Build-Installer.ps1'), '-OutputRoot', $output, '-Jdk17', $Jdk17, '-Jdk21', $Jdk21, '-Regression')
    if ($Offline) { $buildArgs += '-Offline' }
    Invoke-Logged $shell $buildArgs 'build-installer.log'
    $buildLog = Get-Content -LiteralPath (Join-Path $validation 'build-installer.log') -Raw
    $authMatches = [regex]::Matches($buildLog, 'PASS: (\d+) authentication checks')
    $recoveryMatches = [regex]::Matches($buildLog, 'PASS: (\d+) recovery checks')
    if ($authMatches.Count -ne 1 -or $recoveryMatches.Count -ne 1) { throw '核心回归没有唯一的成功统计。' }
    $authChecks = [int]$authMatches[0].Groups[1].Value
    $recoveryChecks = [int]$recoveryMatches[0].Groups[1].Value
    if ($authChecks -lt 319 -or $recoveryChecks -lt 63) { throw '核心检查数量低于本轮已验证基线。' }
    $published = Join-Path $output 'ESurfingDialerLite'
    $installer = Join-Path $output "ESurfingDialer-Windows-v$version-Setup.exe"
    foreach ($item in @('ESurfingDialerLite.exe', 'ESurfingDialerLite.dll')) {
        $info = [Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $published $item))
        if ($info.FileVersion -ne $fileVersion -or ($info.ProductVersion -split '\+', 2)[0] -ne $version) { throw "发布文件版本不匹配：$item" }
    }
    $setupInfo = [Diagnostics.FileVersionInfo]::GetVersionInfo($installer)
    # Inno Setup pads the textual PE version resources to fixed widths.
    if ($setupInfo.FileVersion.Trim() -ne $fileVersion -or $setupInfo.ProductVersion.Trim() -ne $version) { throw '安装器版本不匹配。' }
    $runtimeConfig = Get-Content -LiteralPath (Join-Path $published 'ESurfingDialerLite.runtimeconfig.json') -Raw | ConvertFrom-Json
    if (@($runtimeConfig.runtimeOptions.includedFrameworks).Count -ne 2) { throw '发布文件不是预期的自包含.NET/WPF运行时。' }
    $java = Join-Path $published 'runtime/bin/java.exe'
    Invoke-Logged $java @('-version') 'java-version.log'
    $javaRelease = Get-Content -LiteralPath (Join-Path $published 'runtime/release') -Raw
    if ($javaRelease -notmatch 'JAVA_VERSION="(21\.[^"]+)"') { throw '包内Java版本不是21。' }
    $javaVersion = $Matches[1]
    $archive = [IO.Compression.ZipFile]::OpenRead((Join-Path $published 'core/client.jar'))
    try {
        $entries = @($archive.Entries | Where-Object FullName -eq 'libdaproxy.so')
        if ($entries.Count -ne 1) { throw '核心原生资源缺失或重复。' }
        $stream = $entries[0].Open()
        try { $nativeHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant() }
        finally { $stream.Dispose() }
        if (@($archive.Entries | Where-Object FullName -match 'Regression.*\.class$').Count) { throw '测试类不能进入交付JAR。' }
        foreach ($requiredClass in @('PortalConfiguration', 'PortalDiagnostics', 'PortalXmlDetails', 'PortalXmlIssue', 'PortalXmlContext', 'PortalXmlStage')) {
            $requiredPath = "com/rsplwe/esurfing/$requiredClass.class"
            if (@($archive.Entries | Where-Object FullName -eq $requiredPath).Count -ne 1) { throw "门户必需类缺失或重复：$requiredClass" }
        }
        foreach ($entry in @($archive.Entries | Where-Object FullName -match '^com/rsplwe/esurfing/Portal.*\.class$')) {
            $compiled = Join-Path $sourceRoot "core/ESurfingDialerCore/build/classes/kotlin/main/$($entry.FullName)"
            $stream = $entry.Open()
            try { $entryHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant() }
            finally { $stream.Dispose() }
            if ($entryHash -ne (Hash $compiled)) { throw '包内门户类与编译输出不一致。' }
        }
    }
    finally { $archive.Dispose() }
    if ($nativeHash -ne (Hash (Join-Path $sourceRoot 'core/ESurfingDialerCore/src/main/resources/libdaproxy.so'))) { throw '包内原生库与源码不一致。' }
    if ((Hash (Join-Path $published 'core/client.jar')) -ne (Hash (Join-Path $sourceRoot 'core/ESurfingDialerCore/build/libs/ESurfingDialer-1.4.0-all.jar'))) { throw '交付核心不是本轮构建。' }
    $probe = Join-Path $validation 'package-probe'
    Invoke-Logged 'dotnet' @('publish', 'tests/ClientRegression/ClientRegression.csproj', '-c', 'Release', '-r', 'win-x64', '--self-contained', 'true', '--no-restore', '-o', $probe) 'publish-harness.log'
    Copy-Item -Path (Join-Path $published '*') -Destination $probe -Recurse -Force
    foreach ($relative in @('ESurfingDialerLite.dll', 'core/client.jar', 'runtime/bin/java.exe', 'coreclr.dll')) {
        if ((Hash (Join-Path $probe $relative)) -ne (Hash (Join-Path $published $relative))) { throw "受测文件摘要不一致：$relative" }
    }
    Invoke-Logged (Join-Path $probe 'ClientRegression.exe') @((Join-Path $validation 'client-regression'), '--core', '--stress') 'client-regression.log'
    $clientResult = Get-Content -LiteralPath (Join-Path $validation 'client-regression/result.json') -Raw | ConvertFrom-Json
    if (-not $clientResult.Passed -or -not $clientResult.Core -or -not $clientResult.Stress -or $clientResult.Checks -lt 188) { throw '包内客户端完整回归未通过。' }
    Invoke-Logged (Join-Path $probe 'ClientRegression.exe') @((Join-Path $validation 'ui-regression'), '--ui-only') 'ui-regression.log'
    Invoke-Logged $shell @('-NoProfile', '-File', (Join-Path $sourceRoot 'tests/ClientRegression/Test-PublishedStartup.ps1'), '-PublishedDirectory', $published, '-OutputDirectory', (Join-Path $validation 'exe-startup')) 'exe-startup.log'
    $startup = Get-Content -LiteralPath (Join-Path $validation 'exe-startup/result.json') -Raw | ConvertFrom-Json
    if (-not $startup.Passed -or -not $startup.InputIdle -or -not $startup.WpfWindowReady -or -not $startup.CleanupSucceeded -or -not $startup.ConfigurationUnchanged) { throw 'EXE入口就绪或清理门禁未通过。' }
    $hostTrace = Get-Content -LiteralPath (Join-Path $validation 'exe-startup/runtime-host-trace.log') -Raw
    $loadedRuntime = 'Loaded library from ' + (Join-Path $published 'coreclr.dll')
    if ($hostTrace -notmatch ('(?m)^' + [regex]::Escape($loadedRuntime) + '\r?$')) { throw '产品EXE未确认成功加载包内.NET运行时。' }
    $links = Check-Links
    Save-Json $links (Join-Path $validation 'document-links.json')
    if (($inputs | ConvertTo-Json -Depth 4 -Compress) -cne (@(Source-Inventory) | ConvertTo-Json -Depth 4 -Compress)) { throw '构建期间源码输入变化，不能交付。' }
    foreach ($entry in $protected) {
        $path = Join-Path $repository $entry.Path
        if ((Test-Path -LiteralPath $path -PathType Leaf) -ne $entry.Exists -or ($entry.Exists -and (Hash $path) -ne $entry.Sha256)) { throw "保护文件发生变化：$($entry.Path)" }
    }
    $files = @(Get-ChildItem -LiteralPath $published -Recurse -File | Sort-Object FullName | ForEach-Object {
        [ordered]@{ Path = [IO.Path]::GetRelativePath($published, $_.FullName).Replace('\', '/'); Bytes = $_.Length; Sha256 = Hash $_.FullName }
    })
    $publishedBytes = 0L
    foreach ($file in $files) { $publishedBytes += [long]$file['Bytes'] }
    New-Item -ItemType Directory -Path $delivery | Out-Null
    Copy-Item -LiteralPath $installer -Destination $delivery
    Copy-Item -LiteralPath (Join-Path $published 'README_TEST.txt') -Destination $delivery
    if ((Hash $installer) -ne (Hash (Join-Path $delivery ([IO.Path]::GetFileName($installer))))) { throw '安装器交付复制校验失败。' }
    Save-Json $files (Join-Path $delivery 'PUBLISHED-FILES.json')
    Save-Json $inputs (Join-Path $delivery 'SOURCE-FILES.json')
    $sourceArchive = [IO.Compression.ZipFile]::Open((Join-Path $delivery 'SOURCE-INPUTS.zip'), [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $inputs | Where-Object Exists) {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($sourceArchive, (Join-Path $sourceRoot $entry.Path), $entry.Path, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    }
    finally { $sourceArchive.Dispose() }
    $manifest = [ordered]@{
        Product = 'ESurfingDialer Windows'; Version = $version; FileVersion = $fileVersion
        Distribution = $(if ($Release) { 'release-candidate' } else { 'local-test-only' }); Pushed = $false; Released = $false
        BuildHead = $buildHead; Branch = $branch; BuildHadUncommittedChanges = [bool](git status --porcelain -- app core scripts installer resources tests)
        SourceInputsIncludeUntracked = $true; SourceInputCount = $inputs.Count
        SourceArchiveSha256 = Hash (Join-Path $delivery 'SOURCE-INPUTS.zip')
        Installer = [ordered]@{ File = [IO.Path]::GetFileName($installer); Bytes = (Get-Item -LiteralPath $installer).Length; Sha256 = Hash $installer }
        Published = [ordered]@{ FileCount = $files.Count; Bytes = $publishedBytes; Directory = [IO.Path]::GetRelativePath($repository, $published).Replace('\', '/') }
        CoreSha256 = Hash (Join-Path $published 'core/client.jar'); NativeLibrarySha256 = $nativeHash
        JavaVersion = $javaVersion; DotNetFrameworks = $runtimeConfig.runtimeOptions.includedFrameworks; RuntimeIdentifier = 'win-x64'
        Validation = [ordered]@{ AuthenticationSimulationChecks = $authChecks; RecoveryChecks = $recoveryChecks; PackagedClientChecks = $clientResult.Checks; StartupGateChecks = $startupGate.Checks; Core = $true; Stress = $true; ExeStartup = $startup; ProductLoadsBundledDotNet = $true; DocumentLinks = $links; ProtectedFileCount = $protected.Count; CampusAuthenticationTested = $false; LongTermStabilityTested = $false; InstallUpgradeUninstallTested = $false; InstallerExtractionTested = $false }
        ValidationDirectory = [IO.Path]::GetRelativePath($repository, $validation).Replace('\', '/')
        CreatedAtUtc = [DateTimeOffset]::UtcNow
    }
    Save-Json $manifest (Join-Path $delivery 'BUILD-MANIFEST.json')
    New-Item -ItemType Directory -Path (Join-Path $delivery '本地验证结果') | Out-Null
    Copy-Item -LiteralPath (Join-Path $validation 'client-regression/result.json') -Destination (Join-Path $delivery '本地验证结果/client-regression.json')
    Copy-Item -LiteralPath (Join-Path $validation 'client-regression/resource-stress.json') -Destination (Join-Path $delivery '本地验证结果/resource-stress.json')
    Copy-Item -LiteralPath (Join-Path $validation 'exe-startup/result.json') -Destination (Join-Path $delivery '本地验证结果/exe-startup.json')
    Get-ChildItem -LiteralPath $delivery -Recurse -File | Sort-Object FullName | ForEach-Object {
        "$(Hash $_.FullName)  $([IO.Path]::GetRelativePath($delivery, $_.FullName).Replace('\', '/'))"
    } | Set-Content -LiteralPath (Join-Path $delivery 'SHA256SUMS.txt') -Encoding utf8
    Save-Json ([ordered]@{ Passed = $true; Version = $version; DeliveryDirectory = $delivery; InstallerSha256 = $manifest.Installer.Sha256; CompletedAtUtc = [DateTimeOffset]::UtcNow }) (Join-Path $validation 'result.json')
    Write-Host "验证交付完成：$delivery"
}
catch {
    if (Test-Path -LiteralPath $validation) { Save-Json ([ordered]@{ Passed = $false; Failure = $_.Exception.Message; CompletedAtUtc = [DateTimeOffset]::UtcNow }) (Join-Path $validation 'failure.json') }
    throw
}
finally { Pop-Location }
