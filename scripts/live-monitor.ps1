<#
.SYNOPSIS
    Barky On-Demand Live Debug Monitor PowerShell Launcher
.DESCRIPTION
    Activates Firestore live debug stream and monitors phone actions in real-time.
    Usage:
        powershell -ExecutionPolicy Bypass -File scripts\live-monitor.ps1
        powershell -ExecutionPolicy Bypass -File scripts\live-monitor.ps1 -TimeoutSec 180
        powershell -ExecutionPolicy Bypass -File scripts\live-monitor.ps1 -Once
#>
param(
    [int]$TimeoutSec = 300,
    [switch]$Once
)

$scriptPath = Join-Path $PSScriptRoot "live-monitor.mjs"
$cmdArgs = @()
if ($Once) {
    $cmdArgs += "--once"
} else {
    $cmdArgs += "--timeout", "$TimeoutSec"
}

& node $scriptPath @cmdArgs
