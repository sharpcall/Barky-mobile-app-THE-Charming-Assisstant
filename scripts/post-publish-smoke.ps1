# A-Z post-publish checks for Barky, including duration comparison TX vs RX.
# Uses fast logcat polling loops and supports both Wi-Fi and Cellular peer discovery.
param(
    [string]$Package = "com.example.barky",
    [string]$Activity = "com.example.barky/.MainActivity",
    [string]$Service = "com.example.barky/.walkie.WalkieTalkieService",
    [int]$WaitSeconds = 7,
    [int]$TalkSeconds = 4,
    [switch]$BestQuality
)

$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }

function Adb-Svc([string]$serial, [string]$action, [string[]]$extraArgs = @()) {
    $cmd = @("-s", $serial, "shell", "am", "start-foreground-service", "-n", $Service, "-a", $action) + $extraArgs
    & $adb @cmd 2>$null
    if ($LASTEXITCODE -ne 0) {
        $cmd = @("-s", $serial, "shell", "am", "startservice", "-n", $Service, "-a", $action) + $extraArgs
        & $adb @cmd 2>$null
    }
}

# Fast logcat polling helper: returns as soon as $pat matches on $serial or after $timeoutSec.
function Wait-DeviceLog([string]$serial, [string]$pat, [double]$timeoutSec = 6.0) {
    $dl = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $dl) {
        $l = & $adb -s $serial logcat -d -v time 2>$null
        if (($l | Where-Object { $_ -match $pat } | Select-Object -First 1) -ne $null) { return }
        Start-Sleep -Milliseconds 250
    }
}

function Parse-ClipLines([object[]]$logLines, [string]$kind) {
    $hits = @()
    foreach ($line in $logLines) {
        $text = if ($null -ne $line.Line) { [string]$line.Line } else { [string]$line }
        if ($text -match "$kind mode=(\S+) frames=(\d+) videoMs=(\d+) audioMs=(\d+)") {
            $hits += [pscustomobject]@{
                Mode     = $Matches[1]
                Frames   = [int]$Matches[2]
                VideoMs  = [int]$Matches[3]
                AudioMs  = [int]$Matches[4]
                Raw      = $text
            }
        }
    }
    return $hits
}

function Fmt-Dur([int]$ms) {
    return ("{0}ms ({1:N2}s)" -f $ms, ($ms / 1000.0))
}

Write-Output "=== adb devices ==="
& $adb devices -l
# Deduplicate ADB transports by hardware ro.serialno so a phone connected via both
# mDNS (_adb-tls-connect) and IP:port is only counted once as a physical device.
$rawDevices = @()
& $adb devices | ForEach-Object {
    if ($_ -match "^(\S+)\s+device\s*$") { $rawDevices += $Matches[1] }
}
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
if ($devices.Count -eq 0) {
    Write-Output "SMOKE_FAIL: no devices"
    exit 2
}

foreach ($s in $devices) {
    $m = (& $adb -s $s shell getprop ro.product.model).Trim()
    Write-Output "=== LAUNCH $m ==="
    & $adb -s $s logcat -c | Out-Null
    & $adb -s $s shell am force-stop $Package
    & $adb -s $s shell am start -n $Activity | Out-Null
}

Write-Output "Polling up to ${WaitSeconds}s for HELLO / Firebase peer link..."
$linkDl = (Get-Date).AddSeconds([math]::Min(3.5, $WaitSeconds))
$linked = $false
while ((Get-Date) -lt $linkDl) {
    $allOk = $true
    foreach ($s in $devices) {
        $l = & $adb -s $s logcat -d -v time 2>$null
        if (-not ($l | Where-Object { $_ -match "HELLO from" } | Select-Object -First 1)) { $allOk = $false }
    }
    if ($allOk) { $linked = $true; break }
    Start-Sleep -Milliseconds 250
}
if (-not $linked) {
    # Fallback to Firebase directory peer auto-adoption for cellular / cross-subnet setups.
    foreach ($s in $devices) { Adb-Svc $s "com.example.barky.FIREBASE_LIST" }
    Wait-DeviceLog $devices[0] "HELLO from|adoptDirectoryPeer" ([math]::Max(2.0, $WaitSeconds - 3.5))
}

if ($devices.Count -ge 1) {
    $tx = $devices[0]
    $txModel = (& $adb -s $tx shell getprop ro.product.model).Trim()
    $modeLabel = if ($BestQuality) { "BEST_BUNDLE" } else { "LIVE" }

    if ($BestQuality) {
        Write-Output "=== Enable BEST quality on TX ==="
        Adb-Svc $tx "com.example.barky.SET_BEST_QUALITY" @("--ez", "best", "true")
    } else {
        Write-Output "=== Disable BEST quality on TX (LIVE) ==="
        Adb-Svc $tx "com.example.barky.SET_BEST_QUALITY" @("--ez", "best", "false")
    }
    Wait-DeviceLog $tx "SET_BEST_QUALITY=" 1.5

    Write-Output "=== AUTO VIDEO TEST: TX=$txModel hold ${TalkSeconds}s mode=$modeLabel ==="
    Adb-Svc $tx "com.example.barky.TEST_TALK_START"
    Start-Sleep -Seconds $TalkSeconds
    Adb-Svc $tx "com.example.barky.TEST_TALK_STOP"
    $settle = if ($BestQuality) { 10.0 } else { 5.0 }
    Write-Output "Polling up to ${settle}s for CLIP_TX / CLIP_RX..."
    Wait-DeviceLog $tx "CLIP_TX" $settle
    if ($devices.Count -ge 2) {
        Wait-DeviceLog $devices[1] "CLIP_RX" $settle
    }

    Write-Output "=== AUTO REPLAY TEST on TX ==="
    Adb-Svc $tx "com.example.barky.TEST_REPLAY"
    Wait-DeviceLog $tx "Replay start" 2.5
}

