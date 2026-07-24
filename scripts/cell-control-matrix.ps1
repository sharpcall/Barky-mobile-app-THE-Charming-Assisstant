# Cellular control/media matrix: Pixel(cell USB) <-> S20(Wi‑Fi wireless adb)
# Confirms RING / LISTEN / SEE by comparing BOTH ends' logcat (agent-only).
# Usage: powershell -File scripts/cell-control-matrix.ps1
# Requires: Pixel on cellular (Wi‑Fi off), S20 Wi‑Fi on, hub up, quiet volumes.

$ErrorActionPreference = 'Continue'
$env:Path = "C:\Users\Mary Sparky\AppData\Local\Android\Sdk\platform-tools;" + $env:Path
$pkg = 'com.example.barky'
$pix = '38091FDJH00KU3'
$BudgetSec = 100
$deadline = (Get-Date).AddSeconds($BudgetSec)
function Left { [math]::Max(0, [int](($deadline - (Get-Date)).TotalSeconds)) }

function Ensure-S20 {
    $devs = adb devices
    $s = ($devs | Select-String 'RFCN1073|192\.168\.4\.231' | ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1)
    if ($s) { return $s }
    $md = adb mdns services 2>&1 | Out-String
    $m = [regex]::Match($md, '(\d+\.\d+\.\d+\.\d+):(\d+)')
    if ($m.Success) {
        $a = "$($m.Groups[1].Value):$($m.Groups[2].Value)"
        adb connect $a | Out-Null
        Start-Sleep 1
    }
    return ($devs = adb devices; ($devs | Select-String 'RFCN1073|192\.168\.4\.231' | ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1))
}

