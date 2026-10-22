# Full A-Z feature smoke for Barky on all connected devices (Wi-Fi AND Cellular).
# Covers: peer HELLO / Firebase cellular directory link, LIVE video, BEST single-file,
# durations TX vs RX, Replay sync logs, WAKEUP, half-duplex busy, LISTEN, SEE, GPS, SCRN.
# Uses fast logcat polling loops for state transitions instead of fixed worst-case sleeps.
param(
    [string]$Package = "com.example.barky",
    [string]$Activity = "com.example.barky/.MainActivity",
    [string]$Service = "com.example.barky/.walkie.WalkieTalkieService",
    [int]$WaitSeconds = 12,
    [int]$TalkSeconds = 4,
    # When set, disables Wi-Fi on TX (USB device) during the test to verify cellular data path,
    # and restores Wi-Fi on TX at the end of the run.
    [switch]$Cellular,
    # Hard wall-clock limit so agent never hangs with no output
    [int]$OverallTimeoutSec = 300
)

$ErrorActionPreference = "Continue"
$script:smokeStart = Get-Date
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$fail = 0
$pass = 0

function Ok([string]$msg) { $script:pass++; Write-Output "PASS: $msg" }
function Bad([string]$msg) { $script:fail++; Write-Output "FAIL: $msg" }
function Info([string]$msg) {
    $t = [int]((Get-Date) - $script:smokeStart).TotalSeconds
    Write-Output ("INFO [t={0}s]: {1}" -f $t, $msg)
}
function Assert-NotTimedOut {
    # Use ASCII '--' so Windows PowerShell 5.1 (CP1252) does not treat UTF-8 em-dash bytes as a quote.
    $t = [int]((Get-Date) - $script:smokeStart).TotalSeconds
    if ($t -ge $OverallTimeoutSec) {
        Bad ("OVERALL TIMEOUT after {0}s (limit {1}s) -- aborting remaining steps" -f $t, $OverallTimeoutSec)
        Write-Output ("========== RESULTS pass={0} fail={1} timeout=1 ==========" -f $pass, $fail)
        exit 3
    }
}

function Adb-Svc([string]$serial, [string]$action, [string[]]$extra = @()) {
    $a = @("-s", $serial, "shell", "am", "start-foreground-service", "-n", $Service, "-a", $action) + $extra
    & $adb @a 2>$null
    if ($LASTEXITCODE -ne 0) {
        $a = @("-s", $serial, "shell", "am", "startservice", "-n", $Service, "-a", $action) + $extra
        & $adb @a 2>$null
    }
}

function Get-Logs([string]$serial) {
    # Filter to all Barky module tags + crash tags so logcat -d is fast over wireless ADB and never matches OS noise.
    return @(& $adb -s $serial logcat -d -v time -s WalkieTalkieEngine:I WalkieTalkieService:I BarkyVideoUI:I CameraFrameSource:I See:I SeeCameraSession:I Ring:I Listen:I FirebasePeerDirectory:I BarkyUdpRelay:I BarkyService:I ApkSelfUpdate:I LocationTracker:I WakeupPlayer:I AndroidRuntime:E DEBUG:I 2>$null)
}

function Has-Line([object[]]$logs, [string]$pattern) {
    if (-not $logs) { return $false }
    return ($logs | Where-Object { "$_" -match $pattern } | Select-Object -First 1) -ne $null
}

# Fast logcat polling helper: polls a single device every 250ms until $pattern appears or $timeoutSec elapses.
function Wait-Log([string]$serial, [string]$pattern, [double]$timeoutSec = 6.0) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $logs = @(Get-Logs $serial)
        if (Has-Line $logs $pattern) { return ,$logs }
        Start-Sleep -Milliseconds 250
    }
    return ,@(Get-Logs $serial)
}

# Fast dual-device logcat polling helper: polls both TX and RX every 250ms until both patterns match.
function Wait-LogsBoth([string]$s1, [string]$pat1, [string]$s2, [string]$pat2, [double]$timeoutSec = 6.0) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $l1 = @(Get-Logs $s1)
        $l2 = @(Get-Logs $s2)
        $ok1 = (-not $pat1) -or (Has-Line $l1 $pat1)
        $ok2 = (-not $pat2) -or (Has-Line $l2 $pat2)
        if ($ok1 -and $ok2) { return [pscustomobject]@{ Tx = $l1; Rx = $l2 } }
        Start-Sleep -Milliseconds 250
    }
    return [pscustomobject]@{ Tx = @(Get-Logs $s1); Rx = @(Get-Logs $s2) }
}

