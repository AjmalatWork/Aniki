<#
Toggle script for local Aniki dev setup: one run starts everything needed to use the app on a
connected Android device (backend server + adb port-forward + launching the app), the next run
tears it all down. State is tracked via a PID lock file so the script can tell which mode to run.
#>

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$serverDir = Join-Path $repoRoot "server"
$lockFile = Join-Path $repoRoot "scripts\.aniki-dev-server.pid"
$adbPath = "C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$appPackage = "com.aniki.anikiai"

function Resolve-Npm {
    $cmd = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $fallback = "C:\nvm4w\nodejs\npm.cmd"
    if (Test-Path $fallback) { return $fallback }
    throw "Could not find npm.cmd. Is Node.js installed?"
}

function Test-ProcessAlive($processId) {
    try { Get-Process -Id $processId -ErrorAction Stop | Out-Null; return $true }
    catch { return $false }
}

function Stop-Everything($storedPid) {
    Write-Host "Stopping Aniki dev server (PID $storedPid)..."
    # tsx watch spawns a child node process; kill the whole tree, not just the launcher.
    Get-CimInstance Win32_Process -Filter "ParentProcessId=$storedPid" -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Stop-Process -Id $storedPid -Force -ErrorAction SilentlyContinue

    if (Test-Path $adbPath) {
        & $adbPath reverse --remove tcp:4000 2>$null | Out-Null
    }

    Remove-Item $lockFile -Force -ErrorAction SilentlyContinue
    Write-Host "Stopped. Port forwarding removed."
}

function Start-Everything {
    Write-Host "Starting Aniki backend server..."
    $npm = Resolve-Npm
    $proc = Start-Process -FilePath $npm -ArgumentList "run", "dev" -WorkingDirectory $serverDir `
        -WindowStyle Minimized -PassThru
    $proc.Id | Out-File -FilePath $lockFile -Encoding ascii

    Write-Host "Waiting for the server to become healthy..."
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        try {
            $resp = Invoke-WebRequest -Uri "http://127.0.0.1:4000/health" -UseBasicParsing -TimeoutSec 2
            if ($resp.StatusCode -eq 200) { $ready = $true; break }
        } catch {}
    }

    if (-not $ready) {
        Write-Host "Server did not come up within 30s. Leaving it running -- check its window for errors."
        Write-Host "Run this again to stop it."
        Start-Sleep -Seconds 4
        return
    }
    Write-Host "Server is up."

    if (-not (Test-Path $adbPath)) {
        Write-Host "adb not found at the expected path -- skipping device setup. Server is still running."
        Start-Sleep -Seconds 3
        return
    }

    $devices = & $adbPath devices | Select-String "\tdevice$"
    if (-not $devices) {
        Write-Host "No Android device/emulator connected -- server is running, but nothing to forward to yet."
        Write-Host "Connect your phone and run this again if the app still can't reach the server."
        Start-Sleep -Seconds 4
        return
    }

    & $adbPath reverse tcp:4000 tcp:4000 | Out-Null
    Write-Host "Device port-forwarding set up (tcp:4000)."

    & $adbPath shell monkey -p $appPackage -c android.intent.category.LAUNCHER 1 | Out-Null
    Write-Host "Launched Aniki on the device."

    Write-Host ""
    Write-Host "Everything is running. Run this script again to stop it."
    Start-Sleep -Seconds 3
}

if (Test-Path $lockFile) {
    $storedPid = Get-Content $lockFile -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($storedPid -and (Test-ProcessAlive $storedPid)) {
        Stop-Everything $storedPid
        Start-Sleep -Seconds 2
        exit
    } else {
        Remove-Item $lockFile -Force -ErrorAction SilentlyContinue
    }
}

Start-Everything