function Svc($dev, $action, $extras = '') {
    if ($extras) {
        Invoke-Expression "adb -s `"$dev`" shell am start-foreground-service -n $pkg/.walkie.WalkieTalkieService -a $action $extras"
    } else {
        adb -s $dev shell am start-foreground-service -n $pkg/.walkie.WalkieTalkieService -a $action
    }
}

Write-Host "BUDGET ${BudgetSec}s cell-control-matrix left=$(Left)"
$s20 = Ensure-S20
if (-not $s20) {
    Write-Host 'FAIL: S20 adb not connected (toggle Wireless debugging ON)'
    exit 3
}
Write-Host "devices pix=$pix s20=$s20"

foreach ($s in @($pix, $s20)) {
    adb -s $s shell media volume --stream 3 --set 1 2>$null
}
adb -s $pix shell svc wifi disable
adb -s $pix shell am force-stop $pkg
adb -s $s20 shell am force-stop $pkg
adb -s $pix shell am start -n $pkg/.MainActivity | Out-Null
adb -s $s20 shell am start -n $pkg/.MainActivity | Out-Null
Start-Sleep 10

adb -s $pix logcat -c; adb -s $s20 logcat -c
Svc $pix com.example.barky.FIREBASE_LIST
Svc $s20 com.example.barky.FIREBASE_LIST
Start-Sleep 6
$pfb = adb -s $pix logcat -d -t 250 2>&1 | Out-String
$sfb = adb -s $s20 logcat -d -t 250 2>&1 | Out-String
$pixId = [regex]::Match($pfb, 'registered db=\S+ id=(\S+)').Groups[1].Value
$s20Id = [regex]::Match($sfb, 'registered db=\S+ id=(\S+)').Groups[1].Value
$s20Pub = [regex]::Match($pfb, 'FIREBASE_PEER samsung SM-G981W id=\S+ local=\S+ public=([^:\s]+):(\d+)')
$pixPub = [regex]::Match($sfb, 'FIREBASE_PEER Google Pixel 8 id=\S+ local=\S+ public=([^:\s]+):(\d+)')
$s20H = $s20Pub.Groups[1].Value; $s20U = [int]$s20Pub.Groups[2].Value
$pixH = $pixPub.Groups[1].Value; $pixU = [int]$pixPub.Groups[2].Value
Write-Host "ids pix=$pixId s20=$s20Id pubs s20=$s20H`:$s20U pix=$pixH`:$pixU"
if (-not $pixId -or -not $s20Id -or $s20U -le 0 -or $pixU -le 0) {
    Write-Host 'FAIL: firebase parse'
    exit 2
}

Svc $pix com.example.barky.ADOPT_PEER "--es peer_name S20 --es peer_host $s20H --ei peer_udp $s20U --ei peer_video $s20U --es peer_device_id $s20Id"
Svc $s20 com.example.barky.ADOPT_PEER "--es peer_name Pixel8 --es peer_host $pixH --ei peer_udp $pixU --ei peer_video $pixU --es peer_device_id $pixId"
Start-Sleep 3

# RING — need RING_ACK on Pixel (proves S20 handled WAKEUP + replied via hub)
adb -s $pix logcat -c; adb -s $s20 logcat -c
Svc $pix com.example.barky.TEST_WAKEUP '--ez quiet true'
Start-Sleep 6
$pt = adb -s $pix logcat -d -t 180 2>&1 | Out-String
$st = adb -s $s20 logcat -d -t 180 2>&1 | Out-String
$ring = $pt -match 'RING_ACK'
Write-Host "RING pix_ack=$ring s20_rx=$($st -match 'WAKEUP|RING|type=0x2')"
Write-Host (($pt -split "`n" | Select-String 'RING_ACK|WAKEUP' | Select-Object -Last 4).Line)
Write-Host (($st -split "`n" | Select-String 'WAKEUP|RING|type=0x2|relay DELIVER' | Select-Object -Last 6).Line)

# LISTEN — need LISTEN_REQ on S20 + CTRL sent on Pixel
adb -s $pix logcat -c; adb -s $s20 logcat -c
Svc $pix com.example.barky.TEST_LISTEN_START
Start-Sleep 12
$pl = adb -s $pix logcat -d -t 220 2>&1 | Out-String
$sl = adb -s $s20 logcat -d -t 220 2>&1 | Out-String
$lisTx = $pl -match 'CTRL sent type=0xa|LISTEN_REQ burst'
$lisRx = $sl -match 'LISTEN_REQ from|LISTEN serve START'
$lis = $lisTx -and $lisRx
Write-Host "LISTEN=$lis tx=$lisTx rx=$lisRx"
Write-Host (($pl -split "`n" | Select-String 'LISTEN|CTRL sent type=0xa|MONITOR_AUDIO' | Select-Object -Last 8).Line)
Write-Host (($sl -split "`n" | Select-String 'LISTEN_REQ|LISTEN serve|type=0xa|relay DELIVER' | Select-Object -Last 10).Line)
Svc $pix com.example.barky.TEST_LISTEN_STOP
Start-Sleep 1

# SEE — need video RX count on Pixel
adb -s $pix logcat -c; adb -s $s20 logcat -c
Svc $pix com.example.barky.TEST_SEE_START
Start-Sleep 12
$ps = adb -s $pix logcat -d -t 220 2>&1 | Out-String
$ss = adb -s $s20 logcat -d -t 220 2>&1 | Out-String
$rxN = 0
foreach ($m in [regex]::Matches($ps, 'MONITOR_VIDEO_AVC_RX n=(\d+)|SEE/SCRN_AVC_HUB_TCP n=(\d+)|MONITOR_VIDEO_RX n=(\d+)')) {
    foreach ($g in 1..3) {
        if ($m.Groups[$g].Value) {
            $v = [int]$m.Groups[$g].Value
            if ($v -gt $rxN) { $rxN = $v }
        }
    }
}
$see = ($rxN -ge 5) -and ($ps -notmatch 'SESSION_FAIL')
Write-Host "SEE=$see rx=$rxN"
Write-Host (($ps -split "`n" | Select-String 'SEE_|MONITOR_VIDEO|AVC|SESSION|hub' | Select-Object -Last 10).Line)
Write-Host (($ss -split "`n" | Select-String 'SEE_|serve|AVC|hub|CAMERA' | Select-Object -Last 10).Line)
Svc $pix com.example.barky.TEST_SEE_STOP

$overall = if ($ring -and $lis -and $see) { 'PASS' } else { 'FAIL' }
Write-Host "MATRIX RING=$ring LISTEN=$lis SEE=$see OVERALL=$overall left=$(Left)"
if ($overall -ne 'PASS') { exit 1 }
exit 0