# Link peers across either LAN (NSD/UDP HELLO) or Cellular (Firebase directory + ADOPT_PEER).
function Ensure-PeersLinked([string]$devA, [string]$devB, [int]$maxWaitSec = 12) {
    # 1. Fast poll up to 3.5s for direct LAN HELLO when not forcing cellular.
    $lanPoll = if ($Cellular) { 1.5 } else { [math]::Min(3.5, $maxWaitSec) }
    $pair = Wait-LogsBoth $devA "HELLO from" $devB "HELLO from" $lanPoll
    if ((Has-Line $pair.Tx "HELLO from") -and (Has-Line $pair.Rx "HELLO from")) {
        return $pair
    }
    # 2. Trigger Firebase directory listing + auto-adoption (supports cellular and mixed Wi-Fi/cellular).
    Info "Triggering FIREBASE_LIST peer discovery for cellular/cross-net link..."
    Adb-Svc $devA "com.example.barky.FIREBASE_LIST"
    Adb-Svc $devB "com.example.barky.FIREBASE_LIST"
    $pair = Wait-LogsBoth $devA "FIREBASE_PEER|HELLO from" $devB "FIREBASE_PEER|HELLO from" 5.0

    # Parse registered deviceIds and peer endpoints from logcat if explicit ADOPT_PEER is needed.
    $strA = ($pair.Tx -join "`n")
    $strB = ($pair.Rx -join "`n")
    $idA = [regex]::Match($strA, "registered db=\S+ id=(\S+)").Groups[1].Value
    $idB = [regex]::Match($strB, "registered db=\S+ id=(\S+)").Groups[1].Value
    $peerOnA = [regex]::Match($strA, "FIREBASE_PEER\s+(.+?)\s+id=(\S+)\s+local=([^:\s]*):(\d+)\s+public=([^:\s]*):(\d+)(?:\s+video=(\d+)\s+fullId=(\S+))?")
    $peerOnB = [regex]::Match($strB, "FIREBASE_PEER\s+(.+?)\s+id=(\S+)\s+local=([^:\s]*):(\d+)\s+public=([^:\s]*):(\d+)(?:\s+video=(\d+)\s+fullId=(\S+))?")
    if ($peerOnA.Success) {
        $pName = ($peerOnA.Groups[1].Value -replace "\s+", "")
        $locH = $peerOnA.Groups[3].Value; $locU = [int]$peerOnA.Groups[4].Value
        $pubH = $peerOnA.Groups[5].Value; $pubU = [int]$peerOnA.Groups[6].Value
        $vidP = if ($peerOnA.Groups[7].Value) { [int]$peerOnA.Groups[7].Value } else { 0 }
        $fullB = if ($peerOnA.Groups[8].Value) { $peerOnA.Groups[8].Value } else { $idB }
        $usePub = $Cellular -or (-not $locH) -or ($pubH -and $pubU -gt 0)
        $h = if ($usePub -and $pubH) { $pubH } else { $locH }
        $u = if ($usePub -and $pubU -gt 0) { $pubU } else { $locU }
        $v = if ($usePub) { $u } elseif ($vidP -gt 0) { $vidP } else { $u }
        if ($h -and $u -gt 0) {
            $ext = @("--es", "peer_name", $pName, "--es", "peer_host", $h, "--ei", "peer_udp", "$u", "--ei", "peer_video", "$v")
            if ($fullB) { $ext += @("--es", "peer_device_id", $fullB) }
            Adb-Svc $devA "com.example.barky.ADOPT_PEER" $ext
        }
    }
    if ($peerOnB.Success) {
        $pName = ($peerOnB.Groups[1].Value -replace "\s+", "")
        $locH = $peerOnB.Groups[3].Value; $locU = [int]$peerOnB.Groups[4].Value
        $pubH = $peerOnB.Groups[5].Value; $pubU = [int]$peerOnB.Groups[6].Value
        $vidP = if ($peerOnB.Groups[7].Value) { [int]$peerOnB.Groups[7].Value } else { 0 }
        $fullA = if ($peerOnB.Groups[8].Value) { $peerOnB.Groups[8].Value } else { $idA }
        $usePub = $Cellular -or (-not $locH) -or ($pubH -and $pubU -gt 0)
        $h = if ($usePub -and $pubH) { $pubH } else { $locH }
        $u = if ($usePub -and $pubU -gt 0) { $pubU } else { $locU }
        $v = if ($usePub) { $u } elseif ($vidP -gt 0) { $vidP } else { $u }
        if ($h -and $u -gt 0) {
            $ext = @("--es", "peer_name", $pName, "--es", "peer_host", $h, "--ei", "peer_udp", "$u", "--ei", "peer_video", "$v")
            if ($fullA) { $ext += @("--es", "peer_device_id", $fullA) }
            Adb-Svc $devB "com.example.barky.ADOPT_PEER" $ext
        }
    }
    return (Wait-LogsBoth $devA "HELLO from|adoptDirectoryPeer|ADOPT_PEER ok=true" $devB "HELLO from|adoptDirectoryPeer|ADOPT_PEER ok=true" ([math]::Max(3.0, $maxWaitSec - 5.0)))
}

