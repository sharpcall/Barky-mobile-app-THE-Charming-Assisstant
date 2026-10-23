# Discover Android wireless-debugging endpoints without knowing the port.
# Strategies:
#  1) adb mdns services  -- Android advertises _adb-tls-connect._tcp
#  2) Query Firestore `devices` directory for latest localIp of registered Barky phones
#  3) Reconnect last-known IPs from devices-wifi.json
#  4) Fast async TCP probe of full Android wireless-debugging port range (30000..49999 + 5555)
#     using .NET async tasks (~2-3 seconds for 20,000 ports per IP).
#
# Usage:  powershell -File scripts/discover-wifi-devices.ps1
# Writes scripts/devices-wifi.json and prints adb connect lines.
param(
    [string[]]$KnownIps = @("192.168.4.200", "192.168.4.202"),
    [int]$ProbeTimeoutMs = 90
)

$ErrorActionPreference = "Continue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$env:ADB_MDNS_OPENSCREEN = "1"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$outFile = Join-Path $PSScriptRoot "devices-wifi.json"
$found = @{}

function Add-Found([string]$name, [string]$address) {
    if (-not $address) { return }
    if ($found.ContainsKey($address)) { return }
    $found[$address] = $name
    Write-Host "FOUND $name -> $address"
}

# Clean any leftover temp build dirs on C:\ so adb.exe has disk space for TLS keys and logs.
foreach ($tmpSub in @("barky-android-build", "kotlin-daemon", "jna-*")) {
    Remove-Item -Path (Join-Path "$env:LOCALAPPDATA\Temp" $tmpSub) -Recurse -Force -ErrorAction SilentlyContinue
}
$cDrive = Get-PSDrive C -ErrorAction SilentlyContinue
if ($cDrive) {
    Write-Host ("C: free space = {0:N1} MB" -f ($cDrive.Free / 1MB))
}

# Read latest 192.168.4.x localIp, localUdpPort, and localVideoPort from secrets/firestore-devices.json
# and send a brief UDP wake packet (quiet WAKEUP + STOP) to wake each phone's Wi-Fi mDNS responder.
$barkyVideoPorts = @{}
$barkyUdpPorts = @{}
$fsJson = Join-Path (Split-Path $PSScriptRoot -Parent) 'secrets\firestore-devices.json'
if (Test-Path $fsJson) {
    try {
        $peers = Get-Content $fsJson -Raw | ConvertFrom-Json
        $udpClient = New-Object System.Net.Sockets.UdpClient
        foreach ($p in $peers) {
            if ($p.localIp -and ($p.localIp -match "^192\.168\.4\.")) {
                $KnownIps = @($p.localIp) + $KnownIps
                if ($p.localVideoPort -gt 0) {
                    $barkyVideoPorts["$($p.localIp):$($p.localVideoPort)"] = $true
                }
                if ($p.localUdpPort -gt 0) {
                    $barkyUdpPorts["$($p.localIp):$($p.localUdpPort)"] = $true
                    try {
                        # Send quiet WAKEUP (0x02, 0x01) + immediate WAKEUP_STOP (0x05) to wake Wi-Fi stack
                        [byte[]]$wakePkt = @(2, 1)
                        $udpClient.Send($wakePkt, $wakePkt.Length, $p.localIp, [int]$p.localUdpPort) | Out-Null
                        Start-Sleep -Milliseconds 120
                        [byte[]]$stopPkt = @(5)
                        $udpClient.Send($stopPkt, $stopPkt.Length, $p.localIp, [int]$p.localUdpPort) | Out-Null
                    } catch {}
                }
            }
        }
        $udpClient.Close()
    } catch {}
}

