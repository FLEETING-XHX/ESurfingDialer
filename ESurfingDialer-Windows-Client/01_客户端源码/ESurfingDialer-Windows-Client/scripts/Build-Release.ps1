param(
    [string]$OutputRoot,
    [string]$ValidationDirectory,
    [string]$DeliveryDirectory,
    [string]$Jdk17 = 'D:\Java\JDK17',
    [string]$Jdk21 = 'D:\Java\JDK21',
    [switch]$Offline
)

# 正式版本复用同一套严格门禁；发布 GitHub 由维护者在验证完成后执行。
& (Join-Path $PSScriptRoot 'Build-LocalBeta.ps1') @PSBoundParameters -Release
