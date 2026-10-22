# Build + install Barky on all adb devices (USB or wireless debugging) in parallel,
# and optionally upload to Firebase so cellular/remote phones self-update hands-free.
# Optionally reconnect wireless endpoints listed in scripts/devices-wifi.json.
# Hard time limits so this never hangs the agent silently.
#
# devices-wifi.json example:
# { "devices": [ {"name":"Pixel8","address":"192.168.4.200:5555"}, {"name":"S20","address":"192.168.4.202:43123"} ] }
#
# Keep Wireless debugging ON on each phone (Developer options).
# Pairing once (adb pair IP:PAIRPORT) is enough; after that adb connect IP:PORT works.
param(
    [int]$OverallTimeoutSec = 360,
    [switch]$SkipBuild,
    [switch]$SkipSmoke,
    [switch]$UploadFirebase,
    [switch]$CellularSmoke
)

$ErrorActionPreference = "Continue"
$start = Get-Date
function Elapsed { [int]((Get-Date) - $start).TotalSeconds }
# Use ASCII punctuation in string literals so Windows PowerShell 5.1 (CP1252) does not
# misparse UTF-8 em-dash bytes (0xE2 0x80 0x94) as a closing smart-quote.
function DeadlineLeft {
    $left = $OverallTimeoutSec - (Elapsed)
    if ($left -lt 5) { throw "TIMEOUT: publish-wifi exceeded ${OverallTimeoutSec}s -- aborting and reporting" }
    return $left
}

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$root = "F:\To sort\Delete\barky"
# Prefer Android Studio2 JBR (matches gradle.properties), fallback to Android Studio JBR.
$javaHome = if (Test-Path "C:\Program Files\Android\Android Studio2\jbr") {
    "C:\Program Files\Android\Android Studio2\jbr"
} else {
    "C:\Program Files\Android\Android Studio\jbr"
}
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:LOCALAPPDATA\Android\Sdk\platform-tools;" + $env:Path

Write-Output "========== PUBLISH WIFI/ADB + FIREBASE t=0s =========="
Write-Output "Keep Wireless debugging ON on phones. Pairing is only needed once."

# Discover / reconnect wireless endpoints (mdns first -- ports change often)
$discover = Join-Path $PSScriptRoot "discover-wifi-devices.ps1"
if (Test-Path $discover) {
    Write-Output "Discovering wireless adb via mdns..."
    & powershell -NoProfile -ExecutionPolicy Bypass -File $discover 2>&1 | ForEach-Object { Write-Output $_ }
}
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
        # Poll briefly for connected devices instead of a long fixed sleep.
        for ($i = 0; $i -lt 6; $i++) {
            $cur = @(& $adb devices | Select-String "device$")
            if ($cur.Count -ge 2) { break }
            Start-Sleep -Milliseconds 300
        }
    } catch {
        Write-Output "WARN: devices-wifi.json parse/connect: $_"
    }
} else {
    Write-Output "INFO: no devices-wifi.json yet -- ran mdns discover; or leave Wireless debugging ON"
}

& $adb devices -l
# Deduplicate ADB transports by hardware ro.serialno so each physical phone is installed once.
$rawDevices = @()
& $adb devices | ForEach-Object { if ($_ -match "^(\S+)\s+device\s*$") { $rawDevices += $Matches[1] } }
$devices = @()
$seenHw = @{}
foreach ($s in $rawDevices) {
    $hw = (& $adb -s $s shell getprop ro.serialno 2>$null).Trim()
    if (-not $hw) { $hw = $s }
    if (-not $seenHw.ContainsKey($hw)) {
        $seenHw[$hw] = $true
        $devices += $s
    }
}
if ($devices.Count -lt 1 -and -not $UploadFirebase) {
    Write-Output "FAIL: no adb devices. Enable Wireless debugging, note IP:port, add to devices-wifi.json, or plug USB."
    exit 2
}
Write-Output "Devices: $($devices -join ', ')"

