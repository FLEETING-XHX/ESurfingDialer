$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$coreDir = Join-Path $repoRoot "core\ESurfingDialerCore"
$jdk21 = "D:\Java\JDK21"

if (-not (Test-Path (Join-Path $jdk21 "bin\java.exe"))) {
    throw "JDK 21 was not found at $jdk21"
}

$env:JAVA_HOME = $jdk21
$env:Path = "$jdk21\bin;$env:Path"

Push-Location $coreDir
try {
    $cachedGradle = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists" -Recurse -Filter gradle.bat -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1

    if ($cachedGradle) {
        & $cachedGradle.FullName shadowJar --no-daemon --stacktrace
        if ($LASTEXITCODE -ne 0) {
            throw "Cached Gradle build failed."
        }
    } else {
        .\gradlew.bat shadowJar --no-daemon --stacktrace
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle wrapper build failed."
        }
    }
}
finally {
    Pop-Location
}

$jar = Get-ChildItem (Join-Path $coreDir "build\libs") -Filter "*-all.jar" | Select-Object -First 1
if (-not $jar) {
    throw "Core jar was not generated."
}

Write-Host "Core jar: $($jar.FullName)"