function Parse-Clips([object[]]$logs, [string]$kind) {
    $hits = @()
    foreach ($line in $logs) {
        $t = [string]$line
        if ($t -match "$kind mode=(\S+) frames=(\d+) videoMs=(\d+) audioMs=(\d+)") {
            $hits += [pscustomobject]@{
                Mode = $Matches[1]; Frames = [int]$Matches[2]
                VideoMs = [int]$Matches[3]; AudioMs = [int]$Matches[4]; Raw = $t
            }
        }
    }
    return $hits
}

Write-Output ("========== FULL FEATURE SMOKE limit={0}s cellular={1} ==========" -f $OverallTimeoutSec, [bool]$Cellular)
# Optional wireless reconnect before smoke
$wifiList = Join-Path $PSScriptRoot "devices-wifi.json"
if (Test-Path $wifiList) {
    try {
        $cfg = Get-Content $wifiList -Raw | ConvertFrom-Json
        foreach ($d in $cfg.devices) {
            if ($d.address) { & $adb connect $d.address 2>$null | Out-Null }
        }
        Start-Sleep -Milliseconds 600
    } catch {}
}
& $adb devices -l
# Deduplicate ADB transports by hardware ro.serialno so a phone connected via both
# mDNS (_adb-tls-connect) and IP:port is only counted once as a physical device.
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
if ($devices.Count -lt 2) {
    Bad ("Need 2 devices for full dual-phone smoke (found {0}). Keep Wireless debugging ON; use scripts/devices-wifi.json" -f $devices.Count)
    Write-Output ("========== RESULTS pass={0} fail={1} ==========" -f $pass, $fail)
    exit 2
}
$tx = $devices[0]
$rx = $devices[1]
$txM = (& $adb -s $tx shell getprop ro.product.model).Trim()
$rxM = (& $adb -s $rx shell getprop ro.product.model).Trim()
Info "TX=$txM  RX=$rxM"

# If -Cellular is requested and TX is attached over USB (not IP:port), disable Wi-Fi on TX.
$restoreWifiOnTx = $false
if ($Cellular -and ($tx -notmatch ":")) {
    Info "Disabling Wi-Fi on $txM ($tx) to force cellular data path..."
    & $adb -s $tx shell svc wifi disable 2>$null | Out-Null
    $restoreWifiOnTx = $true
    Start-Sleep -Seconds 2
}

foreach ($s in $devices) {
    # Quiet everything including ALARM (WAKEUP) — do not blast the room
    foreach ($st in 1,2,3,4,5) {
        & $adb -s $s shell media volume --show --stream $st --set 1 2>$null | Out-Null
    }
    # STREAM_ALARM often not covered by 1..5 on all OEMs — force via cmd media
    & $adb -s $s shell cmd media_session volume --stream 4 --set 1 2>$null | Out-Null
    & $adb -s $s shell settings put system volume_alarm 1 2>$null | Out-Null
    & $adb -s $s shell settings put system volume_ring 1 2>$null | Out-Null
    foreach ($p in @(
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_CONTACTS",
        "android.permission.CALL_PHONE",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_PHONE_NUMBERS"
    )) {
        & $adb -s $s shell pm grant $Package $p 2>$null | Out-Null
    }
    & $adb -s $s shell appops set $Package PROJECT_MEDIA allow 2>$null | Out-Null
    & $adb -s $s shell appops set $Package SYSTEM_ALERT_WINDOW allow 2>$null | Out-Null
    & $adb -s $s shell appops set $Package REQUEST_INSTALL_PACKAGES allow 2>$null | Out-Null
    & $adb -s $s logcat -c | Out-Null
    & $adb -s $s shell am force-stop $Package
    & $adb -s $s shell am start -n $Activity | Out-Null
}
Info "Polling for peer link (up to ${WaitSeconds}s, Wi-Fi NSD + Firebase cellular directory)..."
$linkedLogs = Ensure-PeersLinked $tx $rx $WaitSeconds

