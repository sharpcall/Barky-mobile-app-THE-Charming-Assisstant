# Build + install Barky on all adb devices (USB or wireless debugging).
# Optionally reconnect wireless endpoints listed in scripts/devices-wifi.json
# Hard time limits so this never hangs the agent silently.
#
# devices-wifi.json example:
# { "devices": [ {"name":"Pixel8","address":"192.168.4.200:5555"}, {"name":"S20","address":"192.168.4.202:43123"} ] }
#
# Keep Wireless debugging ON on each phone (Developer options).
# Pairing once (adb pair IP:PAIRPORT) is enough; after that adb connect IP:PORT works.
param(
    [int]$OverallTimeoutSec = 180,
    [switch]$SkipBuild,
    [switch]$SkipSmoke
)

$ErrorActionPreference = "Continue"
$start = Get-Date
function Elapsed { [int]((Get-Date) - $start).TotalSeconds }
function DeadlineLeft {
    $left = $OverallTimeoutSec - (Elapsed)
    if ($left -lt 5) { throw "TIMEOUT: publish-wifi exceeded ${OverallTimeoutSec}s — aborting and reporting" }
    return $left
}

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$root = "F:\To sort\Delete\barky"
$javaHome = "C:\Program Files\Android\Android Studio\jbr"
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:LOCALAPPDATA\Android\Sdk\platform-tools;" + $env:Path

Write-Output "========== PUBLISH WIFI t=0s =========="
Write-Output "Keep Wireless debugging ON on phones. Pairing is only needed once."

# Reconnect known wireless endpoints
$wifiList = Join-Path $PSScriptRoot "devices-wifi.json"
if (Test-Path $wifiList) {
    try {
        $cfg = Get-Content $wifiList -Raw | ConvertFrom-Json
        foreach ($d in $cfg.devices) {
            $addr = [string]$d.address
            if (-not $addr) { continue }
            Write-Output "adb connect $addr ($($d.name))"
            & $adb connect $addr 2>&1 | ForEach-Object { Write-Output "  $_" }
        }
        Start-Sleep -Seconds 2
    } catch {
        Write-Output "WARN: devices-wifi.json parse/connect: $_"
    }
} else {
    Write-Output "INFO: no scripts/devices-wifi.json — using currently connected adb devices only"
    Write-Output "INFO: create it with phone IP:PORT from Wireless debugging screen"
}

& $adb devices -l
$devices = @()
& $adb devices | ForEach-Object { if ($_ -match "^(\S+)\s+device\s*$") { $devices += $Matches[1] } }
if ($devices.Count -lt 1) {
    Write-Output "FAIL: no adb devices. Enable Wireless debugging, note IP:port, add to devices-wifi.json, or plug USB."
    exit 2
}
Write-Output "Devices: $($devices -join ', ')"

$apk = Join-Path $root "app\build-outputs\app-debug.apk"
if (-not $SkipBuild) {
    DeadlineLeft | Out-Null
    Write-Output "Building… t=$(Elapsed)s"
    Push-Location $root
    & .\gradlew.bat assembleDebug -q
    $bc = $LASTEXITCODE
    Pop-Location
    if ($bc -ne 0) {
        Write-Output "FAIL: gradle assembleDebug exit $bc at t=$(Elapsed)s"
        exit $bc
    }
}
if (-not (Test-Path $apk)) {
    Write-Output "FAIL: APK missing at $apk"
    exit 1
}

$pkg = "com.example.barky"
foreach ($s in $devices) {
    DeadlineLeft | Out-Null
    $m = (& $adb -s $s shell getprop ro.product.model 2>$null).Trim()
    Write-Output "Install $m ($s) t=$(Elapsed)s"
    & $adb -s $s install -r $apk
    if ($LASTEXITCODE -ne 0) {
        Write-Output "FAIL: install on $m"
        continue
    }
    foreach ($p in @(
        "android.permission.RECORD_AUDIO","android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION","android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.POST_NOTIFICATIONS","android.permission.READ_CONTACTS",
        "android.permission.CALL_PHONE","android.permission.READ_PHONE_STATE",
        "android.permission.READ_PHONE_NUMBERS"
    )) {
        & $adb -s $s shell pm grant $pkg $p 2>$null | Out-Null
    }
    & $adb -s $s shell am force-stop $pkg | Out-Null
    & $adb -s $s shell am start -n "$pkg/.MainActivity" | Out-Null
    $ver = & $adb -s $s shell dumpsys package $pkg | Select-String "versionName"
    Write-Output "OK $m $ver"
}

if (-not $SkipSmoke) {
    DeadlineLeft | Out-Null
    if ($devices.Count -ge 2) {
        Write-Output "Starting full-feature-smoke (max remaining $(DeadlineLeft)s)…"
        $smoke = Join-Path $PSScriptRoot "full-feature-smoke.ps1"
        $job = Start-Job -ScriptBlock {
            param($script, $adbPath)
            $env:Path = (Split-Path $adbPath) + ";" + $env:Path
            & powershell -NoProfile -ExecutionPolicy Bypass -File $script -WaitSeconds 10 -TalkSeconds 3
        } -ArgumentList $smoke, $adb
        $wait = [math]::Min(240, (DeadlineLeft) - 5)
        if (-not (Wait-Job $job -Timeout $wait)) {
            Stop-Job $job -ErrorAction SilentlyContinue
            Remove-Job $job -Force -ErrorAction SilentlyContinue
            Write-Output "FAIL: smoke timed out after ${wait}s — see scripts/full-feature-smoke.ps1"
            exit 3
        }
        $out = Receive-Job $job
        Remove-Job $job -Force -ErrorAction SilentlyContinue
        $out | ForEach-Object { Write-Output $_ }
        if ($out -match "fail=[1-9]") { exit 1 }
    } else {
        Write-Output "WARN: only $($devices.Count) device(s) — skipped dual-phone smoke (not hung; insufficient devices)"
    }
}

Write-Output "========== PUBLISH DONE t=$(Elapsed)s =========="
exit 0
