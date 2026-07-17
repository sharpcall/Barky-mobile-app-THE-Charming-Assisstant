# Exhaustive button / feature matrix smoke for Barky (dual phone).
# Buttons: L=LISTEN, S=SEE, W=WAKEUP, M=MIC(talk), R=REPLAY, F=FLIP (local), G=GPS
# Runs ordered pairs A then B, and reverse; reports PASS/FAIL with hard timeout.
param(
    [int]$OverallTimeoutSec = 420,
    [int]$HoldSec = 2
)

$ErrorActionPreference = "Continue"
$start = Get-Date
function T { [int]((Get-Date) - $start).TotalSeconds }
function Info([string]$m) { Write-Output ("INFO [t={0}s] {1}" -f (T), $m) }
function Ok([string]$m) { $script:pass++; Write-Output "PASS: $m" }
function Bad([string]$m) { $script:fail++; Write-Output "FAIL: $m" }
function DieIfTimeout {
    if ((T) -ge $OverallTimeoutSec) {
        Bad ("TIMEOUT after {0}s" -f (T))
        Write-Output ("========== MATRIX RESULTS pass={0} fail={1} ==========" -f $pass, $fail)
        exit 3
    }
}

$pass = 0; $fail = 0
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$pkg = "com.example.barky"
$svc = "$pkg/.walkie.WalkieTalkieService"

# Discover wireless endpoints
foreach ($line in (& $adb mdns services 2>&1)) {
    if ("$line" -match "(\d+\.\d+\.\d+\.\d+):(\d+)") {
        & $adb connect "$($Matches[1]):$($Matches[2])" 2>&1 | Out-Null
    }
}
Start-Sleep 1
$raw = @(& $adb devices | Select-String "device$" | ForEach-Object { ($_ -split "\s+")[0] })
$devs = @(); $seen = @{}
foreach ($s in $raw) {
    $id = (& $adb -s $s shell getprop ro.serialno 2>$null).Trim()
    if (-not $id) { $id = $s }
    if (-not $seen.ContainsKey($id)) { $seen[$id] = $s; $devs += $s }
}
if ($devs.Count -lt 2) {
    Bad "Need 2 phones (found $($devs.Count))"
    exit 2
}
$A = $devs[0]; $B = $devs[1]
$Am = (& $adb -s $A shell getprop ro.product.model).Trim()
$Bm = (& $adb -s $B shell getprop ro.product.model).Trim()
Info "A=$Am ($A)  B=$Bm ($B)"

function Quiet([string]$s) {
    foreach ($st in 1,2,3,4,5) {
        & $adb -s $s shell media volume --show --stream $st --set 2 2>$null | Out-Null
    }
}
function Svc([string]$s, [string]$action, [string[]]$extra = @()) {
    $args = @("-s", $s, "shell", "am", "start-foreground-service", "-n", $svc, "-a", $action) + $extra
    & $adb @args 2>$null | Out-Null
}
function Logs([string]$s) { & $adb -s $s logcat -d -v brief 2>$null }
function Has([object[]]$logs, [string]$pat) {
    return ($logs | Where-Object { "$_" -match $pat } | Select-Object -First 1) -ne $null
}
function ClearLogs {
    foreach ($s in $devs) { & $adb -s $s logcat -c | Out-Null }
}

foreach ($s in $devs) {
    Quiet $s
    & $adb -s $s shell am force-stop $pkg | Out-Null
    & $adb -s $s shell am start -n "$pkg/.MainActivity" | Out-Null
}
Info "Wait peer HELLO"
Start-Sleep 10
$la = Logs $A; $lb = Logs $B
if (Has $la "HELLO from" -and Has $lb "HELLO from") { Ok "HELLO both ways" } else { Bad "HELLO incomplete" }

# --- Helper scenarios ---
function Test-Listen([string]$req, [string]$srv, [string]$tag) {
    DieIfTimeout
    ClearLogs
    Svc $req "com.example.barky.TEST_LISTEN_START"
    Start-Sleep 4
    Svc $req "com.example.barky.TEST_LISTEN_STOP"
    Start-Sleep 1
    $lr = Logs $req; $ls = Logs $srv
    $okReq = Has $lr "LISTEN_REQ" -or Has $lr "MONITOR_AUDIO_RX"
    $okSrv = Has $ls "LISTEN_REQ from" -or Has $ls "LISTEN serve" -or Has $ls "MONITOR_AUDIO_TX"
    $okRx = Has $lr "MONITOR_AUDIO_RX"
    if ($okReq -and $okSrv -and $okRx) { Ok "$tag LISTEN audio path" }
    elseif ($okReq -and $okSrv) { Bad "$tag LISTEN control ok but no MONITOR_AUDIO_RX" }
    else { Bad "$tag LISTEN failed req=$okReq srv=$okSrv rx=$okRx" }
}