# --- A: HELLO / peer discovery (LAN or Cellular Firebase directory) ---
$txLogs = $linkedLogs.Tx
$rxLogs = $linkedLogs.Rx
$txLinked = Has-Line $txLogs "HELLO from|adoptDirectoryPeer|ADOPT_PEER ok=true"
$rxLinked = Has-Line $rxLogs "HELLO from|adoptDirectoryPeer|ADOPT_PEER ok=true"
if ($txLinked -and $rxLinked) {
    Ok "A peer linked both ways (HELLO / Firebase directory)"
} else {
    Bad "A peer link missing (tx=$txLinked rx=$rxLinked)"
}
if (Has-Line $txLogs "NSD peer|HELLO from|FIREBASE_PEER|adoptDirectoryPeer") {
    Ok "A NSD/Firebase peer discovery signals present"
} else {
    Bad "A no NSD/Firebase peer discovery on TX"
}

# --- B: LIVE mode TX/RX with durations ---
Info "B LIVE hold ${TalkSeconds}s"
foreach ($s in $devices) { & $adb -s $s logcat -c | Out-Null }
Adb-Svc $tx "com.example.barky.SET_BEST_QUALITY" @("--ez", "best", "false")
Wait-Log $tx "SET_BEST_QUALITY=false" 1.5 | Out-Null
Adb-Svc $tx "com.example.barky.TEST_TALK_START"
Start-Sleep -Seconds $TalkSeconds
Adb-Svc $tx "com.example.barky.TEST_TALK_STOP"
# Fast poll for CLIP_TX and CLIP_RX instead of fixed 4s sleep.
$pairB = Wait-LogsBoth $tx "CLIP_TX" $rx "CLIP_RX" 6.0
$txLogs = $pairB.Tx
$rxLogs = $pairB.Rx
$liveTx = @(Parse-Clips $txLogs "CLIP_TX") | Select-Object -Last 1
$liveRx = @(Parse-Clips $rxLogs "CLIP_RX") | Select-Object -Last 1
if ($liveTx) {
    Ok ("B LIVE TX frames={0} videoMs={1} audioMs={2}" -f $liveTx.Frames, $liveTx.VideoMs, $liveTx.AudioMs)
} else { Bad "B LIVE no CLIP_TX" }
if ($liveRx) {
    Ok ("B LIVE RX frames={0} videoMs={1} audioMs={2}" -f $liveRx.Frames, $liveRx.VideoMs, $liveRx.AudioMs)
} else { Bad "B LIVE no CLIP_RX" }
if ($liveTx -and $liveRx) {
    $fd = [math]::Abs($liveTx.Frames - $liveRx.Frames)
    $vd = [math]::Abs($liveTx.VideoMs - $liveRx.VideoMs)
    $ad = [math]::Abs($liveTx.AudioMs - $liveRx.AudioMs)
    Info ("B LIVE DELTA frames={0} videoMs={1} audioMs={2}" -f $fd, $vd, $ad)
    if ($fd -le 5 -and $vd -le 900 -and $ad -le 900) { Ok "B LIVE TX/RX duration within tolerance" }
    else { Bad "B LIVE TX/RX duration mismatch" }
}
if (Has-Line $rxLogs "channelBusy=true") { Ok "B half-duplex channelBusy on RX while TX talks" }
else { Bad "B no channelBusy on RX during LIVE talk" }

