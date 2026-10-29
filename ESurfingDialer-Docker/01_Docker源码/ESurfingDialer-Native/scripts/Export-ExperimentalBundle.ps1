#requires -Version 7.0
param(
    [ValidatePattern('^[a-z0-9-]+$')][string]$Name='experimental-bundle-20261030',
    [string]$Docker='C:/Users/FLEETING/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
Add-Type -AssemblyName System.IO.Compression
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$build=Join-Path $root 'build-msvc'
$reportPath=Join-Path $build 'ops-validation-report.json'
$report=[IO.File]::ReadAllText($reportPath) | ConvertFrom-Json
if($report.production_replaced -or $report.published -or $report.credentials_used -ne 'synthetic only' -or $report.images.Count -ne 3){throw 'Expected isolated experimental validation report'}
$destination=Join-Path $build $Name
if(Test-Path -LiteralPath $destination){throw 'Bundle already exists; preserving it. Choose another explicit Name.'}
[IO.Directory]::CreateDirectory($destination) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $destination 'evidence')) | Out-Null
function Hash-Path([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Hash-Stream([IO.Stream]$Stream){$hasher=[Security.Cryptography.SHA256]::Create();try{[Convert]::ToHexString($hasher.ComputeHash($Stream)).ToLowerInvariant()}finally{$hasher.Dispose()}}
$sourceArchive=Join-Path $destination 'native-source.zip'
$output=[IO.File]::Open($sourceArchive,[IO.FileMode]::CreateNew)
$zip=[IO.Compression.ZipArchive]::new($output,[IO.Compression.ZipArchiveMode]::Create,$true)
try{
    foreach($source in $report.source_files){
        $relative=$source.file.Replace('\','/')
        if($relative.StartsWith('/') -or $relative.Contains('../') -or $relative -match '(^|/)build[^/]*(/|$)' -or $relative -eq '.env' -or ($relative -like '.env.*' -and $relative -ne '.env.example')){throw 'Unexpected source path in report'}
        $path=Join-Path $root $relative
        if((Hash-Path $path) -ne $source.sha256){throw "Source changed since validation: $relative"}
        $entry=$zip.CreateEntry($relative,[IO.Compression.CompressionLevel]::Optimal)
        $entry.LastWriteTime=[DateTimeOffset]::new(2026,10,30,0,0,0,[TimeSpan]::Zero)
        $sourceStream=[IO.File]::OpenRead($path);$entryStream=$entry.Open()
        try{$sourceStream.CopyTo($entryStream)}finally{$entryStream.Dispose();$sourceStream.Dispose()}
    }
}finally{$zip.Dispose();$output.Dispose()}
# Verify every archived source directly, without extraction or trusting ZIP names.
$verify=[IO.Compression.ZipFile]::OpenRead($sourceArchive)
try{
    if($verify.Entries.Count -ne $report.source_files.Count){throw 'Source archive entry count differs'}
    foreach($source in $report.source_files){$entry=$verify.GetEntry($source.file.Replace('\','/'));if(!$entry){throw 'Source missing from ZIP'};$stream=$entry.Open();try{if((Hash-Stream $stream) -ne $source.sha256){throw 'Source archive digest differs'}}finally{$stream.Dispose()}}
}finally{$verify.Dispose()}
$images=@()
foreach($image in $report.images){
    $identity=@(& $Docker image inspect -f '{{.Id}}' $image.tag 2>&1)
    if($LASTEXITCODE -ne 0 -or ($identity -join "`n").Trim() -ne $image.image_id){throw 'Validated image tag changed'}
    $platform=$image.platform
    $arch=$platform.Replace('/','-')
    $tarPath=Join-Path $destination "$arch.tar"
    & $Docker image save --platform $platform --output $tarPath $image.tag
    if($LASTEXITCODE -ne 0){throw 'Docker image export failed; incomplete bundle retained'}
    $tarHash=Hash-Path $tarPath
    # Inspect only manifest.json, to stdout; never extract paths from an image archive.
    $manifestText=@(& 'C:/Windows/System32/tar.exe' -xOf $tarPath manifest.json 2>&1)
    if($LASTEXITCODE -ne 0){throw 'Exported Docker archive lacks a readable manifest'}
    $manifest=($manifestText -join "`n") | ConvertFrom-Json
    if($manifest.Count -ne 1 -or $manifest[0].RepoTags -notcontains $image.tag){throw 'Exported Docker archive tag differs'}
    $gzipPath="$tarPath.gz"
    $sourceStream=[IO.File]::OpenRead($tarPath);$output=[IO.File]::Open($gzipPath,[IO.FileMode]::CreateNew)
    $gzip=[IO.Compression.GZipStream]::new($output,[IO.Compression.CompressionLevel]::Optimal,$true)
    try{$sourceStream.CopyTo($gzip)}finally{$gzip.Dispose();$output.Dispose();$sourceStream.Dispose()}
    $compressed=[IO.File]::OpenRead($gzipPath);$decompressed=[IO.Compression.GZipStream]::new($compressed,[IO.Compression.CompressionMode]::Decompress)
    try{if((Hash-Stream $decompressed) -ne $tarHash){throw 'Compressed image does not reproduce exported bytes'}}finally{$decompressed.Dispose();$compressed.Dispose()}
    $images+=[ordered]@{file=[IO.Path]::GetFileName($gzipPath);platform=$platform;tag=$image.tag;validated_image_id=$image.image_id;sha256=(Hash-Path $gzipPath);bytes=(Get-Item -LiteralPath $gzipPath).Length;uncompressed_sha256=$tarHash;docker_manifest_checked=$true;gzip_roundtrip_checked=$true;docker_load_tested=$false}
    # A single known generated tar, inside the new destination; no recursive cleanup.
    Remove-Item -LiteralPath $tarPath
}
Copy-Item -LiteralPath $reportPath -Destination (Join-Path $destination 'evidence/ops-validation-report.json')
foreach($log in $report.evidence){
    $path=Join-Path $root $log.file
    if((Hash-Path $path) -ne $log.sha256){throw 'Evidence changed since validation'}
    Copy-Item -LiteralPath $path -Destination (Join-Path $destination ('evidence/'+[IO.Path]::GetFileName($path)))
}
$repositoryRoot=(Resolve-Path (Join-Path $root '../../..')).Path
foreach($document in @('DOCKER-CPP-MIGRATION.md','DOCKER-CPP-FIELD-ACCEPTANCE.md')){Copy-Item -LiteralPath (Join-Path $repositoryRoot "项目文档/$document") -Destination (Join-Path $destination "evidence/$document")}
$manifest=[ordered]@{schema_version=1;created_at=[DateTime]::UtcNow.ToString('o');kind='experimental offline handoff, not a production release';native_equivalence=$false;real_authentication=$false;production_ready=$false;production_replaced=$false;published=$false;docker_load_tested=$false;source_archive='native-source.zip';source_sha256=(Hash-Path $sourceArchive);source_entries=$report.source_files.Count;images=$images;credentials='no real .env, password file, account, ticket, or user state included'}
[IO.File]::WriteAllText((Join-Path $destination 'bundle-manifest.json'),($manifest | ConvertTo-Json -Depth 8))
$readme=@'
# Docker C++ 本地离线实验检查包

本包未发布、未部署生产，不是已完成校园网验收的发行版。原生等价/现场认证仍未通过。

- native-source.zip：经过逐文件SHA256核对的C++源码、独立C算法与许可证、实验Compose、.env.example及测试。没有真实.env、账户文件或data。
- linux-*.tar.gz：本机已经构建/测试的三架构实验镜像，含Docker归档；压缩后已流式解压核对原始摘要，manifest.json标签已核对。本轮没有执行docker load。
- evidence：阶段报告、离线/容器日志、镜像身份与现场验收规格。ARM证据是CPU仿真，不是实体路由器。
- bundle-manifest.json与SHA256SUMS：文件身份及明确未验收事项。

实验开关默认关闭，.env.example的NATIVE_EXPERIMENTAL=0。当前不会替换、重启或停止已有Kotlin服务；不要共用活动卷或复制旧健康文件。此包不包含旧Kotlin部署与用户状态，回退仍依赖保留的原目录/卷。

请先阅读evidence/DOCKER-CPP-FIELD-ACCEPTANCE.md。真正替换之前还需要正确Android32/2082模块、实际原生aid与同方向黄金向量，以及明确授权后的校园网/实体路由器验证。不要把95参考分支自身往返当作该证据。
'@
[IO.File]::WriteAllText((Join-Path $destination 'README.md'),$readme)
$sums=Get-ChildItem -LiteralPath $destination -Recurse -File | Sort-Object FullName | ForEach-Object {"$(Hash-Path $_.FullName)  $([IO.Path]::GetRelativePath($destination,$_.FullName).Replace('\','/'))"}
[IO.File]::WriteAllLines((Join-Path $destination 'SHA256SUMS'),$sums,[Text.UTF8Encoding]::new($false))
Write-Output "Local experimental bundle verified: $destination"
