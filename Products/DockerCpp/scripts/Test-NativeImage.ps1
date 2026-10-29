#requires -Version 7.0
param(
    [Parameter(Mandatory)][ValidatePattern('^esurfing-dialer-native:[A-Za-z0-9_.-]+$')][string]$Image,
    [ValidatePattern('^[a-z0-9-]+$')][string]$ReportName='ops-lifecycle',
    [string]$Docker='C:/Users/FLEETING/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$nativeRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$reportDirectory=Join-Path $nativeRoot 'build-msvc'
[IO.Directory]::CreateDirectory($reportDirectory) | Out-Null
function Invoke-TaskDocker([string[]]$ArgumentList) {
    $lines=@(& $Docker @ArgumentList 2>&1)
    [pscustomobject]@{code=$LASTEXITCODE;text=($lines | ForEach-Object {"$_"}) -join "`n"}
}
function Require-Success($Result) {if($Result.code -ne 0){throw "Isolated Docker test failed: $($Result.text)"};$Result.text}
$imageInfo=(Require-Success (Invoke-TaskDocker @('image','inspect',$Image)) | ConvertFrom-Json)[0]
if($imageInfo.Config.User -ne '10001:10001' -or $imageInfo.Config.Labels.'org.esurfing.implementation' -ne 'cpp' -or $imageInfo.Config.Entrypoint[0] -ne '/app/esurfing-native'){throw 'Image is not the expected isolated native layout'}
$watchdog=[IO.File]::ReadAllText((Join-Path $nativeRoot 'scripts/openwrt-watchdog.sh'))
$guard=[regex]::Match($watchdog,'timeout 10 docker exec "\$id" sh -c ''([\s\S]*?)'' native-watchdog').Groups[1].Value
if(!$guard.Contains('kill -TERM 1') -or !$guard.Contains('/proc/1/stat')){throw 'Exact watchdog exit guard not found'}
$ownedContainer=''
try {
    $platform='linux/'+$imageInfo.Architecture
    if($imageInfo.Architecture -eq 'arm'){$platform+='/v7'}
    $runIdentity=[Guid]::NewGuid().ToString('N')
    $arguments=@('run','-d','--platform',$platform,'--label',"org.esurfing.test-run=$runIdentity",'--network','none','--read-only','--tmpfs','/tmp:rw,noexec,nosuid,size=4m','--cap-drop','ALL','--security-opt','no-new-privileges:true',
        '-e','NATIVE_EXPERIMENTAL=1','-e','DIALER_USER=synthetic','-e','DIALER_PASSWORD=synthetic','-e','NETWORK_CHECK_URLS=http://192.0.2.1/probe','-e','HEALTH_WRITE_INTERVAL_SECONDS=1',$Image)
    $created=Require-Success (Invoke-TaskDocker $arguments)
    [IO.File]::WriteAllText((Join-Path $reportDirectory "$ReportName-create.log"),$created)
    $returnedIds=@($created -split '\r?\n' | ForEach-Object {$_.Trim()} | Where-Object {$_ -match '^[a-f0-9]{64}$'})
    if($returnedIds.Count -ne 1){throw 'Docker did not return one owned container ID'}
    $ownedContainer=$returnedIds[0]
    $owner=(Require-Success (Invoke-TaskDocker @('inspect','-f','{{index .Config.Labels "org.esurfing.test-run"}}',$ownedContainer))).Trim()
    if($owner -ne $runIdentity){$ownedContainer='';throw 'Container does not carry this test run ownership marker'}
    $program=(Require-Success (Invoke-TaskDocker @('inspect','-f','{{.Path}}',$ownedContainer))).Trim()
    if($program -ne '/app/esurfing-native'){throw 'Docker startup program is not the native core'}
    $health=$null
    for($attempt=0;$attempt -lt 50;$attempt++){
        $read=Invoke-TaskDocker @('exec',$ownedContainer,'sh','-c','cat /data/health.json')
        if($read.code -eq 0){try{$health=$read.text | ConvertFrom-Json}catch{}}
        if($health -and $health.schemaVersion -eq 3 -and $health.processAlive){break}
        Start-Sleep -Milliseconds 100
    }
    if(!$health -or $health.processId -ne 1 -or $health.instanceId -notmatch '^[A-Za-z0-9_-]+$' -or $health.authenticated){throw 'Isolated native PID1 health not established'}
    $null=Require-Success (Invoke-TaskDocker @('exec',$ownedContainer,'/app/healthcheck.sh'))
    $layout=Require-Success (Invoke-TaskDocker @('exec',$ownedContainer,'sh','-c','id -u; readlink /proc/1/exe; stat -c %s /app/esurfing-native; sha256sum /app/esurfing-native; test ! -e /app/native-runtime-fixture; ! command -v java'))
    [IO.File]::WriteAllText((Join-Path $reportDirectory "$ReportName-layout.log"),$layout)
    $rootSignal=Invoke-TaskDocker @('exec','--user','0',$ownedContainer,'sh','-c','kill -TERM 1')
    if($rootSignal.code -eq 0){throw 'Root unexpectedly signalled UID10001 without capabilities'}
    [IO.File]::WriteAllText((Join-Path $reportDirectory "$ReportName-root-signal.log"),$rootSignal.text)
    $start=(Require-Success (Invoke-TaskDocker @('exec',$ownedContainer,'sh','-c','awk ''{print $22}'' /proc/1/stat'))).Trim()
    if($start -notmatch '^[0-9]+$'){throw 'PID1 start marker invalid'}
    # Execute the script's actual guard, not a reimplementation, against the owned process.
    $wrongStart=Invoke-TaskDocker @('exec',$ownedContainer,'sh','-c',$guard,'native-watchdog','0',$health.instanceId)
    $wrongInstance=Invoke-TaskDocker @('exec',$ownedContainer,'sh','-c',$guard,'native-watchdog',$start,'wrong-synthetic-instance')
    if($wrongStart.code -ne 2 -or $wrongInstance.code -ne 2){throw 'Watchdog failed to reject a stale process/instance'}
    $samples=@()
    for($i=0;$i -lt 3;$i++){
        $sample=Require-Success (Invoke-TaskDocker @('stats','--no-stream','--format','{{json .}}',$ownedContainer))
        $samples+=($sample | ConvertFrom-Json)
    }
    $accepted=Invoke-TaskDocker @('exec',$ownedContainer,'sh','-c',$guard,'native-watchdog',$start,$health.instanceId)
    if($accepted.code -ne 0){throw "Same-user watchdog TERM rejected (code $($accepted.code)); layout evidence saved: $ReportName-layout.log"}
    $stopped=$false
    for($attempt=0;$attempt -lt 100;$attempt++){
        $running=Require-Success (Invoke-TaskDocker @('inspect','-f','{{.State.Running}}',$ownedContainer))
        if($running.Trim() -eq 'false'){$stopped=$true;break}
        Start-Sleep -Milliseconds 100
    }
    if(!$stopped){throw 'Native PID1 did not exit within the test grace period'}
    $exitCode=(Require-Success (Invoke-TaskDocker @('wait',$ownedContainer))).Trim()
    if($exitCode -ne '0'){throw "Native PID1 did not stop cleanly: $exitCode"}
    $healthPath=Join-Path $reportDirectory "$ReportName-final-health.json"
    $null=Require-Success (Invoke-TaskDocker @('cp',"${ownedContainer}:/data/health.json",$healthPath))
    $final=[IO.File]::ReadAllText($healthPath) | ConvertFrom-Json
    if($final.phase -ne 'stopped' -or $final.processAlive -or $final.authenticated){throw 'Final stopped health incorrect'}
    $logs=Require-Success (Invoke-TaskDocker @('logs',$ownedContainer))
    [IO.File]::WriteAllText((Join-Path $reportDirectory "$ReportName-stdout.log"),$logs)
    $report=[ordered]@{created_at=[DateTime]::UtcNow.ToString('o');image=$Image;image_id=$imageInfo.Id;image_bytes=$imageInfo.Size;architecture=$imageInfo.Architecture;variant=$imageInfo.Variant;
        image_user=$imageInfo.Config.User;docker_startup_program=$program;layout=$layout;direct_pid1=$true;root_signal_denied=$true;actual_watchdog_guard_stale_start_rejected=$true;actual_watchdog_guard_stale_instance_rejected=$true;same_user_term=$true;exit_code=0;
        readonly_root=$true;cap_drop_all=$true;network='none';credentials='synthetic only';resource_samples_context='idle/no-session snapshots, not authenticated peak or long-run acceptance';resource_samples=$samples;production_replaced=$false}
    [IO.File]::WriteAllText((Join-Path $reportDirectory "$ReportName-report.json"),($report | ConvertTo-Json -Depth 8))
    Write-Output "$Image actual PID1/watchdog guard and stopped health passed; only own container; idle resource snapshots are not authenticated peaks"
}
finally {
    if($ownedContainer -match '^[a-f0-9]{64}$'){$cleanup=Invoke-TaskDocker @('rm','-f','-v',$ownedContainer);if($cleanup.code -ne 0){Write-Warning "Own test container cleanup failed: $ownedContainer"}}
}