# --- C: BEST single-file mode ---
Info "C BEST single-file hold ${TalkSeconds}s"
foreach ($s in $devices) { & $adb -s $s logcat -c | Out-Null }
Adb-Svc $tx "com.example.barky.SET_BEST_QUALITY" @("--ez", "best", "true")
Wait-Log $tx "SET_BEST_QUALITY=true" 1.5 | Out-Null
Adb-Svc $tx "com.example.barky.TEST_TALK_START"
Start-Sleep -Seconds $TalkSeconds
Adb-Svc $tx "com.example.barky.TEST_TALK_STOP"
# Fast poll until BEST bundle is uploaded on TX (CLIP_TX mode=BEST_BUNDLE) and synced playback starts on RX.
$pairC = Wait-LogsBoth $tx "CLIP_TX mode=BEST_BUNDLE|BEST bundle sent" $rx "BEST_RX sync play|sync play start" 12.0
$txLogs = $pairC.Tx
$rxLogs = $pairC.Rx
$bestTx = @(Parse-Clips $txLogs "CLIP_TX") | Where-Object { $_.Mode -eq "BEST_BUNDLE" } | Select-Object -Last 1
if (-not $bestTx) { $bestTx = @(Parse-Clips $txLogs "CLIP_TX") | Where-Object { $_.Mode -match "BEST" } | Select-Object -Last 1 }
if (-not $bestTx) { $bestTx = @(Parse-Clips $txLogs "CLIP_TX") | Select-Object -Last 1 }
$bestRx = @(Parse-Clips $rxLogs "CLIP_RX") | Where-Object { $_.Mode -match "BEST" } | Select-Object -Last 1
if (-not $bestRx) { $bestRx = @(Parse-Clips $rxLogs "CLIP_RX") | Select-Object -Last 1 }

if (Has-Line $txLogs "BEST quality record start|TX BEST record") {
    Ok "C BEST record path engaged"
} else { Bad "C BEST record path not seen" }

if (Has-Line $txLogs "singleFile=true|BEST single-file write") {
    Ok "C BEST single-file write on TX"
} else { Bad "C BEST single-file write log missing" }

if (Has-Line $txLogs "BEST bundle sent ok=true|BEST flush done") {
    Ok "C BEST bundle send completed"
} else { Bad "C BEST bundle send failed/missing" }

if (Has-Line $rxLogs "BEST_BUNDLE|BEST RX single-file") {
    Ok "C BEST single-file receive on RX"
} else { Bad "C BEST RX single-file missing" }

if ($bestTx -and $bestRx) {
    Info ("C BEST SENT frames={0} videoMs={1} audioMs={2}" -f $bestTx.Frames, $bestTx.VideoMs, $bestTx.AudioMs)
    Info ("C BEST GOT  frames={0} videoMs={1} audioMs={2}" -f $bestRx.Frames, $bestRx.VideoMs, $bestRx.AudioMs)
    $fd = [math]::Abs($bestTx.Frames - $bestRx.Frames)
    $vd = [math]::Abs($bestTx.VideoMs - $bestRx.VideoMs)
    $ad = [math]::Abs($bestTx.AudioMs - $bestRx.AudioMs)
    Info ("C BEST DELTA frames={0} videoMs={1} audioMs={2}" -f $fd, $vd, $ad)
    if ($fd -eq 0 -and $vd -eq 0 -and $ad -eq 0) { Ok "C BEST exact TX/RX match (single file intact)" }
    elseif ($fd -le 2 -and $vd -le 200 -and $ad -le 200) { Ok "C BEST TX/RX near-exact match" }
    else { Bad "C BEST TX/RX mismatch - clip not intact" }
    # audio ~ hold length
    if ($bestTx.AudioMs -ge ($TalkSeconds * 1000 - 500)) { Ok "C BEST audio length covers hold" }
    else { Bad ("C BEST audio too short: {0}ms for {1}s hold" -f $bestTx.AudioMs, $TalkSeconds) }
} else {
    Bad "C BEST missing CLIP_TX or CLIP_RX"
}

if (Has-Line $rxLogs "BEST_RX sync play|sync play start") {
    Ok "C BEST RX synced playback started"
} else {
    if (Has-Line $rxLogs "BEST_RX") { Ok "C BEST RX play activity seen" }
    else { Bad "C BEST RX synced play not logged" }
}

# --- D: Replay on TX (after BEST clip stored) + end signal ---
Info "D Replay on TX"
& $adb -s $tx logcat -c | Out-Null
Adb-Svc $tx "com.example.barky.TEST_REPLAY"
$txLogs = Wait-Log $tx "Replay sync play done|Replay finished" ($TalkSeconds + 4.0)
if ((Has-Line $txLogs "Replay start") -and (Has-Line $txLogs "sync=single_clock")) {
    Ok "D Replay uses single-clock sync"
} elseif (Has-Line $txLogs "Replay start") {
    Ok "D Replay started"
    Bad "D Replay missing sync=single_clock (old build?)"
} else { Bad "D Replay did not start" }

