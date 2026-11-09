param(
    [Parameter(Mandatory=$true)][string]$KotlinJar,
    [string]$Jdk17='D:/Java/JDK17',
    [ValidatePattern('^[a-z0-9.-]+$')][string]$OutputName='native-diagnostic-v1'
)
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$build=Join-Path $repository 'artifacts/build/native-diagnostic'
$package=Join-Path $repository "artifacts/packages/DockerCpp/$OutputName"
if(Test-Path -LiteralPath $package){throw '诊断输出已存在，拒绝覆盖'}
New-Item -ItemType Directory -Path "$build/classes",$package -Force | Out-Null
$dependency=Join-Path $build 'kotlin-reference.jar'
Copy-Item -LiteralPath $KotlinJar -Destination $dependency
& "$Jdk17/bin/javac.exe" -encoding UTF-8 --release 17 -cp $dependency -d "$build/classes" "$PSScriptRoot/NativeDiagnostic.java" "$PSScriptRoot/NativeDiagnosticTest.java"
if($LASTEXITCODE -ne 0){throw '诊断工具编译失败'}
& "$Jdk17/bin/java.exe" -cp "$build/classes;$dependency" NativeDiagnosticTest
if($LASTEXITCODE -ne 0){throw '离线诊断回归失败'}
$jarArguments=@('--create','--file',"$package/native-diagnostic.jar")
Get-ChildItem -LiteralPath "$build/classes" -File | Where-Object { $_.Name -match '^NativeDiagnostic(\$.*)?\.class$' } | ForEach-Object {
    $jarArguments += @('-C',"$build/classes",$_.Name)
}
& "$Jdk17/bin/jar.exe" @jarArguments
if($LASTEXITCODE -ne 0){throw '诊断JAR生成失败'}
Copy-Item -LiteralPath "$PSScriptRoot/collect.sh","$PSScriptRoot/README.md" -Destination $package
New-Item -ItemType Directory -Path "$package/input" -Force | Out-Null
'本目录默认留空；需要时保存portal-url.txt或ticket-url.txt；离线模式使用module.bin。不要放入.env或账户配置。' | Set-Content -LiteralPath "$package/input/说明.txt" -Encoding utf8
$hashes=Get-ChildItem -LiteralPath $package -File | ForEach-Object {
    (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLower()+'  '+$_.Name
}
[IO.File]::WriteAllText("$package/SHA256SUMS",($hashes -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
Compress-Archive -LiteralPath $package -DestinationPath "$package.zip"
Get-FileHash -LiteralPath "$package.zip" -Algorithm SHA256
