param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\..\artifacts\startup-gate-regression')
)
$ErrorActionPreference = 'Stop'
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw "门禁回归目录已存在：$output" }
New-Item -ItemType Directory -Path $output | Out-Null

# Load only the process-boundary functions from the real script, never its launch body.
$tokens = $null
$parseErrors = $null
$scriptPath = Join-Path $PSScriptRoot 'Test-PublishedStartup.ps1'
$ast = [Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw '启动验证脚本有语法错误' }
$names = @('Assert-StartupInputIdle', 'Stop-StartupTestProcess')
foreach ($name in $names) {
    $definition = $ast.Find({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $true)
    if ($null -eq $definition) { throw "缺少启动验证函数：$name" }
    . ([scriptblock]::Create($definition.Extent.Text))
}

$checks = 0
function Verify([bool]$Condition, [string]$Label) {
    if (-not $Condition) { throw $Label }
    $script:checks++
    Write-Output "PASS: $Label"
}
function Rejects([scriptblock]$Action, [string]$Label) {
    $failed = $false
    try { & $Action } catch { $failed = $true }
    Verify $failed $Label
}
function New-TestProcess {
    $fixture = [pscustomobject]@{
        Exited = $false; InputIdle = $true; ExitWaitResult = $true; ExitAfterWait = $true
        KillThrows = $false; DisposeThrows = $false; Calls = [Collections.Generic.List[string]]::new()
    }
    $fixture | Add-Member ScriptProperty HasExited { $this.Exited }
    $fixture | Add-Member ScriptMethod WaitForInputIdle { param($timeout)
        $this.Calls.Add("input:$timeout")
        return $this.InputIdle
    }
    $fixture | Add-Member ScriptMethod Kill { param($tree)
        $this.Calls.Add("kill:$tree")
        if ($this.KillThrows) { throw 'synthetic kill failure' }
    }
    $fixture | Add-Member ScriptMethod WaitForExit { param($timeout)
        $this.Calls.Add("wait:$timeout")
        if ($this.ExitAfterWait) { $this.Exited = $true }
        return $this.ExitWaitResult
    }
    $fixture | Add-Member ScriptMethod Refresh { $this.Calls.Add('refresh') }
    $fixture | Add-Member ScriptMethod Dispose {
        $this.Calls.Add('dispose')
        if ($this.DisposeThrows) { throw 'synthetic dispose failure' }
    }
    return $fixture
}

$idle = New-TestProcess
$idle.InputIdle = $false
Rejects { Assert-StartupInputIdle $idle 100 } 'InputIdle=false rejects startup'
$idle.InputIdle = $true
Assert-StartupInputIdle $idle 100
Verify ($idle.Calls.Count -eq 2) 'InputIdle=true permits later WPF readiness checks'

$clean = New-TestProcess
Stop-StartupTestProcess $clean 250
Verify (($clean.Calls -join ',') -eq 'kill:True,wait:250,refresh,dispose') 'Cleanup kills only the supplied process tree, waits and disposes in order'
Verify $clean.Exited 'Successful cleanup requires the process to have exited'

$alreadyExited = New-TestProcess
$alreadyExited.Exited = $true
Stop-StartupTestProcess $alreadyExited 250
Verify (($alreadyExited.Calls -join ',') -eq 'refresh,dispose') 'Already exited process is disposed without kill'
Stop-StartupTestProcess $null 250
Verify $true 'Start failure can clean up without a process'

$timedOut = New-TestProcess
$timedOut.ExitWaitResult = $false
$timedOut.ExitAfterWait = $false
Rejects { Stop-StartupTestProcess $timedOut 250 } 'Cleanup timeout cannot become a passed validation'
Verify ($timedOut.Calls[-1] -eq 'dispose') 'Cleanup timeout still disposes the process handle'

$stillRunning = New-TestProcess
$stillRunning.ExitAfterWait = $false
Rejects { Stop-StartupTestProcess $stillRunning 250 } 'Reported wait success is checked against actual exit state'
Verify ($stillRunning.Calls[-1] -eq 'dispose') 'Exit-state mismatch still disposes the process handle'

$killFailed = New-TestProcess
$killFailed.KillThrows = $true
Rejects { Stop-StartupTestProcess $killFailed 250 } 'Kill failure cannot become a passed validation'
Verify ($killFailed.Calls[-1] -eq 'dispose') 'Kill failure still disposes the process handle'

$disposeFailed = New-TestProcess
$disposeFailed.DisposeThrows = $true
Rejects { Stop-StartupTestProcess $disposeFailed 250 } 'Dispose failure prevents successful cleanup'

[ordered]@{ Passed = $true; Checks = $checks; RealProcessesStarted = 0; AuthenticationTested = $false } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
Get-Content -LiteralPath (Join-Path $output 'result.json')