if (Has-Line $txLogs "Replay sync play done|Replay finished") {
    Ok "D Replay finished (button should leave Stop)"
} else { Bad "D Replay finish log missing" }

if (Has-Line $txLogs "NetworkOnMainThread") { Bad "D NetworkOnMainThread during replay" }
else { Ok "D no NetworkOnMainThread" }

# --- E: Replay on RX ---
Info "E Replay on RX"
& $adb -s $rx logcat -c | Out-Null
Adb-Svc $rx "com.example.barky.TEST_REPLAY"
$rxLogs = Wait-Log $rx "Replay sync play done|Replay finished" ($TalkSeconds + 4.0)
if (Has-Line $rxLogs "Replay start") { Ok "E RX Replay started" } else { Bad "E RX Replay missing" }
if (Has-Line $rxLogs "Replay finished|Replay sync play done") {
    Ok "E RX Replay finished"
} else { Bad "E RX Replay finish missing" }

# --- F: WAKEUP ---
Info "F WAKEUP"
foreach ($s in $devices) { & $adb -s $s logcat -c | Out-Null }
Adb-Svc $tx "com.example.barky.TEST_WAKEUP"
$pairF = Wait-LogsBoth $tx "TEST_WAKEUP|WAKEUP sent|sendWakeup|RING send" $rx "WAKEUP RING|RING incoming|onWakeupReceived|walkie_wakeup" 5.0
$txLogs = $pairF.Tx
$rxLogs = $pairF.Rx
if (Has-Line $txLogs "TEST_WAKEUP|WAKEUP sent|sendWakeup|RING send") {
    Ok "F WAKEUP sent from TX"
} else { Bad "F WAKEUP send log missing" }
if (Has-Line $rxLogs "WAKEUP RING|RING incoming|onWakeupReceived|WAKEUP|Wakeup|walkie_wakeup|Ringtone|ring") {
    Ok "F WAKEUP reached RX path"
} else { Bad "F no WAKEUP evidence on RX logs" }
# Stop wakeup alarm on both phones before starting section G LISTEN audio checks.
Adb-Svc $tx "com.example.barky.STOP_WAKEUP"
Adb-Svc $rx "com.example.barky.STOP_WAKEUP"
Start-Sleep -Milliseconds 300

Assert-NotTimedOut
# --- G: LISTEN both directions (must work on Pixel and Samsung, Wi-Fi & Cellular) ---
Info "G LISTEN RX hears TX mic"
foreach ($s in $devices) { & $adb -s $s logcat -c | Out-Null }
Adb-Svc $rx "com.example.barky.TEST_LISTEN_START"
$pairG = Wait-LogsBoth $tx "MONITOR_AUDIO_TX" $rx "MONITOR_AUDIO_RX|MONITOR_AUDIO" 6.0
$txLogs = $pairG.Tx
$rxLogs = $pairG.Rx
if (Has-Line $rxLogs "LISTEN_REQ") { Ok "G RX sent LISTEN_REQ" } else { Bad "G RX LISTEN_REQ missing" }
if (Has-Line $txLogs "LISTEN_REQ from|LISTEN serve") { Ok "G TX serving LISTEN" } else { Bad "G TX not serving LISTEN" }
if (Has-Line $txLogs "MONITOR_AUDIO_TX") { Ok "G TX MONITOR_AUDIO_TX" } else { Bad "G TX no MONITOR_AUDIO_TX" }
if (Has-Line $rxLogs "MONITOR_AUDIO_RX|MONITOR_AUDIO") { Ok "G RX got monitor audio" } else { Bad "G RX no monitor audio RX" }
Adb-Svc $rx "com.example.barky.TEST_LISTEN_STOP"
Start-Sleep -Milliseconds 300

