#requires -Version 7.0
param(
 [Parameter(Mandatory)][string]$SlimImage,
 [Parameter(Mandatory)][string]$FullImage,
 [Parameter(Mandatory)][string]$BuilderImage,
 [string]$ControlBuilderImage='',
 [Parameter(Mandatory)][string]$OutputDirectory,
 [object[]]$PrivateCases=@(),
 [string]$Docker='C:/Users/FLEETING/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$output=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $output){throw 'Use a new output directory'}
if($output -match '[,\r\n]'){throw 'Unsupported output path'}
New-Item -ItemType Directory -Path "$output/binaries" | Out-Null
$owned=@();$network='esurfing-slim-'+[Guid]::NewGuid().ToString('N')
$networkCreated=$false
function Run-Docker([string[]]$Arguments){
 $result=@(& $Docker @Arguments 2>&1);$code=$LASTEXITCODE
 if($code -ne 0){throw "Docker check failed ($code): $($result -join "`n")"}
 return ($result -join "`n")
}
function Input-File([string]$Path){$p=(Get-Item -LiteralPath $Path);if($p.PSIsContainer -or $p.FullName -match '[,\r\n]'){throw 'Invalid input file'};return $p.FullName}
try {
 if(!$ControlBuilderImage){$ControlBuilderImage=$BuilderImage}
 foreach($side in @('control','candidate')){
  $buildImage=if($side -eq 'control'){$ControlBuilderImage}else{$BuilderImage}
  $null=New-Item -ItemType Directory -Path "$output/binaries/$side"
  $builder=(Run-Docker @('create','--network','none','--entrypoint','/bin/sh',$buildImage,'-c','exit 0')).Trim();$owned+=$builder
  if($builder -notmatch '^[a-f0-9]{64}$'){throw 'Invalid builder container ID'}
  foreach($name in @('runtime-platform-regression','http-regression','sm4-native-regression','module-session-regression')){
   $null=Run-Docker @('cp',"${builder}:/src/build/$name","$output/binaries/$side/$name")
  }
 }
 $null=Run-Docker @('network','create','--internal','--label','org.esurfing.test=minimal-runtime',$network);$networkCreated=$true
 $peer=(Run-Docker @('run','-d','--network',$network,'--network-alias','slim-fixture','--read-only','--cap-drop','ALL','--user','10001:10001','--entrypoint','/usr/bin/sleep',$FullImage,'180')).Trim();$owned+=$peer
 if($peer -notmatch '^[a-f0-9]{64}$'){throw 'Invalid fixture container ID'}
 $reports=@()
 foreach($image in @($FullImage,$SlimImage)){
  $info=(Run-Docker @('image','inspect',$image) | ConvertFrom-Json)[0]
  $container=(Run-Docker @('create','--network','none','--entrypoint','/bin/sh',$image,'-c','exit 0')).Trim();$owned+=$container
  $size=(Run-Docker @('container','inspect','--size',$container) | ConvertFrom-Json)[0].SizeRootFs
  $report=[ordered]@{image=$image;image_id=$info.Id;docker_image_size_bytes=$info.Size;rootfs_bytes=$size;private_sessions=@();network='internal test network only';server_authentication_tested=$false}
  $side=if($image -eq $FullImage){'control'}else{'candidate'}
  $common=@('run','--rm','--pull','never','--network',$network,'--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--user','10001:10001','--pids-limit','128','--memory','768m','--tmpfs','/tmp:rw,noexec,nosuid,size=16m','--mount',"type=bind,src=$output/binaries/$side,dst=/checks,readonly",'--entrypoint','/bin/sh')
  $platformArgs=@('--env','SLIM_TEST_DNS_NAME=slim-fixture')
  if($info.Config.Labels.'org.esurfing.runtime.profile' -eq 'lean-dependencies'){$platformArgs+=@('--env','SLIM_TEST_LEAN_PROFILE=1')}
  $platform=Run-Docker ($common+$platformArgs+@($image,'-c','/checks/runtime-platform-regression'))
  $http=Run-Docker ($common+@($image,'-c','/checks/http-regression'))
  $sm4=Run-Docker ($common+@($image,'-c','/checks/sm4-native-regression'))
  $report['platform']=$platform;$report['http']=$http;$report['sm4']=$sm4
  $privateCommon=$common.Clone()
  $privateCommon[[Array]::IndexOf($privateCommon,'--network')+1]='none'
  foreach($case in $PrivateCases){
   $module=Input-File $case.module;$vectors=Input-File $case.vectors;$unknown=Input-File $case.unknown
   $result=Run-Docker ($privateCommon+@('--mount',"type=bind,src=$module,dst=/input/module.bin,readonly",'--mount',"type=bind,src=$vectors,dst=/input/vectors.tsv,readonly",'--mount',"type=bind,src=$unknown,dst=/input/unknown.zsm,readonly",$image,'-c','/checks/module-session-regression /input/module.bin /input/vectors.tsv /input/unknown.zsm'))
   $report.private_sessions+=@{module_sha256=(Get-FileHash -LiteralPath $module).Hash.ToLower();vectors_sha256=(Get-FileHash -LiteralPath $vectors).Hash.ToLower();network='none';result=$result}
  }
  $layout=Run-Docker ($common+@($image,'-c','test ! -e /app/native-runtime-fixture && test ! -e /app/runtime-platform-regression && ! command -v java && ! command -v g++ && LD_TRACE_LOADED_OBJECTS=1 /app/esurfing-native'))
  $report['layout']=$layout;$reports+=$report
 }
 $reports | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$output/comparison.json" -Encoding utf8
 foreach($report in $reports){Write-Output "$($report.image): rootfs=$($report.rootfs_bytes) image-size=$($report.docker_image_size_bytes); platform/HTTP/SM4/private-session checks passed"}
} finally {
 foreach($container in $owned){if($container -match '^[a-f0-9]{64}$'){& $Docker rm -f -v $container 2>&1 | Out-Null}}
 if($networkCreated){& $Docker network rm $network 2>&1 | Out-Null}
}
