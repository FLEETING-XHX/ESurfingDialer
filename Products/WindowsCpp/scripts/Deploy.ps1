param([string]$QtRoot='', [string]$BuildDirectory='', [string]$OutputDirectory='', [string]$VCRedistDirectory='')
$ErrorActionPreference='Stop'
$productRoot=Split-Path $PSScriptRoot -Parent
if(-not $QtRoot){$QtRoot=$env:QT_ROOT}
if(-not $QtRoot){$QtRoot=Join-Path $productRoot '../../artifacts/tools/Qt/6.10.0/msvc2022_64'}
if(-not $BuildDirectory){$BuildDirectory=Join-Path $productRoot 'build/qt'}
if(-not $OutputDirectory){$OutputDirectory=Join-Path $productRoot 'dist/qt-preview'}
if(Test-Path -LiteralPath $OutputDirectory){throw '部署目录已存在，请指定新的独立目录，避免覆盖。'}
$buildExe=Join-Path $BuildDirectory 'Release/ESurfingDialerWindowsCpp.exe'
if(-not(Test-Path -LiteralPath $buildExe)){throw '请先运行 Build.ps1 构建 Release。'}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
Copy-Item -LiteralPath $buildExe -Destination $OutputDirectory
$deployTool=Join-Path $QtRoot 'bin/windeployqt.exe'
$deployArgs=@('--release','--qmldir',(Join-Path $productRoot 'qml'),'--no-translations','--no-compiler-runtime')
if((& $deployTool --help | Out-String).Contains('--skip-plugin-types')){
    $deployArgs+=@('--skip-plugin-types','qmltooling')
}
& $deployTool @deployArgs (Join-Path $OutputDirectory 'ESurfingDialerWindowsCpp.exe')
if($LASTEXITCODE -ne 0){throw 'Qt 依赖部署失败'}
if(-not $VCRedistDirectory){
    $vswhere=Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
    if(Test-Path -LiteralPath $vswhere){
        $vs=& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
        if($vs){
            $defaultVersion=Join-Path $vs 'VC/Auxiliary/Build/Microsoft.VCRedistVersion.default.txt'
            if(Test-Path -LiteralPath $defaultVersion){
                $version=(Get-Content -LiteralPath $defaultVersion -Raw).Trim()
                $VCRedistDirectory=Get-ChildItem -LiteralPath (Join-Path $vs "VC/Redist/MSVC/$version/x64") -Directory -Filter 'Microsoft.VC*.CRT' |
                    Select-Object -First 1 -ExpandProperty FullName
            }
        }
    }
}
if(-not $VCRedistDirectory -or -not(Test-Path -LiteralPath (Join-Path $VCRedistDirectory 'msvcp140.dll'))){
    throw '缺少 x64 MSVC 应用本地运行库，请通过 -VCRedistDirectory 指定 Microsoft.VC*.CRT 目录。'
}
Get-ChildItem -LiteralPath $VCRedistDirectory -File -Filter '*.dll' | Copy-Item -Destination $OutputDirectory
$licenseDir=Join-Path $OutputDirectory 'licenses'
New-Item -ItemType Directory -Path $licenseDir -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $productRoot 'assets/icons/LICENSE.txt') -Destination (Join-Path $licenseDir 'Lucide.txt')
Copy-Item -LiteralPath (Join-Path $productRoot '../Windows/app/ESurfingDialerLite/Fonts/NotoSansSC-OFL.txt') -Destination $licenseDir
Copy-Item -LiteralPath (Join-Path $productRoot 'vendor/NOTICE.md') -Destination $licenseDir
Copy-Item -LiteralPath (Join-Path $productRoot '../DockerCpp/third_party/ciphers/LICENSE') -Destination (Join-Path $licenseDir 'ciphers-APACHE-2.0.txt')
if(Test-Path -LiteralPath (Join-Path $QtRoot 'licenses')){Copy-Item -LiteralPath (Join-Path $QtRoot 'licenses') -Destination (Join-Path $licenseDir 'Qt') -Recurse}
$files=@(Get-ChildItem -LiteralPath $OutputDirectory -Recurse -File)
$bytes=($files|Measure-Object Length -Sum).Sum
[pscustomobject]@{Directory=[IO.Path]::GetFullPath($OutputDirectory);Files=$files.Count;Bytes=$bytes;MiB=[math]::Round($bytes/1MB,2);LimitBytes=104857600;WithinLimit=($bytes -le 104857600)} | ConvertTo-Json
if($bytes -gt 104857600){throw '部署目录超过 100 MiB；不能作为达标交付。保留目录用于分析，不自动删依赖。'}