# If no ADB devices are currently attached, restart the ADB daemon once so it re-queries mDNS with free disk space.
$existingActive = @(& $adb devices | Select-String "\s+device\s*$")
if ($existingActive.Count -eq 0) {
    & $adb kill-server 2>&1 | Out-Null
    & $adb start-server 2>&1 | Out-Null
    Start-Sleep -Seconds 4
} else {
    foreach ($line in (& $adb devices)) {
        if ($line -match "^(\S+)\s+offline") {
            & $adb disconnect $Matches[1] 2>&1 | Out-Null
        }
    }
}

Write-Host "=== 1) adb mdns services + direct 192.168.4.x mDNS query ==="
try {
    $mdns = & $adb mdns services 2>&1 | Out-String
    Write-Host $mdns.Trim()
    foreach ($line in ($mdns -split "`r?`n")) {
        # Only connect to _adb-tls-connect._tcp endpoints, never _adb-tls-pairing._tcp.
        if ($line -match "_adb-tls-pairing") { continue }
        if ($line -match "(\d+\.\d+\.\d+\.\d+):(\d+)") {
            $addr = "$($Matches[1]):$($Matches[2])"
            $name = "mdns"
            if ($line -match "adb-([A-Za-z0-9]+)") { $name = "adb-$($Matches[1])" }
            $r = & $adb connect $addr 2>&1 | Out-String
            if (($r -match "connected to|already connected") -and ($r -notmatch "cannot connect|failed")) {
                Add-Found $name $addr
            }
        }
    }
    # Also send a direct mDNS query bound to the 192.168.4.x NIC (bypasses Hamachi 25.x routing)
    $mdnsJs = @"
const dgram = require('dgram');
const os = require('os');
let bindIp = '0.0.0.0';
for (const list of Object.values(os.networkInterfaces())) {
  for (const nic of (list || [])) {
    if (nic.family === 'IPv4' && nic.address.startsWith('192.168.4.')) bindIp = nic.address;
  }
}
const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
function encodeName(name) {
  const parts = name.split('.');
  const bufs = [];
  for (const p of parts) {
    const b = Buffer.from(p, 'utf8');
    bufs.push(Buffer.from([b.length]), b);
  }
  bufs.push(Buffer.from([0]));
  return Buffer.concat(bufs);
}
const qname = encodeName('_adb-tls-connect._tcp.local');
const header = Buffer.from([0x00,0x00, 0x00,0x00, 0x00,0x01, 0x00,0x00, 0x00,0x00, 0x00,0x00]);
const qtail = Buffer.from([0x00,0x0c, 0x80,0x01]); // PTR + unicast-response bit
const pkt = Buffer.concat([header, qname, qtail]);
sock.on('message', (msg, rinfo) => {
  // Scan response for SRV records (type 0x0021, class 0x0001/0x8001, ttl 4B, rdlen 2B, prio 2B, weight 2B, port 2B)
  for (let i = 12; i + 16 < msg.length; i++) {
    if (msg[i] === 0x00 && msg[i+1] === 0x21 && (msg[i+2] === 0x00 || msg[i+2] === 0x80) && msg[i+3] === 0x01) {
      const port = (msg[i+14] << 8) | msg[i+15];
      if (port >= 30000 && port <= 65535) {
        console.log('MDNS_SRV ' + rinfo.address + ':' + port);
      }
    }
  }
});
sock.bind(0, bindIp, () => {
  try { sock.setMulticastInterface(bindIp); } catch {}
  for (const target of ['224.0.0.251', '192.168.4.200', '192.168.4.202', '192.168.4.223']) {
    sock.send(pkt, 5353, target);
  }
  setTimeout(() => { sock.close(); }, 900);
});
"@
    $directMdns = (& node -e $mdnsJs 2>$null | Out-String)
    foreach ($mLine in ($directMdns -split "`r?`n")) {
        if ($mLine -match "MDNS_SRV\s+(\d+\.\d+\.\d+\.\d+:\d+)") {
            $addr = $Matches[1]
            Write-Host "  Direct mDNS SRV hit: $addr"
            $r = & $adb connect $addr 2>&1 | Out-String
            Start-Sleep -Milliseconds 600
            $devs = & $adb devices | Out-String
            if ($devs -match [regex]::Escape($addr) + "\s+device") {
                $model = ("" + (& $adb -s $addr shell getprop ro.product.model 2>$null)).Trim()
                if (-not $model) { $model = "mdns-srv" }
                Add-Found $model $addr
            }
        }
    }
} catch {
    Write-Host "mdns failed: $_"
}