$pattern = "WalkieTalkieEngine|CameraFrameSource|HELLO|TCP video|TX OK|RX OK|Replay|channelBusy|TALK_START|TEST_TALK|Stored last|CLIP_TX|CLIP_RX|BEST|NetworkOnMainThread|FATAL|AndroidRuntime"
$fail = $false

foreach ($s in $devices) {
    $m = (& $adb -s $s shell getprop ro.product.model).Trim()
    $ver = & $adb -s $s shell dumpsys package $Package | Select-String "versionName"
    Write-Output "=== LOGS $m  $ver ==="
    $allLogs = & $adb -s $s logcat -d -v time 2>$null
    $logs = $allLogs | Select-String -Pattern $pattern | Select-Object -Last 80
    if ($logs) { $logs | ForEach-Object { $_.Line } } else { Write-Output "(no matching log lines)" }

    $txClips = @(Parse-ClipLines $allLogs "CLIP_TX")
    $rxClips = @(Parse-ClipLines $allLogs "CLIP_RX")
    $lastTx = $txClips | Select-Object -Last 1
    $lastRx = $rxClips | Select-Object -Last 1

    if ($lastTx) {
        Write-Output ("DURATION_TX {0} : mode={1} frames={2} video={3} audio={4}" -f `
            $m, $lastTx.Mode, $lastTx.Frames, (Fmt-Dur $lastTx.VideoMs), (Fmt-Dur $lastTx.AudioMs))
    } else {
        Write-Output "DURATION_TX $m : (none - this device did not transmit in this run)"
    }
    if ($lastRx) {
        Write-Output ("DURATION_RX {0} : mode={1} frames={2} video={3} audio={4}" -f `
            $m, $lastRx.Mode, $lastRx.Frames, (Fmt-Dur $lastRx.VideoMs), (Fmt-Dur $lastRx.AudioMs))
    } else {
        Write-Output "DURATION_RX $m : (none - this device did not receive in this run)"
    }

    $hello = @($logs | Where-Object { $_.Line -match "HELLO from" }).Count
    $mainThread = @($logs | Where-Object { $_.Line -match "NetworkOnMainThread" }).Count
    Write-Output "SUMMARY $m : HELLO=$hello CLIP_TX=$($txClips.Count) CLIP_RX=$($rxClips.Count) NetworkOnMainThread=$mainThread"
    if ($mainThread -gt 0) {
        Write-Output "SMOKE_FAIL $m : NetworkOnMainThread"
        $fail = $true
    }
}

if ($devices.Count -ge 2) {
    $tx = $devices[0]
    $rx = $devices[1]
    $txModel = (& $adb -s $tx shell getprop ro.product.model).Trim()
    $rxModel = (& $adb -s $rx shell getprop ro.product.model).Trim()
    $txLogs = & $adb -s $tx logcat -d -v time 2>$null
    $rxLogs = & $adb -s $rx logcat -d -v time 2>$null
    $sent = @(Parse-ClipLines $txLogs "CLIP_TX") | Select-Object -Last 1
    $got  = @(Parse-ClipLines $rxLogs "CLIP_RX") | Select-Object -Last 1

    Write-Output "=== DURATION COMPARE (complete smoke) ==="
    if ($sent -and $got) {
        $vDiff = [math]::Abs($sent.VideoMs - $got.VideoMs)
        $aDiff = [math]::Abs($sent.AudioMs - $got.AudioMs)
        $fDiff = [math]::Abs($sent.Frames - $got.Frames)
        Write-Output ("SENT  {0} : frames={1} video={2} audio={3} mode={4}" -f `
            $txModel, $sent.Frames, (Fmt-Dur $sent.VideoMs), (Fmt-Dur $sent.AudioMs), $sent.Mode)
        Write-Output ("GOT   {0} : frames={1} video={2} audio={3} mode={4}" -f `
            $rxModel, $got.Frames, (Fmt-Dur $got.VideoMs), (Fmt-Dur $got.AudioMs), $got.Mode)
        Write-Output ("DELTA frames={0} videoMs={1} audioMs={2}" -f $fDiff, $vDiff, $aDiff)
        if ($fDiff -gt 3 -or $vDiff -gt 800 -or $aDiff -gt 800) {
            Write-Output "SMOKE_FAIL: TX/RX duration mismatch beyond tolerance (800ms / 3 frames)"
            $fail = $true
        } else {
            Write-Output "SMOKE_OK: TX/RX durations match within tolerance"
        }
    } else {
        Write-Output "SMOKE_FAIL: missing CLIP_TX on sender or CLIP_RX on receiver"
        Write-Output ("  sent={0} got={1}" -f [bool]$sent, [bool]$got)
        $fail = $true
    }
}

Write-Output ("SMOKE_DONE devices={0} best={1}" -f $devices.Count, [bool]$BestQuality)
if ($fail) { exit 1 } else { exit 0 }
