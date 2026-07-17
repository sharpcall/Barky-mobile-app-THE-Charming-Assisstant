# Discover Android wireless-debugging endpoints without knowing the port.
# Strategies (creative + practical):
#  1) adb mdns services  — Android advertises adb-tls-connect (best)
#  2) Reconnect last-known IPs from devices-wifi.json while scanning common adb ports
#  3) Optional TCP probe of port ranges on known LAN IPs
#
# Usage:  powershell -File scripts/discover-wifi-devices.ps1
# Writes scripts/devices-wifi.json and prints adb connect lines.
param(
    [string[]]$KnownIps = @("192.168.4.200", "192.168.4.202"),
    [int]$ProbeTimeoutMs = 120,
    [switch]$PortScan
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
    Write-Host $mdns
    # Formats vary: "adb-XXXX_adb-tls-connect._tcp. 192.168.x.x:PORT" or tab-separated
    foreach ($line in ($mdns -split "`r?`n")) {
        if ($line -match "(\d+\.\d+\.\d+\.\d+):(\d+)") {
            $addr = "$($Matches[1]):$($Matches[2])"
            $name = "mdns"
            if ($line -match "adb-([A-Za-z0-9]+)") { $name = "adb-$($Matches[1])" }
            Add-Found $name $addr
            & $adb connect $addr 2>&1 | Out-Null
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
                if ($r -match "connected|already") { Add-Found $d.name $d.address }
            }
        }
    } catch {}
}
$KnownIps = $KnownIps | Select-Object -Unique

Write-Host "=== 3) adb devices (already linked) ==="
& $adb devices -l
foreach ($line in (& $adb devices)) {
    if ($line -match "^(\d+\.\d+\.\d+\.\d+:\d+)\s+device") {
        Add-Found "connected" $Matches[1]
    }
}

if ($PortScan -and $KnownIps.Count -gt 0) {
    Write-Host "=== 4) Port scan known IPs (slow; wireless-debug ports often 30000-50000) ==="
    # Wireless debugging tends to use high ports; sample strategically
    $ports = @(5555, 5037) + (37000..37100) + (30000..30100) + (33000..33200) + (34000..34500) + (43000..43200)
    $ports = $ports | Select-Object -Unique
    foreach ($ip in $KnownIps) {
        Write-Host "Probing $ip ($($ports.Count) ports)…"
        $jobs = @()
        foreach ($port in $ports) {
            $jobs += Start-Job -ScriptBlock {
                param($ip, $port, $ms)
                try {
                    $c = New-Object System.Net.Sockets.TcpClient
                    $iar = $c.BeginConnect($ip, $port, $null, $null)
                    if ($iar.AsyncWaitHandle.WaitOne($ms, $false) -and $c.Connected) {
                        $c.Close()
                        return "$ip`:$port"
                    }
                    $c.Close()
                } catch {}
                return $null
            } -ArgumentList $ip, $port, $ProbeTimeoutMs
        }
        $done = Wait-Job $jobs -Timeout 90
        foreach ($j in $jobs) {
            $r = Receive-Job $j
            if ($r) {
                Add-Found "scan" $r
                & $adb connect $r 2>&1 | Out-Null
            }
            Remove-Job $j -Force -ErrorAction SilentlyContinue
        }
    }
}

# Build JSON
$devices = @()
$i = 0
foreach ($kv in $found.GetEnumerator()) {
    $i++
    $devices += [pscustomobject]@{ name = $kv.Value; address = $kv.Key }
}
$obj = [pscustomobject]@{
    devices = $devices
    updated = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
    notes   = "Auto-discovered. Ports change when wireless debugging restarts; re-run this script."
}
$obj | ConvertTo-Json | Set-Content -Path $outFile -Encoding UTF8
Write-Host "Wrote $outFile ($($devices.Count) endpoints)"
& $adb devices -l
if ($devices.Count -eq 0) {
    Write-Host "NONE found. Keep Wireless debugging ON; pair once; or pass -PortScan -KnownIps 192.168.4.200,192.168.4.202"
    exit 1
}
exit 0