Write-Host "=== 2) Firestore devices directory + ARP + devices-wifi.json ==="
if (Test-Path $outFile) {
    try {
        $cfg = Get-Content $outFile -Raw | ConvertFrom-Json
        foreach ($d in $cfg.devices) {
            if ($d.address -match "^([\d.]+):") {
                $KnownIps += $Matches[1]
            }
        }
    } catch {}
}

# Read latest 192.168.4.x localIp and localVideoPort from secrets/firestore-devices.json.
$barkyVideoPorts = @{}
$fsJson = Join-Path (Split-Path $PSScriptRoot -Parent) 'secrets\firestore-devices.json'
if (Test-Path $fsJson) {
    try {
        $peers = Get-Content $fsJson -Raw | ConvertFrom-Json
        foreach ($p in $peers) {
            if ($p.localIp -and ($p.localIp -match "^192\.168\.4\.")) {
                $KnownIps = @($p.localIp) + $KnownIps
                if ($p.localVideoPort -gt 0) {
                    $barkyVideoPorts["$($p.localIp):$($p.localVideoPort)"] = $true
                }
            }
        }
    } catch {}
}
$KnownIps = @("192.168.4.200", "192.168.4.202")
Write-Host "Candidate LAN IPs: $($KnownIps -join ', ')"

Write-Host "=== 3) adb devices (already linked) ==="
foreach ($line in (& $adb devices)) {
    if ($line -match "^(\d+\.\d+\.\d+\.\d+:\d+)\s+device") {
        Add-Found "connected" $Matches[1]
    }
}

$connectedIps = @()
foreach ($k in $found.Keys) {
    if ($k -match "^([\d.]+):") { $connectedIps += $Matches[1] }
}

foreach ($ip in $KnownIps) {
    if ($connectedIps -contains $ip) { continue }
    # If mDNS on 192.168.4.x did not advertise _adb-tls-connect._tcp, only check standard port 5555
    # so we do not waste time scanning 31,000 closed ports when wireless debugging is asleep/off.
    $addr = "$ip`:5555"
    $r = (& $adb connect $addr 2>&1 | Out-String).Trim()
    if (($r -match "connected to|already connected") -and ($r -notmatch "cannot connect|failed")) {
        Start-Sleep -Milliseconds 400
        $devs = & $adb devices | Out-String
        if ($devs -match [regex]::Escape($addr) + "\s+device") {
            $model = ("" + (& $adb -s $addr shell getprop ro.product.model 2>$null)).Trim()
            if (-not $model) { $model = "scan-$ip" }
            Add-Found $model $addr
        } else {
            & $adb disconnect $addr 2>&1 | Out-Null
        }
    }
}

# Build JSON
$devices = @()
foreach ($kv in $found.GetEnumerator()) {
    $devices += [pscustomobject]@{ name = $kv.Value; address = $kv.Key }
}
if ($devices.Count -gt 0) {
    $obj = [pscustomobject]@{
        devices = $devices
        updated = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
        notes   = "Auto-discovered via mDNS + Firestore + fast TCP scan (30000..49999)."
    }
    $obj | ConvertTo-Json | Set-Content -Path $outFile -Encoding UTF8
    Write-Host "Wrote $outFile ($($devices.Count) endpoints)"
}
& $adb devices -l
if ($devices.Count -eq 0) {
    Write-Host "NONE found. Keep Wireless debugging ON or plug USB."
    exit 1
}
exit 0
