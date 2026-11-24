param([string]$QtRoot = '', [ValidateSet('Release','Debug')][string]$Configuration='Release', [string]$BuildDirectory = '', [ValidateRange(1,16)][int]$Parallel=1, [switch]$SkipTests, [switch]$CompressFonts)
$ErrorActionPreference = 'Stop'
$productRoot = Split-Path $PSScriptRoot -Parent
if (-not $BuildDirectory) { $BuildDirectory = Join-Path $productRoot 'build/qt' }
if (-not $QtRoot) { $QtRoot = $env:QT_ROOT }
if (-not $QtRoot) { $QtRoot = Join-Path $productRoot '../../artifacts/tools/Qt/6.10.0/msvc2022_64' }
$QtRoot = [IO.Path]::GetFullPath($QtRoot)
if (-not (Test-Path -LiteralPath (Join-Path $QtRoot 'lib/cmake/Qt6/Qt6Config.cmake'))) { throw '未找到 Qt SDK。请通过 -QtRoot 或 QT_ROOT 指定含 Quick/QuickControls2/Svg/Test 的 Qt 6.8+ MSVC x64 SDK。' }
$cmakeCommand = Get-Command cmake -ErrorAction SilentlyContinue
if ($cmakeCommand) { $cmakePath = $cmakeCommand.Source }
else {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
    if (-not (Test-Path -LiteralPath $vswhere)) { throw '需要 Visual Studio C++ 工具及 CMake。' }
    $vsPath = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    $cmakePath = Join-Path $vsPath 'Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe'
}
if (-not (Test-Path -LiteralPath $cmakePath)) { throw '未找到 CMake，请安装 Visual Studio C++ CMake 工具。' }
$fontCompression = if ($CompressFonts) { 'ON' } else { 'OFF' }
& $cmakePath -S $productRoot -B $BuildDirectory -A x64 "-DCMAKE_PREFIX_PATH=$QtRoot" "-DWINDOWSCPP_COMPRESS_FONTS=$fontCompression"
if ($LASTEXITCODE -ne 0) { throw 'CMake 配置失败' }
& $cmakePath --build $BuildDirectory --config $Configuration --parallel $Parallel
if ($LASTEXITCODE -ne 0) { throw '编译失败' }
if (-not $SkipTests) {
    $ctestPath = Join-Path (Split-Path $cmakePath -Parent) 'ctest.exe'
    $previousPath = $env:PATH
    try {
        $env:PATH = (Join-Path $QtRoot 'bin') + ';' + $previousPath
        & $ctestPath --test-dir $BuildDirectory -C $Configuration --output-on-failure
    } finally { $env:PATH = $previousPath }
    if ($LASTEXITCODE -ne 0) { throw '接口边界验证失败' }
}
Write-Output (Join-Path $BuildDirectory "$Configuration/ESurfingDialerWindowsCpp.exe")