function Test-See([string]$req, [string]$srv, [string]$tag) {
    DieIfTimeout
    ClearLogs
    Svc $req "com.example.barky.TEST_SEE_START"
    Start-Sleep 4
    Svc $req "com.example.barky.TEST_SEE_STOP"
    Start-Sleep 1
    $lr = Logs $req; $ls = Logs $srv
    $ok = (Has $lr "SEE_REQ") -and (Has $ls "SEE_REQ from" -or Has $ls "serve see" -or Has $ls "Camera+mic open" -or Has $ls "SEE")
    if ($ok) { Ok "$tag SEE path" } else { Bad "$tag SEE missing logs" }
}

function Test-Talk([string]$tx, [string]$rx, [string]$tag) {
    DieIfTimeout
    ClearLogs
    Svc $tx "com.example.barky.SET_BEST_QUALITY" @("--ez","best","false")
    Start-Sleep 1
    Svc $tx "com.example.barky.TEST_TALK_START"
    Start-Sleep $HoldSec
    Svc $tx "com.example.barky.TEST_TALK_STOP"
    Start-Sleep 3
    $lt = Logs $tx; $lr = Logs $rx
    if ((Has $lt "CLIP_TX" -or Has $lt "TX OK") -and (Has $lr "CLIP_RX" -or Has $lr "RX OK" -or Has $lr "channelBusy")) {
        Ok "$tag TALK/HOLD"
    } else { Bad "$tag TALK/HOLD incomplete" }
}

function Test-Wake([string]$from, [string]$to, [string]$tag) {
    DieIfTimeout
    ClearLogs
    Quiet $to
    Svc $from "com.example.barky.TEST_WAKEUP" @("--ez","quiet","true")
    Start-Sleep 2
    $lf = Logs $from; $lt = Logs $to
    if ((Has $lf "WAKEUP" -or Has $lf "TEST_WAKEUP") -and (Has $lt "WAKEUP" -or Has $lt "Wakeup" -or Has $lt "quiet=")) {
        Ok "$tag WAKEUP quiet packet"
    } else { Bad "$tag WAKEUP path" }
}

function Test-Replay([string]$s, [string]$tag) {
    DieIfTimeout
    ClearLogs
    Svc $s "com.example.barky.TEST_REPLAY"
    Start-Sleep 3
    $l = Logs $s
    if (Has $l "Replay start" -or Has $l "Replay") { Ok "$tag REPLAY" } else { Bad "$tag REPLAY" }
}

function Test-Gps([string]$tag) {
    DieIfTimeout
    ClearLogs
    Svc $A "com.example.barky.TEST_GPS_ON"
    Svc $B "com.example.barky.TEST_GPS_ON"
    Start-Sleep 8
    $la = Logs $A; $lb = Logs $B
    $aOk = Has $la "GPS ON" -or Has $la "GPS_TX" -or Has $la "GPS_RX"
    $bOk = Has $lb "GPS ON" -or Has $lb "GPS_TX" -or Has $lb "GPS_RX"
    if ($aOk -and $bOk) { Ok "$tag GPS both active" } else { Bad "$tag GPS a=$aOk b=$bOk" }
}

Write-Output "========== BUTTON MATRIX SMOKE =========="
# Singles
Test-Listen $A $B "A->B"
Test-Listen $B $A "B->A"
Test-See $A $B "A->B"
Test-See $B $A "B->A"
Test-Talk $A $B "A->B"
Test-Talk $B $A "B->A"
Test-Wake $A $B "A->B"
Test-Wake $B $A "B->A"
Test-Replay $A "A"
Test-Replay $B "B"
Test-Gps "G"

# Ordered pairs (A then B actions on same phone)
Info "Pairs: SEE then LISTEN on A"
ClearLogs
Svc $A "com.example.barky.TEST_SEE_START"; Start-Sleep 2
Svc $A "com.example.barky.TEST_LISTEN_START"; Start-Sleep 2
Svc $A "com.example.barky.TEST_SEE_STOP"; Start-Sleep 1
$la = Logs $A
# LISTEN should still be stoppable independently
Svc $A "com.example.barky.TEST_LISTEN_STOP"; Start-Sleep 1
$la2 = Logs $A
if ((Has $la "SEE_REQ") -and (Has $la "LISTEN_REQ" -or Has $la2 "LISTEN_STOP")) {
    Ok "PAIR SEE then LISTEN independent stop on A"
} else { Bad "PAIR SEE then LISTEN on A" }