$apk = Join-Path $root "app\build-outputs\app-debug.apk"
if (-not $SkipBuild) {
    DeadlineLeft | Out-Null
    Write-Output "Building... t=$(Elapsed)s"
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

# Optional: upload APK catalog to Firebase Storage/Firestore in parallel with ADB installs
# so any phone on cellular data (without ADB) self-updates automatically via ApkSelfUpdate + BarkyService.
$fbJob = $null
if ($UploadFirebase) {
    $uploadScript = Join-Path $PSScriptRoot "upload-apk-to-firebase.mjs"
    if (Test-Path $uploadScript) {
        Write-Output "Starting Firebase APK upload in parallel... t=$(Elapsed)s"
        $fbJob = Start-Job -ScriptBlock {
            param($rootDir, $scriptPath)
            Set-Location $rootDir
            & node $scriptPath 2>&1
        } -ArgumentList $root, $uploadScript
    }
}

$pkg = "com.example.barky"
# Install and grant permissions across all connected ADB phones IN PARALLEL to cut install wall-time in half.
$installJobs = @()
foreach ($s in $devices) {
    DeadlineLeft | Out-Null
    $installJobs += Start-Job -ScriptBlock {
        param($adbExe, $serial, $apkPath, $pkgName)
        $m = (& $adbExe -s $serial shell getprop ro.product.model 2>$null).Trim()
        & $adbExe -s $serial install -r $apkPath 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            return "FAIL: install on $m ($serial)"
        }
        foreach ($p in @(
            "android.permission.RECORD_AUDIO","android.permission.CAMERA",
            "android.permission.ACCESS_FINE_LOCATION","android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.POST_NOTIFICATIONS","android.permission.READ_CONTACTS",
            "android.permission.CALL_PHONE","android.permission.READ_PHONE_STATE",
            "android.permission.READ_PHONE_NUMBERS","android.permission.WRITE_SECURE_SETTINGS"
        )) {
            & $adbExe -s $serial shell pm grant $pkgName $p 2>$null | Out-Null
        }
        # Enable PROJECT_MEDIA, SYSTEM_ALERT_WINDOW, REQUEST_INSTALL_PACKAGES, and BarkyService
        # accessibility so SCRN remote control and Firebase OTA self-updates work without prompts.
        & $adbExe -s $serial shell appops set $pkgName PROJECT_MEDIA allow 2>$null | Out-Null
        & $adbExe -s $serial shell appops set $pkgName SYSTEM_ALERT_WINDOW allow 2>$null | Out-Null
        & $adbExe -s $serial shell appops set $pkgName REQUEST_INSTALL_PACKAGES allow 2>$null | Out-Null
        & $adbExe -s $serial shell settings put secure enabled_accessibility_services "$pkgName/$pkgName.BarkyService" 2>$null | Out-Null
        & $adbExe -s $serial shell settings put secure accessibility_enabled 1 2>$null | Out-Null
        & $adbExe -s $serial shell am force-stop $pkgName | Out-Null
        & $adbExe -s $serial shell am start -n "$pkgName/.MainActivity" | Out-Null
        $ver = (& $adbExe -s $serial shell dumpsys package $pkgName | Select-String "versionName" | Select-Object -First 1)
        return "OK $m ($serial) $ver"
    } -ArgumentList $adb, $s, $apk, $pkg
}

if ($installJobs.Count -gt 0) {
    Write-Output "Installing in parallel on $($installJobs.Count) device(s)... t=$(Elapsed)s"
    Wait-Job -Job $installJobs -Timeout ([math]::Max(30, (DeadlineLeft) - 10)) | Out-Null
    foreach ($j in $installJobs) {
        $res = Receive-Job $j -ErrorAction SilentlyContinue
        Remove-Job $j -Force -ErrorAction SilentlyContinue
        $res | ForEach-Object { Write-Output $_ }
    }
}

if ($fbJob) {
    Wait-Job -Job $fbJob -Timeout 60 | Out-Null
    $fbOut = Receive-Job $fbJob -ErrorAction SilentlyContinue
    Remove-Job $fbJob -Force -ErrorAction SilentlyContinue
    $fbOut | Select-Object -Last 6 | ForEach-Object { Write-Output "  [firebase] $_" }
    # Trigger immediate Firebase update check on connected devices so they also refresh peer directory versionCode.
    foreach ($s in $devices) {
        & $adb -s $s shell am start-foreground-service -n "$pkg/.walkie.WalkieTalkieService" -a "com.example.barky.CHECK_UPDATE" 2>$null | Out-Null
    }
}

if (-not $SkipSmoke) {
    DeadlineLeft | Out-Null
    if ($devices.Count -ge 2) {
        # Run full-feature-smoke with fast logcat polling (supports both Wi-Fi and Cellular).
        Write-Output "Starting full-feature-smoke (max remaining $(DeadlineLeft)s)..."
        $smoke = Join-Path $PSScriptRoot "full-feature-smoke.ps1"
        $cellFlag = [bool]$CellularSmoke
        $job = Start-Job -ScriptBlock {
            param($script, $adbPath, $useCell)
            $env:Path = (Split-Path $adbPath) + ";" + $env:Path
            if ($useCell) {
                & powershell -NoProfile -ExecutionPolicy Bypass -File $script -WaitSeconds 10 -TalkSeconds 3 -Cellular
            } else {
                & powershell -NoProfile -ExecutionPolicy Bypass -File $script -WaitSeconds 10 -TalkSeconds 3
            }
        } -ArgumentList $smoke, $adb, $cellFlag
        $wait = [math]::Min(240, (DeadlineLeft) - 5)
        if (-not (Wait-Job $job -Timeout $wait)) {
            Stop-Job $job -ErrorAction SilentlyContinue
            Remove-Job $job -Force -ErrorAction SilentlyContinue
            Write-Output "FAIL: smoke timed out after ${wait}s -- see scripts/full-feature-smoke.ps1"
            exit 3
        }
        $out = Receive-Job $job
        Remove-Job $job -Force -ErrorAction SilentlyContinue
        $out | ForEach-Object { Write-Output $_ }
        if ($out -match "fail=[1-9]") { exit 1 }
    } else {
        Write-Output "WARN: only $($devices.Count) device(s) -- skipped dual-phone smoke (not hung; insufficient devices)"
    }
}

Write-Output "========== PUBLISH DONE t=$(Elapsed)s =========="
exit 0