Info "G2 LISTEN reverse TX hears RX mic"
foreach ($s in $devices) { & $adb -s $s logcat -c | Out-Null }
Adb-Svc $tx "com.example.barky.TEST_LISTEN_START"
$pairG2 = Wait-LogsBoth $tx "MONITOR_AUDIO_RX|MONITOR_AUDIO" $rx "MONITOR_AUDIO_TX" 6.0
$txLogs = $pairG2.Tx
$rxLogs = $pairG2.Rx
if (Has-Line $txLogs "LISTEN_REQ") { Ok "G2 TX sent LISTEN_REQ" } else { Bad "G2 TX LISTEN_REQ missing" }
if (Has-Line $rxLogs "LISTEN serve|LISTEN_REQ from") { Ok "G2 RX serving LISTEN" } else { Bad "G2 RX not serving LISTEN" }
if (Has-Line $rxLogs "MONITOR_AUDIO_TX") { Ok "G2 RX MONITOR_AUDIO_TX" } else { Bad "G2 RX no MONITOR_AUDIO_TX" }
if (Has-Line $txLogs "MONITOR_AUDIO_RX|MONITOR_AUDIO") { Ok "G2 TX got monitor audio" } else { Bad "G2 TX no monitor audio RX" }
Adb-Svc $tx "com.example.barky.TEST_LISTEN_STOP"
Start-Sleep -Milliseconds 300

# --- H: SEE both directions ---
Info "H SEE TX requests remote"
foreach ($s in $devices) { & $adb -s $s logcat -c | Out-Null }
Adb-Svc $tx "com.example.barky.TEST_SEE_START"
$pairH = Wait-LogsBoth $tx "SEE_REQ" $rx "SEE_REQ from|serve see|Camera\+mic open" 6.0
$txLogs = $pairH.Tx
$rxLogs = $pairH.Rx
if (Has-Line $txLogs "SEE_REQ") { Ok "H TX SEE_REQ" } else { Bad "H TX SEE_REQ missing" }
if (Has-Line $rxLogs "SEE_REQ from|serve see|Camera\+mic open") { Ok "H RX serving SEE" } else { Bad "H RX not serving SEE" }
Adb-Svc $tx "com.example.barky.TEST_SEE_STOP"
Start-Sleep -Milliseconds 300

Info "H2 SEE reverse"
foreach ($s in $devices) { & $adb -s $s logcat -c | Out-Null }
Adb-Svc $rx "com.example.barky.TEST_SEE_START"
$pairH2 = Wait-LogsBoth $tx "SEE_REQ from|serve see|Camera\+mic open" $rx "SEE_REQ" 6.0
$txLogs = $pairH2.Tx
$rxLogs = $pairH2.Rx
if (Has-Line $rxLogs "SEE_REQ") { Ok "H2 RX SEE_REQ" } else { Bad "H2 RX SEE_REQ missing" }
if (Has-Line $txLogs "SEE_REQ from|serve see|Camera\+mic open") { Ok "H2 TX serving SEE" } else { Bad "H2 TX not serving SEE" }
Adb-Svc $rx "com.example.barky.TEST_SEE_STOP"
Start-Sleep -Milliseconds 300

Assert-NotTimedOut
# --- I: GPS both directions ---
Info "I GPS both phones ON mutual share"
foreach ($s in $devices) { & $adb -s $s logcat -c | Out-Null }
Adb-Svc $tx "com.example.barky.TEST_GPS_ON"
Adb-Svc $rx "com.example.barky.TEST_GPS_ON"
# Fast poll up to 14s until both phones receive peer GPS coordinates (often completes in 1-3s).
$pairI = Wait-LogsBoth $tx "GPS_RX" $rx "GPS_RX" 14.0
$txLogs = $pairI.Tx
$rxLogs = $pairI.Rx
if (Has-Line $txLogs "GPS ON|GPS_TX") { Ok "I TX GPS on/tx" } else { Bad "I TX GPS not active" }
if (Has-Line $rxLogs "GPS ON|GPS_TX") { Ok "I RX GPS on/tx" } else { Bad "I RX GPS not active" }
if (Has-Line $txLogs "GPS_RX") { Ok "I TX received peer GPS" } else { Bad "I TX missing GPS_RX peer path" }
if (Has-Line $rxLogs "GPS_RX") { Ok "I RX received peer GPS" } else { Bad "I RX missing GPS_RX peer path" }
Adb-Svc $tx "com.example.barky.TEST_GPS_OFF"
Adb-Svc $rx "com.example.barky.TEST_GPS_OFF"

