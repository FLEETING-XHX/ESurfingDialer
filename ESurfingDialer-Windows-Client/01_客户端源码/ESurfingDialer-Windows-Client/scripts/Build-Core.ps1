param(
    [string]$Jdk17 = "D:\Java\JDK17",
    [string]$Jdk21 = "D:\Java\JDK21",
    [switch]$Offline,
    [switch]$Regression
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$coreDir = Join-Path $repoRoot "core\ESurfingDialerCore"
foreach ($jdk in @($Jdk17, $Jdk21)) {
    if (-not (Test-Path -LiteralPath (Join-Path $jdk "bin\java.exe"))) {
        throw "JDK was not found at $jdk. Pass -Jdk17 and -Jdk21 for your machine."
    }
}

$previousJava = $env:JAVA_HOME
$previousPath = $env:Path
$gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE ".gradle" }
$gradle = Join-Path $gradleHome "bootstrap\gradle-8.4\bin\gradle.bat"
if (-not (Test-Path -LiteralPath $gradle)) {
    $cached = Get-ChildItem (Join-Path $gradleHome "wrapper\dists\gradle-8.4-bin") -Recurse -Filter gradle.bat -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($cached) { $gradle = $cached.FullName }
    elseif ($Offline) { throw "Gradle 8.4 is not cached. Prepare the build environment before using -Offline." }
    else { $gradle = Join-Path $coreDir "gradlew.bat" }
}

# Gradle 8.4 runs on JDK 17; the project uses the JDK 21 compiler/toolchain.
$env:JAVA_HOME = $Jdk17
$env:Path = "$Jdk17\bin;$previousPath"
Push-Location $coreDir
try {
    $gradleArgs = @("shadowJar", "--no-daemon", "--console=plain", "-Dorg.gradle.java.installations.paths=$Jdk21")
    if ($Offline) { $gradleArgs += "--offline" }
    if ($Regression) { $gradleArgs += @("recoveryRegression", "authenticationRegression") }
    & $gradle @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Core build failed." }
}
finally {
    Pop-Location
    $env:JAVA_HOME = $previousJava
    $env:Path = $previousPath
}

$jar = Get-ChildItem (Join-Path $coreDir "build\libs") -Filter "*-all.jar" | Select-Object -First 1
if (-not $jar) { throw "Core jar was not generated." }
Write-Host "Core jar: $($jar.FullName)"
