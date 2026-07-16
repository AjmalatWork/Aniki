<#
.SYNOPSIS
    Builds the release APK and pushes it to Firebase App Distribution.

.EXAMPLE
    .\scripts\distribute-release.ps1 -ReleaseNotes "Fixes sync bug on cold start"
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$ReleaseNotes
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$AppId = "1:594898051123:android:7519232a901620e9454346"
$Group = "aniki-testers"

Set-Location $RepoRoot

# This machine has no standalone JDK -- reuse the JBR bundled with Android Studio.
if (-not $env:JAVA_HOME) {
    $BundledJbr = "D:\App Dev\Android Studio\jbr"
    if (Test-Path $BundledJbr) {
        $env:JAVA_HOME = $BundledJbr
    }
}

Write-Host "Building release APK..."
& "$RepoRoot\gradlew.bat" ":app:assembleRelease"
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed with exit code $LASTEXITCODE"
}

$SignedApkPath = Join-Path $RepoRoot "app\build\outputs\apk\release\app-release.apk"
$UnsignedApkPath = Join-Path $RepoRoot "app\build\outputs\apk\release\app-release-unsigned.apk"

if (Test-Path $SignedApkPath) {
    $ApkPath = $SignedApkPath
} elseif (Test-Path $UnsignedApkPath) {
    Write-Warning "No signed APK found -- keystore.properties is missing, distributing an UNSIGNED build."
    $ApkPath = $UnsignedApkPath
} else {
    throw "No release APK found under app\build\outputs\apk\release\"
}

Write-Host "Distributing $ApkPath to Firebase App Distribution (group: $Group)..."
firebase appdistribution:distribute $ApkPath `
    --app $AppId `
    --groups $Group `
    --release-notes $ReleaseNotes
if ($LASTEXITCODE -ne 0) {
    throw "firebase appdistribution:distribute failed with exit code $LASTEXITCODE"
}

Write-Host "Done."
