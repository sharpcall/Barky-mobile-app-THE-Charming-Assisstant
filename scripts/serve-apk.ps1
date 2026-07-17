# Serve the latest debug APK on the LAN so phones can install without adb.
# Usage (from barky/):  powershell -File scripts/serve-apk.ps1
# On phone: menu -> Install update via Wi-Fi (same Wi-Fi as this PC).
param(
    [int]$Port = 8765,
    [string]$ApkPath = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $root) { $root = "F:\To sort\Delete\barky" }
if (Test-Path "F:\To sort\Delete\barky") { $root = "F:\To sort\Delete\barky" }

$candidates = @(
    $ApkPath,
    (Join-Path $root "app\build-outputs\app-debug.apk"),
    (Join-Path $root "app\build\outputs\apk\debug\app-debug.apk")
) | Where-Object { $_ -and (Test-Path $_) }

if (-not $candidates) {
    Write-Host "No APK found. Build first: gradlew assembleDebug"
    exit 1
}
$apk = $candidates[0]
Write-Host "Serving: $apk"
Write-Host "URL:     http://<this-pc-ip>:$Port/barky.apk"
Write-Host "Keep this window open. Phone menu: Install update via Wi-Fi"
Write-Host "Press Ctrl+C to stop."

# Simple HTTP file server
$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://+:$Port/")
try {
    $listener.Start()
} catch {
    # Fallback: localhost only if URL ACL missing
    $listener = New-Object System.Net.HttpListener
    $listener.Prefixes.Add("http://127.0.0.1:$Port/")
    $listener.Start()
    Write-Host "WARN: bound to 127.0.0.1 only. Run as admin or:"
    Write-Host "  netsh http add urlacl url=http://+:$Port/ user=Everyone"
}

function Get-LanIPs {
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike "127.*" -and $_.PrefixOrigin -ne "WellKnown" } |
        Select-Object -ExpandProperty IPAddress
}
Write-Host "This PC LAN IPs: $((Get-LanIPs) -join ', ')"

while ($listener.IsListening) {
    $ctx = $listener.GetContext()
    $req = $ctx.Request
    $res = $ctx.Response
    $path = $req.Url.AbsolutePath.TrimStart("/")
    Write-Host "$(Get-Date -Format HH:mm:ss) $($req.HttpMethod) /$path from $($req.RemoteEndPoint)"
    if ($req.HttpMethod -eq "HEAD" -or $req.HttpMethod -eq "GET") {
        if ($path -eq "" -or $path -eq "barky.apk" -or $path -eq "app-debug.apk") {
            $bytes = [System.IO.File]::ReadAllBytes($apk)
            $res.StatusCode = 200
            $res.ContentType = "application/vnd.android.package-archive"
            $res.ContentLength64 = $bytes.Length
            if ($req.HttpMethod -eq "GET") {
                $res.OutputStream.Write($bytes, 0, $bytes.Length)
            }
        } else {
            $res.StatusCode = 404
        }
    } else {
        $res.StatusCode = 405
    }
    $res.Close()
}
