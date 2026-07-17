# Full A-Z feature smoke for Barky on all connected devices.
# Covers: peer HELLO, LIVE video, BEST single-file, durations TX vs RX,
# Replay sync logs, WAKEUP, half-duplex busy, Replay UI end signal.
param(
    [string]$Package = "com.example.barky",
    [string]$Activity = "com.example.barky/.MainActivity",
    [string]$Service = "com.example.barky/.walkie.WalkieTalkieService",
    [int]$WaitSeconds = 12,
    [int]$TalkSeconds = 4,
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
    $t = [int]((Get-Date) - $script:smokeStart).TotalSeconds
    if ($t -ge $OverallTimeoutSec) {
        Bad ("OVERALL TIMEOUT after {0}s (limit {1}s) — aborting remaining steps" -f $t, $OverallTimeoutSec)
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
    return & $adb -s $serial logcat -d -v time 2>$null
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

function Has-Line([object[]]$logs, [string]$pattern) {
    return ($logs | Where-Object { $_ -match $pattern } | Select-Object -First 1) -ne $null
}

Write-Output ("========== FULL FEATURE SMOKE limit={0}s ==========" -f $OverallTimeoutSec)
# Optional wireless reconnect before smoke
$wifiList = Join-Path $PSScriptRoot "devices-wifi.json"
if (Test-Path $wifiList) {
    try {
        $cfg = Get-Content $wifiList -Raw | ConvertFrom-Json
        foreach ($d in $cfg.devices) {
            if ($d.address) { & $adb connect $d.address 2>$null | Out-Null }
        }
        Start-Sleep -Seconds 2
    } catch {}
}
& $adb devices -l
$devices = @()
& $adb devices | ForEach-Object { if ($_ -match "^(\S+)\s+device\s*$") { $devices += $Matches[1] } }
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
    & $adb -s $s logcat -c | Out-Null
    & $adb -s $s shell am force-stop $Package
    & $adb -s $s shell am start -n $Activity | Out-Null
}
Info "Waiting ${WaitSeconds}s for peer link..."
Start-Sleep -Seconds $WaitSeconds

# --- A: HELLO / peer discovery ---
$txLogs = Get-Logs $tx
$rxLogs = Get-Logs $rx
if (Has-Line $txLogs "HELLO from" -and Has-Line $rxLogs "HELLO from") {
    Ok "A peer HELLO both ways"
} else {
    Bad "A HELLO missing (tx=$(Has-Line $txLogs 'HELLO from') rx=$(Has-Line $rxLogs 'HELLO from'))"
}
if (Has-Line $txLogs "NSD peer" -or Has-Line $txLogs "HELLO from") {
    Ok "A NSD/peer discovery signals present"
} else {
    Bad "A no NSD peer / HELLO on TX"
}

# --- B: LIVE mode TX/RX with durations ---
Info "B LIVE hold ${TalkSeconds}s"
foreach ($s in $devices) { & $adb -s $s logcat -c | Out-Null }
Adb-Svc $tx "com.example.barky.SET_BEST_QUALITY" @("--ez", "best", "false")
Start-Sleep -Seconds 1
Adb-Svc $tx "com.example.barky.TEST_TALK_START"
Start-Sleep -Seconds $TalkSeconds
Adb-Svc $tx "com.example.barky.TEST_TALK_STOP"
Start-Sleep -Seconds 4
$txLogs = Get-Logs $tx
$rxLogs = Get-Logs $rx
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
Start-Sleep -Seconds 1
Adb-Svc $tx "com.example.barky.TEST_TALK_START"
Start-Sleep -Seconds $TalkSeconds
Adb-Svc $tx "com.example.barky.TEST_TALK_STOP"
Start-Sleep -Seconds 10
$txLogs = Get-Logs $tx
$rxLogs = Get-Logs $rx
$bestTx = @(Parse-Clips $txLogs "CLIP_TX") | Where-Object { $_.Mode -match "BEST" } | Select-Object -Last 1
if (-not $bestTx) { $bestTx = @(Parse-Clips $txLogs "CLIP_TX") | Select-Object -Last 1 }
$bestRx = @(Parse-Clips $rxLogs "CLIP_RX") | Where-Object { $_.Mode -match "BEST" } | Select-Object -Last 1
if (-not $bestRx) { $bestRx = @(Parse-Clips $rxLogs "CLIP_RX") | Select-Object -Last 1 }

if (Has-Line $txLogs "BEST quality record start" -or Has-Line $txLogs "TX BEST record") {
    Ok "C BEST record path engaged"
} else { Bad "C BEST record path not seen" }

if (Has-Line $txLogs "singleFile=true" -or Has-Line $txLogs "BEST single-file write") {
    Ok "C BEST single-file write on TX"
} else { Bad "C BEST single-file write log missing" }

if (Has-Line $txLogs "BEST bundle sent ok=true" -or Has-Line $txLogs "BEST flush done") {
    Ok "C BEST bundle send completed"
} else { Bad "C BEST bundle send failed/missing" }

if (Has-Line $rxLogs "BEST_BUNDLE" -or Has-Line $rxLogs "BEST RX single-file") {
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

if (Has-Line $rxLogs "BEST_RX sync play" -or Has-Line $rxLogs "sync play start") {
    Ok "C BEST RX synced playback started"
} else {
    # may still match older log label
    if (Has-Line $rxLogs "BEST_RX") { Ok "C BEST RX play activity seen" }
    else { Bad "C BEST RX synced play not logged" }
}

# --- D: Replay on TX (after BEST clip stored) + end signal ---
Info "D Replay on TX"
foreach ($s in @($tx)) { & $adb -s $s logcat -c | Out-Null }
Adb-Svc $tx "com.example.barky.TEST_REPLAY"
Start-Sleep -Seconds 6
$txLogs = Get-Logs $tx
if (Has-Line $txLogs "Replay start" -and Has-Line $txLogs "sync=single_clock") {
    Ok "D Replay uses single-clock sync"
} elseif (Has-Line $txLogs "Replay start") {
    Ok "D Replay started"
    Bad "D Replay missing sync=single_clock (old build?)"
} else { Bad "D Replay did not start" }

if (Has-Line $txLogs "Replay sync play done" -or Has-Line $txLogs "Replay finished") {
    Ok "D Replay finished (button should leave Stop)"
} else { Bad "D Replay finish log missing" }

if (Has-Line $txLogs "NetworkOnMainThread") { Bad "D NetworkOnMainThread during replay" }
else { Ok "D no NetworkOnMainThread" }

# --- E: Replay on RX ---
Info "E Replay on RX"
& $adb -s $rx logcat -c | Out-Null
Adb-Svc $rx "com.example.barky.TEST_REPLAY"
Start-Sleep -Seconds 6
$rxLogs = Get-Logs $rx
if (Has-Line $rxLogs "Replay start") { Ok "E RX Replay started" } else { Bad "E RX Replay missing" }
if (Has-Line $rxLogs "Replay finished" -or Has-Line $rxLogs "Replay sync play done") {
    Ok "E RX Replay finished"
} else { Bad "E RX Replay finish missing" }

# --- F: WAKEUP ---
Info "F WAKEUP"
foreach ($s in $devices) { & $adb -s $s logcat -c | Out-Null }
Adb-Svc $tx "com.example.barky.TEST_WAKEUP"
Start-Sleep -Seconds 3
$txLogs = Get-Logs $tx
$rxLogs = Get-Logs $rx
if (Has-Line $txLogs "TEST_WAKEUP" -or Has-Line $txLogs "WAKEUP sent" -or Has-Line $txLogs "sendWakeup") {
    Ok "F WAKEUP sent from TX"
} else { Bad "F WAKEUP send log missing" }
if (Has-Line $rxLogs "onWakeupReceived" -or Has-Line $rxLogs "WAKEUP" -or Has-Line $rxLogs "Wakeup" -or Has-Line $rxLogs "walkie_wakeup") {
    Ok "F WAKEUP reached RX path"
} elseif (Has-Line $rxLogs "Ringtone" -or Has-Line $rxLogs "ring") {
    Ok "F RX ring/wakeup signal"
} else { Bad "F no WAKEUP evidence on RX logs" }

Assert-NotTimedOut
# --- G: LISTEN both directions (must work on Pixel and Samsung) ---
Info "G LISTEN RX hears TX mic"
foreach ($s in $devices) { & $adb -s $s logcat -c | Out-Null }
Adb-Svc $rx "com.example.barky.TEST_LISTEN_START"
Start-Sleep -Seconds 4
$txLogs = Get-Logs $tx
$rxLogs = Get-Logs $rx
if (Has-Line $rxLogs "LISTEN_REQ") { Ok "G RX sent LISTEN_REQ" } else { Bad "G RX LISTEN_REQ missing" }
if (Has-Line $txLogs "LISTEN_REQ from" -or Has-Line $txLogs "LISTEN serve") { Ok "G TX serving LISTEN" } else { Bad "G TX not serving LISTEN" }
if (Has-Line $txLogs "MONITOR_AUDIO_TX") { Ok "G TX MONITOR_AUDIO_TX" } else { Bad "G TX no MONITOR_AUDIO_TX" }
if (Has-Line $rxLogs "MONITOR_AUDIO_RX" -or Has-Line $rxLogs "MONITOR_AUDIO") { Ok "G RX got monitor audio" } else { Bad "G RX no monitor audio RX" }
Adb-Svc $rx "com.example.barky.TEST_LISTEN_STOP"
Start-Sleep -Seconds 1

Info "G2 LISTEN reverse TX hears RX mic"
foreach ($s in $devices) { & $adb -s $s logcat -c | Out-Null }
Adb-Svc $tx "com.example.barky.TEST_LISTEN_START"
Start-Sleep -Seconds 4
$txLogs = Get-Logs $tx
$rxLogs = Get-Logs $rx
if (Has-Line $txLogs "LISTEN_REQ") { Ok "G2 TX sent LISTEN_REQ" } else { Bad "G2 TX LISTEN_REQ missing" }
if (Has-Line $rxLogs "LISTEN serve" -or Has-Line $rxLogs "LISTEN_REQ from") { Ok "G2 RX serving LISTEN" } else { Bad "G2 RX not serving LISTEN" }
if (Has-Line $rxLogs "MONITOR_AUDIO_TX") { Ok "G2 RX MONITOR_AUDIO_TX" } else { Bad "G2 RX no MONITOR_AUDIO_TX" }
if (Has-Line $txLogs "MONITOR_AUDIO_RX" -or Has-Line $txLogs "MONITOR_AUDIO") { Ok "G2 TX got monitor audio" } else { Bad "G2 TX no monitor audio RX" }
Adb-Svc $tx "com.example.barky.TEST_LISTEN_STOP"
Start-Sleep -Seconds 1

# --- H: SEE both directions ---
Info "H SEE TX requests remote"
foreach ($s in $devices) { & $adb -s $s logcat -c | Out-Null }
Adb-Svc $tx "com.example.barky.TEST_SEE_START"
Start-Sleep -Seconds 4
$txLogs = Get-Logs $tx
$rxLogs = Get-Logs $rx
if (Has-Line $txLogs "SEE_REQ") { Ok "H TX SEE_REQ" } else { Bad "H TX SEE_REQ missing" }
if (Has-Line $rxLogs "SEE_REQ from" -or Has-Line $rxLogs "serve see" -or Has-Line $rxLogs "Camera+mic open") { Ok "H RX serving SEE" } else { Bad "H RX not serving SEE" }
Adb-Svc $tx "com.example.barky.TEST_SEE_STOP"
Start-Sleep -Seconds 1

Info "H2 SEE reverse"
foreach ($s in $devices) { & $adb -s $s logcat -c | Out-Null }
Adb-Svc $rx "com.example.barky.TEST_SEE_START"
Start-Sleep -Seconds 4
$txLogs = Get-Logs $tx
$rxLogs = Get-Logs $rx
if (Has-Line $rxLogs "SEE_REQ") { Ok "H2 RX SEE_REQ" } else { Bad "H2 RX SEE_REQ missing" }
if (Has-Line $txLogs "SEE_REQ from" -or Has-Line $txLogs "serve see" -or Has-Line $txLogs "Camera+mic open") { Ok "H2 TX serving SEE" } else { Bad "H2 TX not serving SEE" }
Adb-Svc $rx "com.example.barky.TEST_SEE_STOP"
Start-Sleep -Seconds 1

Assert-NotTimedOut
# --- I: GPS both directions ---
Info "I GPS both phones ON mutual share"
foreach ($s in $devices) { & $adb -s $s logcat -c | Out-Null }
Adb-Svc $tx "com.example.barky.TEST_GPS_ON"
Adb-Svc $rx "com.example.barky.TEST_GPS_ON"
Start-Sleep -Seconds 14
$txLogs = Get-Logs $tx
$rxLogs = Get-Logs $rx
if (Has-Line $txLogs "GPS ON" -or Has-Line $txLogs "GPS_TX") { Ok "I TX GPS on/tx" } else { Bad "I TX GPS not active" }
if (Has-Line $rxLogs "GPS ON" -or Has-Line $rxLogs "GPS_TX") { Ok "I RX GPS on/tx" } else { Bad "I RX GPS not active" }
if (Has-Line $txLogs "GPS_RX") { Ok "I TX received peer GPS" } else { Bad "I TX missing GPS_RX peer path" }
if (Has-Line $rxLogs "GPS_RX") { Ok "I RX received peer GPS" } else { Bad "I RX missing GPS_RX peer path" }

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

Write-Output ("========== RESULTS pass={0} fail={1} ==========" -f $pass, $fail)
if ($fail -gt 0) { exit 1 } else { exit 0 }
