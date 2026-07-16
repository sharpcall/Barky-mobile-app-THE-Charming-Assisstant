# Full A-Z feature smoke for Barky on all connected devices.
# Covers: peer HELLO, LIVE video, BEST single-file, durations TX vs RX,
# Replay sync logs, WAKEUP, half-duplex busy, Replay UI end signal.
param(
    [string]$Package = "com.example.barky",
    [string]$Activity = "com.example.barky/.MainActivity",
    [string]$Service = "com.example.barky/.walkie.WalkieTalkieService",
    [int]$WaitSeconds = 12,
    [int]$TalkSeconds = 4
)

$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$fail = 0
$pass = 0

function Ok([string]$msg) { $script:pass++; Write-Output "PASS: $msg" }
function Bad([string]$msg) { $script:fail++; Write-Output "FAIL: $msg" }
function Info([string]$msg) { Write-Output "INFO: $msg" }

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

Write-Output "========== FULL FEATURE SMOKE =========="
& $adb devices -l
$devices = @()
& $adb devices | ForEach-Object { if ($_ -match "^(\S+)\s+device\s*$") { $devices += $Matches[1] } }
if ($devices.Count -lt 2) {
    Bad "Need 2 devices for full dual-phone smoke (found $($devices.Count))"
    exit 2
}
$tx = $devices[0]
$rx = $devices[1]
$txM = (& $adb -s $tx shell getprop ro.product.model).Trim()
$rxM = (& $adb -s $rx shell getprop ro.product.model).Trim()
Info "TX=$txM  RX=$rxM"

foreach ($s in $devices) {
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

# --- G: Service alive / no fatal ---
Info "G Stability"
foreach ($s in $devices) {
    $m = (& $adb -s $s shell getprop ro.product.model).Trim()
    $logs = Get-Logs $s
    $fatal = $logs | Select-String -Pattern "FATAL EXCEPTION|AndroidRuntime: FATAL" | Select-Object -Last 3
    if ($fatal) {
        Bad "G FATAL on $m"
        $fatal | ForEach-Object { Write-Output $_.Line }
    } else { Ok "G no FATAL on $m" }
}

Write-Output "========== RESULTS pass=$pass fail=$fail =========="
if ($fail -gt 0) { exit 1 } else { exit 0 }