# --- I2: SCRN mode (decoupled from SEE, full-screen UI, remote tap & keyboard control) ---
Info "I2 SCRN full-screen & remote touch/keyboard control"
foreach ($s in $devices) {
    & $adb -s $s shell settings put secure enabled_accessibility_services "$Package/$Package.BarkyService" 2>$null | Out-Null
    & $adb -s $s shell settings put secure accessibility_enabled 1 2>$null | Out-Null
    # Wake screen and foreground MainActivity so UI listener is bound for SCRN_FULLSCREEN check.
    & $adb -s $s shell input keyevent KEYCODE_WAKEUP 2>$null | Out-Null
    & $adb -s $s shell am start -n $Activity 2>$null | Out-Null
}
Start-Sleep -Milliseconds 400
foreach ($s in $devices) {
    & $adb -s $s logcat -c | Out-Null
}
Adb-Svc $tx "com.example.barky.TEST_SCRN_START"
Wait-Log $tx "SCRN_FULLSCREEN enabled=true" 4.0 | Out-Null
& $adb -s $tx shell am startservice -n $Service -a "com.example.barky.TEST_REMOTE_TAP" --ef remote_x 0.5 --ef remote_y 0.5 | Out-Null
& $adb -s $tx shell am startservice -n $Service -a "com.example.barky.TEST_REMOTE_TEXT" --es remote_text "BarkyScrnTest" --ez remote_enter true | Out-Null
& $adb -s $tx shell am startservice -n $Service -a "com.example.barky.TEST_REMOTE_KEY" --ei remote_key 1 | Out-Null
$pairI2 = Wait-LogsBoth $tx "REMOTE_INPUT_TX cmd=KEY" $rx "REMOTE_INPUT_RX cmd=KEY" 4.0
$txLogs = $pairI2.Tx
$rxLogs = $pairI2.Rx
if (Has-Line $txLogs "SCREEN_REQ ONLY") { Ok "I2 TX started SCRN without enabling SEE" } else { Bad "I2 TX SCREEN_REQ ONLY missing" }
if (-not (Has-Line $txLogs "SEE_REQ via")) { Ok "I2 SCRN did not trigger SEE_REQ" } else { Bad "I2 SCRN unexpectedly triggered SEE_REQ" }
if (Has-Line $txLogs "SCRN_FULLSCREEN enabled=true") { Ok "I2 TX entered full-screen SCRN mode (buttons hidden)" } else { Bad "I2 TX SCRN_FULLSCREEN log missing" }
if (Has-Line $txLogs "REMOTE_INPUT_TX cmd=TAP") { Ok "I2 TX sent remote TAP packet" } else { Bad "I2 TX REMOTE_INPUT_TX TAP missing" }
if (Has-Line $rxLogs "REMOTE_INPUT_RX cmd=TAP") { Ok "I2 RX received remote TAP packet" } else { Bad "I2 RX REMOTE_INPUT_RX TAP missing" }
if (Has-Line $txLogs "REMOTE_INPUT_TX cmd=TEXT") { Ok "I2 TX sent remote keyboard TEXT packet" } else { Bad "I2 TX REMOTE_INPUT_TX TEXT missing" }
if (Has-Line $rxLogs "REMOTE_INPUT_RX cmd=TEXT") { Ok "I2 RX received remote keyboard TEXT packet" } else { Bad "I2 RX REMOTE_INPUT_RX TEXT missing" }
if (Has-Line $rxLogs "REMOTE_INPUT_RX cmd=KEY") { Ok "I2 RX received remote KEY packet" } else { Bad "I2 RX REMOTE_INPUT_RX KEY missing" }
Adb-Svc $tx "com.example.barky.TEST_SCRN_STOP"
Start-Sleep -Milliseconds 300

# --- J: Service alive / no fatal ---
Info "J Stability"
foreach ($s in $devices) {
    $m = (& $adb -s $s shell getprop ro.product.model).Trim()
    $logs = Get-Logs $s
    $fatal = $logs | Select-String -Pattern "FATAL EXCEPTION|AndroidRuntime: FATAL|Fatal signal" | Select-Object -Last 3
    $procId = (& $adb -s $s shell pidof $Package).Trim()
    if ($fatal) {
        Bad "J FATAL on $m"
        $fatal | ForEach-Object { Write-Output $_.Line }
    } else { Ok "J no FATAL on $m" }
    if ($procId) { Ok "J $m alive pid=$procId" } else { Bad "J $m process dead" }
}

if ($restoreWifiOnTx) {
    Info "Re-enabling Wi-Fi on $txM ($tx)..."
    & $adb -s $tx shell svc wifi enable 2>$null | Out-Null
}

Write-Output ("========== RESULTS pass={0} fail={1} ==========" -f $pass, $fail)
if ($fail -gt 0) { exit 1 } else { exit 0 }
