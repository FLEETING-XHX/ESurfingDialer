param([string]$QtRoot='', [string]$BuildDirectory='', [switch]$Preview)
$ErrorActionPreference='Stop'
$productRoot=Split-Path $PSScriptRoot -Parent
if(-not $QtRoot){$QtRoot=$env:QT_ROOT}
if(-not $QtRoot){$QtRoot='C:/Qt/6.12.0/msvc2022_64'}
if(-not $BuildDirectory){$BuildDirectory=Join-Path $productRoot 'build/qt'}
$program=Join-Path $BuildDirectory 'Release/ESurfingDialerWindowsCpp.exe'
if(-not(Test-Path -LiteralPath $program)){throw '请先运行 Build.ps1 生成 Release 预览。'}
if(-not(Test-Path -LiteralPath (Join-Path $QtRoot 'bin/Qt6Quick.dll'))){throw 'Qt SDK 路径无效，请使用 -QtRoot 指定。'}
$previousPath=$env:PATH
try{
    $env:PATH=(Join-Path $QtRoot 'bin')+';'+$previousPath
    # This is an interactive preview, not a background service.
    $launch = @{ FilePath = $program; WorkingDirectory = (Split-Path $program -Parent) }
    if($Preview){ $launch.ArgumentList = '--preview' }
    Start-Process @launch
}finally{$env:PATH=$previousPath}
