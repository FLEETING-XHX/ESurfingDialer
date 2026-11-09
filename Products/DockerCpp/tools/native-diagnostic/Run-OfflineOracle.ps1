param(
    [Parameter(Mandatory=$true)][string]$ModulePath,
    [Parameter(Mandatory=$true)][string]$KotlinJar,
    [Parameter(Mandatory=$true)][string]$DiagnosticJar,
    [string]$ReferenceDirectory,
    [ValidateRange(1,10)][int]$Repeat=3,
    [ValidateRange(0,2147483647)][long]$EpochSeconds=1767225600,
    [string]$OutputDirectory,
    [string]$Docker='C:/Users/FLEETING/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
# 固定已验收的glibc/amd64 Java17运行时；不使用路由器Alpine镜像，也不预加载JNI库。
$runtime='eclipse-temurin:17-jre-jammy@sha256:c35794d1348777e6bc310b9b7f420098ef8f37e988199549b545fded79a895a2'
function Resolve-InputFile([string]$Value) {
    $item=Get-Item -LiteralPath $Value
    if($item.PSIsContainer -or $item.FullName -match '[,\r\n]'){throw '输入必须是普通文件，路径不能含逗号或换行'}
    return $item.FullName
}
function Read-Report([string]$Path) {
    $values=@{}
    foreach($line in [IO.File]::ReadAllLines($Path)) {
        if($line -match '^([a-z_][a-z0-9_]*)=(.*)$'){$values[$Matches[1]]=$Matches[2]}
    }
    return $values
}
function Normalized-Vectors([string]$Path) {
    return [IO.File]::ReadAllText($Path).Replace("`r`n","`n").TrimEnd([char[]]"`r`n")
}
$module=Resolve-InputFile $ModulePath
$kotlin=Resolve-InputFile $KotlinJar
$diagnostic=Resolve-InputFile $DiagnosticJar
if((Get-Item -LiteralPath $module).Length -lt 5 -or (Get-Item -LiteralPath $module).Length -gt 1048576){throw '模块大小无效'}
$moduleHash=(Get-FileHash -LiteralPath $module -Algorithm SHA256).Hash.ToLower()
$expectedReport=$null
$expectedVectors=$null
if($ReferenceDirectory) {
    $reference=(Resolve-Path -LiteralPath $ReferenceDirectory).Path
    $expectedReport=Read-Report (Join-Path $reference 'report.properties')
    $expectedVectors=Normalized-Vectors (Join-Path $reference 'native-enc.tsv')
    if($expectedReport['module_sha256'] -ne $moduleHash){throw '参考材料与模块摘要不一致'}
}
$platform=& $Docker image inspect $runtime --format '{{.Os}}/{{.Architecture}}' 2>$null
if($LASTEXITCODE -ne 0){throw "缺少已固定的本地运行时，请先执行 docker pull $runtime；本脚本不会联网拉取"}
if($platform -ne 'linux/amd64'){throw '运行时平台不符'}
if(!$OutputDirectory) {
    $repository=(Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
    $runName=[DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssZ')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
    $OutputDirectory=Join-Path $repository "artifacts/build/native-oracle/$runName"
}
$output=[IO.Path]::GetFullPath($OutputDirectory)
if($output -match '[,\r\n]' -or (Test-Path -LiteralPath $output)){throw '输出目录须为新目录，且路径不能含逗号或换行'}
New-Item -ItemType Directory -Path $output -Force | Out-Null
$results=@()
$baseline=$null
for($index=1;$index -le $Repeat;$index++) {
    $run=Join-Path $output "run-$index"
    New-Item -ItemType Directory -Path $run | Out-Null
    $arguments=@('run','--rm','--pull','never','--platform','linux/amd64','--network','none',
        '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true',
        '--pids-limit','128','--memory','768m','--ulimit','core=0','--user','10001:10001',
        '--env','HOME=/tmp','--env','STATE_DIR=/tmp/native-diagnostic','--env','DIAGNOSTIC_BACKEND=unicorn2',
        '--env',"DIAGNOSTIC_EPOCH_SECONDS=$EpochSeconds",'--env','TZ=Asia/Shanghai',
        '--tmpfs','/tmp:rw,exec,nosuid,size=128m',
        '--mount',"type=bind,src=$diagnostic,dst=/tool/native-diagnostic.jar,readonly",
        '--mount',"type=bind,src=$kotlin,dst=/app/client.jar,readonly",
        '--mount',"type=bind,src=$module,dst=/input/module.bin,readonly",
        '--mount',"type=bind,src=$run,dst=/output",'--entrypoint','java',$runtime,
        '-Xmx256m','-Duser.timezone=Asia/Shanghai','-XX:ErrorFile=/output/jvm-crash.log','-cp','/tool/native-diagnostic.jar:/app/client.jar',
        'NativeDiagnostic','offline')
    & $Docker @arguments *> (Join-Path $run 'private-tool.log')
    $code=$LASTEXITCODE
    if($code -ne 0){throw "第$index 轮退出$code；结果保留在$run，请检查私有日志。未继续重复"}
    $report=Read-Report (Join-Path $run 'report.properties')
    if($report['stage'] -ne 'NATIVE_ENC_COLLECTED_RESPONSE_DIRECTION_PENDING' -or
       $report['login_attempted'] -ne 'false' -or $report['module_sha256'] -ne $moduleHash -or
       $report['native_backend'] -ne 'unicorn2' -or $report['fixed_epoch_seconds'] -ne [string]$EpochSeconds){throw "第$index 轮摘要未通过核对"}
    $vectors=Normalized-Vectors (Join-Path $run 'native-enc.tsv')
    $rowCount=@($vectors.Split("`n")).Count-1
    if($rowCount -lt 1 -or [string]$rowCount -ne $report['enc_vectors']){throw '向量计数不一致'}
    if($null -eq $baseline){$baseline=$vectors}
    elseif($baseline -cne $vectors){throw '新容器/新会话之间的合成向量不一致，不能作为固定基线'}
    if($null -ne $expectedVectors) {
        if($expectedVectors -cne $vectors){throw '本机与路由器的合成向量不一致'}
        foreach($key in @('native_aid','native_library_sha256','header_candidate','enc_vectors')) {
            if($expectedReport[$key] -cne $report[$key]){throw "现场参考字段不一致：$key"}
        }
    }
    $results += [ordered]@{run=$index;exit_code=$code;native_aid=$report['native_aid'];enc_vectors=$rowCount;
        vectors_sha256=(Get-FileHash -LiteralPath (Join-Path $run 'native-enc.tsv') -Algorithm SHA256).Hash.ToLower()}
    Write-Output "离线轮次 $index/$Repeat 通过：$rowCount 组向量；无网络、无登录。"
}
$summary=[ordered]@{runtime=$runtime;platform='linux/amd64';network='none';user='10001:10001';
    root_read_only=$true;capabilities='none';tmpfs_exec=$true;native_loading='session-owned single load';
    kotlin_jar_sha256=(Get-FileHash -LiteralPath $kotlin -Algorithm SHA256).Hash.ToLower();
    diagnostic_jar_sha256=(Get-FileHash -LiteralPath $diagnostic -Algorithm SHA256).Hash.ToLower();
    module_sha256=$moduleHash;reference_matched=($null -ne $expectedReport);runs=$results;
    fixed_epoch_seconds=$EpochSeconds;timezone='Asia/Shanghai';
    login_attempted=$false;response_direction_verified=$false}
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'validation.json') -Encoding utf8
Write-Output "离线对照完成：$output"
