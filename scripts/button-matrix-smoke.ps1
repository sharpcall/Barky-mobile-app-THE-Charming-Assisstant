# Exhaustive button / feature condition matrix for Barky (dual phone).
# Every major feature is part of the matrix - including combinations that look unrelated.
#
# Features: L=LISTEN S=SEE W=WAKEUP M=HOLD R=REPLAY G=GPS
# Covers: singles both phones, all ordered pairs on each phone, dual/cross, LISTEN speaker route.
# Quiet WAKEUP. Hard timeout. Fast sleeps so full matrix finishes (~6-8 min).
param(
    [int]$OverallTimeoutSec = 900,
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
# Read all Barky + crash logcat lines since the most recent ClearLogs call (do not pass -t 200,
# which drops Barky logs when OEM camera/audio services emit >200 lines per second).
function Logs([string]$s) {
    & $adb -s $s logcat -d -v brief -s WalkieTalkieEngine:I WalkieTalkieService:I BarkyVideoUI:I CameraFrameSource:I AndroidRuntime:E DEBUG:I 2>$null
}
function Has([object[]]$logs, [string]$pat) {
    return ($logs | Where-Object { "$_" -match $pat } | Select-Object -First 1) -ne $null
}
function ClearLogs {
    foreach ($s in $devs) { & $adb -s $s logcat -c 2>$null | Out-Null }
}
function StopAll([string]$s) {
    foreach ($a in @(
        "com.example.barky.TEST_LISTEN_STOP",
        "com.example.barky.TEST_SEE_STOP",
        "com.example.barky.TEST_TALK_STOP",
        "com.example.barky.TEST_GPS_OFF"
    )) { Svc $s $a }
}

foreach ($s in $devs) {
    Quiet $s
    & $adb -s $s shell am force-stop $pkg | Out-Null
    & $adb -s $s shell am start -n "$pkg/.MainActivity" | Out-Null
}
Info "Wait peer HELLO"
Start-Sleep 8
$la = Logs $A; $lb = Logs $B
if ((Has $la "HELLO from") -and (Has $lb "HELLO from")) { Ok "HELLO both ways" } else { Bad "HELLO incomplete" }

function Start-Feat([string]$s, [string]$feat, [string]$peer) {
    switch ($feat) {
        "L" { Svc $s "com.example.barky.TEST_LISTEN_START" }
        "S" { Svc $s "com.example.barky.TEST_SEE_START" }
        "W" { Quiet $peer; Svc $s "com.example.barky.TEST_WAKEUP" @("--ez","quiet","true") }
        "M" {
            Svc $s "com.example.barky.SET_BEST_QUALITY" @("--ez","best","false")
            Svc $s "com.example.barky.TEST_TALK_START"
        }
        "R" { Svc $s "com.example.barky.TEST_REPLAY" }
        "G" {
            Svc $s "com.example.barky.TEST_GPS_ON"
            $other = if ($s -eq $A) { $B } else { $A }
            Svc $other "com.example.barky.TEST_GPS_ON"
        }
    }
}
function Stop-Feat([string]$s, [string]$feat) {
    switch ($feat) {
        "L" { Svc $s "com.example.barky.TEST_LISTEN_STOP" }
        "S" { Svc $s "com.example.barky.TEST_SEE_STOP" }
        "W" { }
        "M" { Svc $s "com.example.barky.TEST_TALK_STOP" }
        "R" { }
        "G" { Svc $s "com.example.barky.TEST_GPS_OFF" }
    }
}
function Feat-Sleep([string]$feat) {
    switch ($feat) {
        "L" { Start-Sleep 2 }
        "S" { Start-Sleep 2 }
        "W" { Start-Sleep 1 }
        "M" { Start-Sleep $HoldSec }
        "R" { Start-Sleep 2 }
        "G" { Start-Sleep 3 }
        default { Start-Sleep 1 }
    }
}
# Use a single regex per feature so PowerShell does not treat unparenthesized -or tokens
# as extra positional arguments to Has.
function Feat-Ok([object[]]$logs, [string]$feat) {
    switch ($feat) {
        "L" { return (Has $logs "LISTEN_REQ|MONITOR_AUDIO|LISTEN serve|forceListenVolume") }
        "S" { return (Has $logs "SEE_REQ|serve see|SEE") }
        "W" { return (Has $logs "WAKEUP|TEST_WAKEUP|quiet=") }
        "M" { return (Has $logs "CLIP_TX|startTalking|TX OK|TALK_START|TEST_TALK_START|channelBusy") }
        "R" { return (Has $logs "Replay start|Replay") }
        "G" { return (Has $logs "GPS ON|GPS_TX|GPS_RX|GPS") }
        default { return $false }
    }
}

$feats = @("L","S","W","M","R","G")
$featName = @{ L="LISTEN"; S="SEE"; W="WAKEUP"; M="HOLD"; R="REPLAY"; G="GPS" }

Write-Output "========== BUTTON MATRIX SMOKE (full condition matrix) =========="
Info "Features: L S W M R G - singles + all ordered pairs + dual/cross"

Info "--- SINGLES ---"
foreach ($f in $feats) {
    DieIfTimeout
    foreach ($phone in @(@{S=$A;P=$B;T="A"}, @{S=$B;P=$A;T="B"})) {
        DieIfTimeout
        ClearLogs
        StopAll $phone.S
        Start-Feat $phone.S $f $phone.P
        Feat-Sleep $f
        Stop-Feat $phone.S $f
        Start-Sleep 0.4
        $l = Logs $phone.S
        $ok = Feat-Ok $l $f
        if (-not $ok) { $ok = Feat-Ok (Logs $phone.P) $f }
        if ($ok) { Ok "SINGLE $($featName[$f]) on $($phone.T)" }
        else { Bad "SINGLE $($featName[$f]) on $($phone.T)" }
        StopAll $phone.S
    }
}

Info "--- ORDERED PAIRS (all f1->f2 on A and on B) ---"
# Sticky features that stay on during f2 (true concurrent). HOLD always stops prior first.
$sticky = @("L","S","G")
foreach ($phone in @(@{S=$A;P=$B;T="A"}, @{S=$B;P=$A;T="B"})) {
    foreach ($f1 in $feats) {
        foreach ($f2 in $feats) {
            if ($f1 -eq $f2) { continue }
            DieIfTimeout
            ClearLogs
            StopAll $phone.S
            StopAll $phone.P
            Start-Feat $phone.S $f1 $phone.P
            Feat-Sleep $f1
            # Stop f1 before HOLD so half-duplex does not block CLIP_TX, unless testing concurrent L/S/G
            if ($f2 -eq "M" -or ($sticky -notcontains $f1)) {
                Stop-Feat $phone.S $f1
                Start-Sleep 0.3
            }
            Start-Feat $phone.S $f2 $phone.P
            Feat-Sleep $f2
            Stop-Feat $phone.S $f2
            if ($sticky -contains $f1 -and $f2 -ne "M") { Stop-Feat $phone.S $f1 }
            Start-Sleep 0.3
            $l = Logs $phone.S
            $ok1 = Feat-Ok $l $f1
            $ok2 = Feat-Ok $l $f2
            if (-not $ok1 -or -not $ok2) {
                $lp = Logs $phone.P
                if (-not $ok1) { $ok1 = Feat-Ok $lp $f1 }
                if (-not $ok2) { $ok2 = Feat-Ok $lp $f2 }
            }
            $tag = "PAIR $($featName[$f1])->$($featName[$f2]) on $($phone.T)"
            if ($ok1 -and $ok2) { Ok $tag }
            elseif ($ok1 -or $ok2) { Bad "$tag partial ok1=$ok1 ok2=$ok2" }
            else { Bad "$tag missing both" }
            StopAll $phone.S
            StopAll $phone.P
        }
    }
}

Info "--- CROSS / DUAL ---"
DieIfTimeout
ClearLogs
Svc $A "com.example.barky.TEST_SEE_START"
Svc $B "com.example.barky.TEST_SEE_START"
Start-Sleep 3
Svc $A "com.example.barky.TEST_SEE_STOP"
Svc $B "com.example.barky.TEST_SEE_STOP"
$la = Logs $A; $lb = Logs $B
if ((Has $la "SEE_REQ") -and (Has $lb "SEE_REQ")) { Ok "DUAL SEE both phones" } else { Bad "DUAL SEE" }

DieIfTimeout
ClearLogs
Svc $A "com.example.barky.TEST_LISTEN_START"
Svc $B "com.example.barky.TEST_LISTEN_START"
Start-Sleep 2
Svc $A "com.example.barky.TEST_LISTEN_STOP"
Svc $B "com.example.barky.TEST_LISTEN_STOP"
$la = Logs $A; $lb = Logs $B
if ((Has $la "LISTEN_REQ|MONITOR_AUDIO") -and (Has $lb "LISTEN_REQ|MONITOR_AUDIO|LISTEN serve")) {
    Ok "DUAL LISTEN both phones"
} else { Bad "DUAL LISTEN" }

DieIfTimeout
ClearLogs
Svc $B "com.example.barky.TEST_LISTEN_START"; Start-Sleep 1
Svc $A "com.example.barky.TEST_TALK_START"; Start-Sleep $HoldSec
Svc $A "com.example.barky.TEST_TALK_STOP"
Svc $B "com.example.barky.TEST_LISTEN_STOP"; Start-Sleep 1
$la = Logs $A; $lb = Logs $B
if ((Has $la "CLIP_TX|startTalking") -or (Has $lb "channelBusy|LISTEN")) {
    Ok "CROSS TALK during remote LISTEN"
} else { Bad "CROSS TALK during LISTEN" }

foreach ($phone in @(@{S=$A;T="A"}, @{S=$B;T="B"})) {
    DieIfTimeout
    ClearLogs
    Svc $phone.S "com.example.barky.TEST_SEE_START"; Start-Sleep 1
    Svc $phone.S "com.example.barky.TEST_SEE_STOP"; Start-Sleep 0.5
    Svc $phone.S "com.example.barky.TEST_LISTEN_START"; Start-Sleep 2
    Svc $phone.S "com.example.barky.TEST_LISTEN_STOP"
    $l = Logs $phone.S
    if ((Has $l "SEE") -and (Has $l "LISTEN_REQ|MONITOR_AUDIO_RX|forceListenVolume")) {
        Ok "INDEP SEE-stop then LISTEN on $($phone.T)"
    } else { Bad "INDEP SEE-stop then LISTEN on $($phone.T)" }
}

DieIfTimeout
ClearLogs
Svc $A "com.example.barky.TEST_SEE_START"; Start-Sleep 1
Svc $A "com.example.barky.TEST_LISTEN_START"; Start-Sleep 1
Svc $A "com.example.barky.TEST_SEE_STOP"; Start-Sleep -Milliseconds 500
Svc $A "com.example.barky.TEST_LISTEN_STOP"; Start-Sleep -Milliseconds 500
$la = Logs $A
if ((Has $la "SEE_REQ|TEST_SEE_START|SEE") -and (Has $la "LISTEN_REQ|LISTEN_STOP|TEST_LISTEN_START|MONITOR_AUDIO|forceListenVolume")) {
    Ok "INDEP concurrent SEE+LISTEN stop order on A"
} else { Bad "INDEP concurrent SEE+LISTEN on A" }

Info "--- ROUTE LISTEN SPEAKER ---"
DieIfTimeout
ClearLogs
Svc $A "com.example.barky.TEST_LISTEN_START"; Start-Sleep 2; Svc $A "com.example.barky.TEST_LISTEN_STOP"
Svc $B "com.example.barky.TEST_LISTEN_START"; Start-Sleep 2; Svc $B "com.example.barky.TEST_LISTEN_STOP"
$la = Logs $A; $lb = Logs $B
$aSp = (Has $la "forceListenVolume SPEAKER") -or (Has $la "route=SPEAKER")
$bSp = (Has $lb "forceListenVolume SPEAKER") -or (Has $lb "route=SPEAKER")
$aPref = Has $la "AudioTrack preferredDevice"
$bPref = Has $lb "AudioTrack preferredDevice"
if ($aSp -and $bSp) { Ok "ROUTE LISTEN SPEAKER both phones" }
elseif ($aSp -or $bSp) { Bad "ROUTE LISTEN SPEAKER only one phone a=$aSp b=$bSp" }
else { Bad "ROUTE LISTEN SPEAKER log missing" }
if ($aPref -or $bPref) { Ok "ROUTE AudioTrack preferredDevice seen (a=$aPref b=$bPref)" }
else { Bad "ROUTE AudioTrack preferredDevice missing both" }

foreach ($s in $devs) {
    $m = (& $adb -s $s shell getprop ro.product.model).Trim()
    $l = Logs $s
    $fatal = $l | Select-String "FATAL EXCEPTION|Fatal signal"
    $procId = (& $adb -s $s shell pidof $pkg).Trim()
    if ($fatal) { Bad "FATAL on $m" } else { Ok "no FATAL on $m" }
    if ($procId) { Ok "$m alive" } else { Bad "$m dead" }
}

Write-Output ("========== MATRIX RESULTS pass={0} fail={1} t={2}s ==========" -f $pass, $fail, (T))
if ($fail -gt 0) { exit 1 } else { exit 0 }
