param(
    [Parameter(Mandatory=$true)][string]$ModulePath,
    [Parameter(Mandatory=$true)][string]$KotlinJar,
    [Parameter(Mandatory=$true)][string]$UnknownCodeModule,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$CppImage='esurfing-diagnostic:cpp-integration-builder',
    [switch]$ResponseWithoutNul,
    [string]$Jdk17='D:/Java/JDK17',
    [string]$Docker='C:/Users/FLEETING/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$runtime='eclipse-temurin:17-jre-jammy@sha256:c35794d1348777e6bc310b9b7f420098ef8f37e988199549b545fded79a895a2'
function Input-File([string]$Path){$f=Get-Item -LiteralPath $Path;if($f.PSIsContainer -or $f.FullName -match '[,\r\n]'){throw '输入须是普通文件，路径不能含逗号或换行'};return $f.FullName}
$module=Input-File $ModulePath;$kotlin=Input-File $KotlinJar;$unknown=Input-File $UnknownCodeModule
$library=Input-File (Join-Path $repository 'Products/DockerCpp/profiles/android32-2082-loader.so')
$output=[IO.Path]::GetFullPath($OutputDirectory)
if((Test-Path -LiteralPath $output) -or $output -match '[,\r\n]'){throw '输出目录须为新目录，路径不能含逗号或换行'}
foreach($image in @($runtime,$CppImage)){
    $platform=& $Docker image inspect $image --format '{{.Os}}/{{.Architecture}}' 2>$null
    if($LASTEXITCODE -ne 0 -or $platform -ne 'linux/amd64'){throw '需要预先准备本地 linux/amd64 对照运行时及当前源码 builder 镜像；脚本不拉取镜像'}
}
$cppIdentity=& $Docker image inspect $CppImage --format '{{.Id}}'
if($LASTEXITCODE -ne 0){throw '无法固定C++测试镜像身份'}
New-Item -ItemType Directory -Path "$output/classes" -Force | Out-Null
& "$Jdk17/bin/javac.exe" -encoding UTF-8 --release 17 -cp $kotlin -d "$output/classes" "$PSScriptRoot/NativeDiagnostic.java" "$PSScriptRoot/SessionInteropProbe.java" *> "$output/javac.log"
if($LASTEXITCODE -ne 0){throw "探针编译失败；私有日志：$output/javac.log"}
& "$Jdk17/bin/jar.exe" --create --file "$output/probe.jar" -C "$output/classes" .
if($LASTEXITCODE -ne 0){throw '探针JAR生成失败'}
$isolated=@('run','--rm','--pull','never','--platform','linux/amd64','--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--pids-limit','128','--memory','768m','--ulimit','core=0','--user','10001:10001')
function Container-Step([string]$Name,[string[]]$Arguments){
    & $Docker @Arguments *> "$output/$Name.log"
    if($LASTEXITCODE -ne 0){throw "$Name 失败；私有日志保留于 $output/$Name.log"}
}
Container-Step 'emit' ($isolated+@('--mount',"type=bind,src=$output,dst=/output",$CppImage,'/src/build/module-session-regression','--emit','/output/cases.tsv'))
$probeArguments=@();if($ResponseWithoutNul){$probeArguments=@('--response-without-nul')}
Container-Step 'private-native' ($isolated+@('--env','HOME=/tmp','--env','TZ=Asia/Shanghai','--env','STATE_DIR=/tmp/native-diagnostic','--env','DIAGNOSTIC_BACKEND=unicorn2','--env','DIAGNOSTIC_EPOCH_SECONDS=1793339591','--tmpfs','/tmp:rw,exec,nosuid,size=128m','--mount',"type=bind,src=$output/probe.jar,dst=/tool/probe.jar,readonly",'--mount',"type=bind,src=$kotlin,dst=/app/client.jar,readonly",'--mount',"type=bind,src=$module,dst=/input/module.bin,readonly",'--mount',"type=bind,src=$output/cases.tsv,dst=/input/cases.tsv,readonly",'--mount',"type=bind,src=$output,dst=/output",'--entrypoint','java',$runtime,'-Xmx256m','-Duser.timezone=Asia/Shanghai','-XX:ErrorFile=/output/jvm-crash.log','-cp','/tool/probe.jar:/app/client.jar','SessionInteropProbe')+$probeArguments)
Container-Step 'cpp' ($isolated+@('--mount',"type=bind,src=$output/vectors.tsv,dst=/input/vectors.tsv,readonly",'--mount',"type=bind,src=$module,dst=/input/module.bin,readonly",'--mount',"type=bind,src=$unknown,dst=/input/unknown.zsm,readonly",'--mount',"type=bind,src=$library,dst=/app/module-profiles/android32-2082-loader.so,readonly",$CppImage,'/src/build/module-session-regression','/input/module.bin','/input/vectors.tsv','/input/unknown.zsm'))
$summary=[ordered]@{module_sha256=(Get-FileHash -LiteralPath $module).Hash.ToLower();native_library_sha256=(Get-FileHash -LiteralPath $library).Hash.ToLower();kotlin_jar_sha256=(Get-FileHash -LiteralPath $kotlin).Hash.ToLower();probe_sha256=(Get-FileHash -LiteralPath "$output/probe.jar").Hash.ToLower();vectors_sha256=(Get-FileHash -LiteralPath "$output/vectors.tsv").Hash.ToLower();runtime=$runtime;cpp_image=$CppImage;cpp_image_id=$cppIdentity;network='none';login_attempted=$false;server_authentication_tested=$false;response_origin='synthetic native primitive, not server'}
$summary['response_nul']=if($ResponseWithoutNul){'nonempty_responses_without_nul'}else{'included'}
$summary | ConvertTo-Json | Set-Content -LiteralPath "$output/validation.json" -Encoding utf8
Get-Content -LiteralPath "$output/cpp.log"
Write-Output "离线生产接入对照完成：$output"
