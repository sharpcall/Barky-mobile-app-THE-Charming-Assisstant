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
    [string[]]$KnownIps = @("192.168.4.200", "192.168.4.223", "192.168.4.202", "192.168.4.231"),
    [int]$ProbeTimeoutMs = 90
)

$ErrorActionPreference = "Continue"
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

Write-Host "=== 1) adb mdns services ==="
try {
    $mdns = & $adb mdns services 2>&1 | Out-String
    if ($mdns -notmatch "_adb-tls-connect") {
        # Restart ADB server so Windows mDNS re-queries active _adb-tls-connect._tcp services on LAN.
        & $adb kill-server 2>&1 | Out-Null
        & $adb start-server 2>&1 | Out-Null
        Start-Sleep -Seconds 2
        $mdns = & $adb mdns services 2>&1 | Out-String
    }
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
} catch {
    Write-Host "mdns failed: $_"
}

Write-Host "=== 2) devices-wifi.json last IPs + adb connect ==="
if (Test-Path $outFile) {
    try {
        $cfg = Get-Content $outFile -Raw | ConvertFrom-Json
        foreach ($d in $cfg.devices) {
            if ($d.address -match "^([\d.]+):") { $KnownIps += $Matches[1] }
            if ($d.address) {
                $r = & $adb connect $d.address 2>&1 | Out-String
                if (($r -match "connected to|already connected") -and ($r -notmatch "cannot connect|failed")) {
                    Add-Found $d.name $d.address
                }
            }
        }
    } catch {}
}

# Also read latest localIp from Firestore `devices` collection if firebase_config.xml is present.
$fbCfg = Join-Path (Split-Path $PSScriptRoot -Parent) 'app\src\main\res\values\firebase_config.xml'
if (Test-Path $fbCfg) {
    try {
        $xml = Get-Content $fbCfg -Raw
        $pidMatch = [regex]::Match($xml, 'name="firebase_project_id">([^<]+)<')
        $dbMatch  = [regex]::Match($xml, 'name="firebase_database_id">([^<]+)<')
        $keyMatch = [regex]::Match($xml, 'name="firebase_api_key">([^<]+)<')
        if ($pidMatch.Success -and $dbMatch.Success -and $keyMatch.Success) {
            $url = "https://firestore.googleapis.com/v1/projects/$($pidMatch.Groups[1].Value)/databases/$($dbMatch.Groups[1].Value)/documents/devices?key=$($keyMatch.Groups[1].Value)"
            $resp = Invoke-RestMethod -Uri $url -TimeoutSec 4 -ErrorAction SilentlyContinue
            foreach ($doc in $resp.documents) {
                $lip = $doc.fields.localIp.stringValue
                $dname = $doc.fields.displayName.stringValue
                if ($lip -and ($lip -match "^192\.168\.|^10\.|^172\.")) {
                    $KnownIps += $lip
                    Write-Host "Firestore peer $dname -> localIp=$lip"
                }
            }
        }
    } catch {}
}
$KnownIps = $KnownIps | Where-Object { $_ } | Select-Object -Unique

Write-Host "=== 3) adb devices (already linked) ==="
foreach ($line in (& $adb devices)) {
    if ($line -match "^(\d+\.\d+\.\d+\.\d+:\d+)\s+device") {
        Add-Found "connected" $Matches[1]
    }
}

# Fast C#/.NET parallel port scanner for any known IP not yet connected via ADB.
if (-not ("FastPortScanner" -as [type])) {
    Add-Type -TypeDefinition @"
using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;

public static class FastPortScanner {
    public static int[] Scan(string ip, int startPort, int endPort, int timeoutMs, int maxConcurrency) {
        var open = new ConcurrentBag<int>();
        using (var sem = new SemaphoreSlim(maxConcurrency)) {
            var tasks = new List<Task>(endPort - startPort + 1);
            for (int p = startPort; p <= endPort; p++) {
                int port = p;
                sem.Wait();
                tasks.Add(Task.Run(async () => {
                    try {
                        using (var client = new TcpClient()) {
                            var connectTask = client.ConnectAsync(ip, port);
                            if (await Task.WhenAny(connectTask, Task.Delay(timeoutMs)) == connectTask && client.Connected) {
                                open.Add(port);
                            }
                        }
                    } catch {
                    } finally {
                        sem.Release();
                    }
                }));
            }
            Task.WaitAll(tasks.ToArray());
        }
        return open.ToArray();
    }
}
"@
}

$connectedIps = @()
foreach ($k in $found.Keys) {
    if ($k -match "^([\d.]+):") { $connectedIps += $Matches[1] }
}

foreach ($ip in $KnownIps) {
    if ($connectedIps -contains $ip) { continue }
    # Two ping attempts wake Wi-Fi power-save radios before deciding if host is reachable on LAN.
    $pingOk = Test-Connection -ComputerName $ip -Count 2 -Quiet -ErrorAction SilentlyContinue
    if (-not $pingOk) {
        Write-Host "Host $ip not responding to ping (may be on cellular data) -- skipping port scan"
        continue
    }
    Write-Host "Fast scanning wireless ADB ports (30000..49999) on reachable host $ip..."
    $openPorts = [FastPortScanner]::Scan($ip, 30000, 49999, 220, 192)
    foreach ($p in ($openPorts + @(5555))) {
        # Skip Barky's own LAN HTTP APK server port (8765) and walkie TCP video ports if ADB rejects them.
        $addr = "$ip`:$p"
        $r = & $adb connect $addr 2>&1 | Out-String
        if ($r -match "connected to|already connected") {
            # Verify it is an active ADB daemon in `adb devices`.
            Start-Sleep -Milliseconds 200
            $devs = & $adb devices | Out-String
            if ($devs -match [regex]::Escape($addr) + "\s+device") {
                $model = (& $adb -s $addr shell getprop ro.product.model 2>$null).Trim()
                if (-not $model) { $model = "scan-$ip" }
                Add-Found $model $addr
                break
            } else {
                & $adb disconnect $addr 2>&1 | Out-Null
            }
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