Info "Pairs: LISTEN then SEE on A"
ClearLogs
Svc $A "com.example.barky.TEST_LISTEN_START"; Start-Sleep 2
Svc $A "com.example.barky.TEST_SEE_START"; Start-Sleep 2
Svc $A "com.example.barky.TEST_LISTEN_STOP"; Start-Sleep 1
Svc $A "com.example.barky.TEST_SEE_STOP"; Start-Sleep 1
$la = Logs $A
if (Has $la "LISTEN_REQ" -and Has $la "SEE_REQ") { Ok "PAIR LISTEN then SEE on A" } else { Bad "PAIR LISTEN then SEE on A" }

Info "Pairs: dual SEE both phones"
ClearLogs
Svc $A "com.example.barky.TEST_SEE_START"
Svc $B "com.example.barky.TEST_SEE_START"
Start-Sleep 4
Svc $A "com.example.barky.TEST_SEE_STOP"
Svc $B "com.example.barky.TEST_SEE_STOP"
Start-Sleep 1
$la = Logs $A; $lb = Logs $B
if ((Has $la "SEE_REQ") -and (Has $lb "SEE_REQ")) { Ok "PAIR dual SEE both phones" } else { Bad "PAIR dual SEE" }

Info "Pairs: TALK while other LISTENs"
ClearLogs
Svc $B "com.example.barky.TEST_LISTEN_START"
Start-Sleep 1
Svc $A "com.example.barky.TEST_TALK_START"
Start-Sleep $HoldSec
Svc $A "com.example.barky.TEST_TALK_STOP"
Svc $B "com.example.barky.TEST_LISTEN_STOP"
Start-Sleep 2
$la = Logs $A; $lb = Logs $B
if (Has $la "CLIP_TX" -or Has $la "startTalking" -or Has $lb "channelBusy" -or Has $lb "LISTEN") {
    Ok "PAIR TALK during remote LISTEN (no crash)"
} else { Bad "PAIR TALK during LISTEN" }

Info "Pairs: SEE then WAKEUP quiet"
ClearLogs
Svc $A "com.example.barky.TEST_SEE_START"; Start-Sleep 2
Svc $A "com.example.barky.TEST_WAKEUP" @("--ez","quiet","true"); Start-Sleep 2
Svc $A "com.example.barky.TEST_SEE_STOP"; Start-Sleep 1
$la = Logs $A; $lb = Logs $B
if ((Has $la "SEE") -and (Has $la "WAKEUP" -or Has $lb "WAKEUP" -or Has $lb "quiet=")) {
    Ok "PAIR SEE then WAKEUP"
} else { Bad "PAIR SEE then WAKEUP" }

Info "Pairs: GPS + LISTEN"
ClearLogs
Svc $A "com.example.barky.TEST_GPS_ON"
Svc $B "com.example.barky.TEST_GPS_ON"
Svc $A "com.example.barky.TEST_LISTEN_START"
Start-Sleep 4
Svc $A "com.example.barky.TEST_LISTEN_STOP"
$la = Logs $A
if ((Has $la "GPS" -or Has $la "GPS_TX" -or Has $la "GPS_RX") -and (Has $la "LISTEN" -or Has $la "MONITOR_AUDIO_RX")) {
    Ok "PAIR GPS+LISTEN on A"
} else { Bad "PAIR GPS+LISTEN" }

# Fatal check
foreach ($s in $devs) {
    $m = (& $adb -s $s shell getprop ro.product.model).Trim()
    $l = Logs $s
    $fatal = $l | Select-String "FATAL EXCEPTION|Fatal signal"
    $pid = (& $adb -s $s shell pidof $pkg).Trim()
    if ($fatal) { Bad "FATAL on $m" } else { Ok "no FATAL on $m" }
    if ($pid) { Ok "$m alive" } else { Bad "$m dead" }
}

Write-Output ("========== MATRIX RESULTS pass={0} fail={1} t={2}s ==========" -f $pass, $fail, (T))
if ($fail -gt 0) { exit 1 } else { exit 0 }
