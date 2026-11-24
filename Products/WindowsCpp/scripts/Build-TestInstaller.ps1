param(
    [Parameter(Mandatory)][string]$QtRoot,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$BuildDirectory='',
    [string]$IsccPath=(Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6/ISCC.exe')
)
$ErrorActionPreference='Stop'
$productRoot=Split-Path $PSScriptRoot -Parent
$output=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $output){ throw '请指定尚不存在的输出目录。' }
if(-not(Test-Path -LiteralPath $IsccPath)){ throw '找不到 Inno Setup 编译器。' }
New-Item -ItemType Directory -Path $output | Out-Null
$stage=Join-Path $output 'stage'
& (Join-Path $PSScriptRoot 'Deploy.ps1') -QtRoot $QtRoot -BuildDirectory $BuildDirectory -OutputDirectory $stage
& $IsccPath "/DStageDirectory=$stage" "/DPackageDirectory=$output" (Join-Path $productRoot 'installer/WindowsCpp.iss')
if($LASTEXITCODE -ne 0){ throw '测试安装器编译失败。' }
$installers=@(Get-ChildItem -LiteralPath $output -File -Filter 'ESurfingDialer-WindowsCpp-*-x64-Setup.exe')
if($installers.Count -ne 1){ throw '未找到唯一的 x64 测试安装包。' }
$installer=$installers[0]
if($installer.Length -gt 100MB){ throw '测试安装包超过 100 MiB。' }
Get-FileHash -LiteralPath $installer.FullName -Algorithm SHA256
