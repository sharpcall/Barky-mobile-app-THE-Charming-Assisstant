# Create / verify Barky Firestore directory using YOUR local credentials.
# Do NOT paste tokens into chat — set env vars in your shell, then run this script.
#
# Option A — Firebase CI token (from: firebase login:ci)
#   $env:FIREBASE_TOKEN = "...."
#   $env:FIREBASE_PROJECT = "your-project-id"
#   .\scripts\setup-firebase.ps1
#
# Option B — gcloud user credentials already logged in
#   gcloud auth login
#   $env:FIREBASE_PROJECT = "your-project-id"
#   .\scripts\setup-firebase.ps1 -UseGcloud
#
# This does NOT upload audio/video. It only helps create the Firestore database
# and prints the fields to paste into app/src/main/res/values/firebase_config.xml

param(
    [switch]$UseGcloud,
    [string]$ProjectId = $env:FIREBASE_PROJECT
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ProjectId)) {
    Write-Host "Set FIREBASE_PROJECT or pass -ProjectId your-id"
    exit 2
}

Write-Host "Project: $ProjectId"
Write-Host "Firebase is a DIRECTORY (IPs/ports), not a STUN/media server."
Write-Host "STUN still discovers public IP; phones write that into Firestore."

# Prefer Firebase CLI if available
$firebase = Get-Command firebase -ErrorAction SilentlyContinue
if ($firebase -and -not $UseGcloud) {
    if (-not $env:FIREBASE_TOKEN) {
        Write-Host "Optional: set FIREBASE_TOKEN from 'firebase login:ci' for non-interactive use."
        Write-Host "Trying interactive firebase tools..."
    }
    Write-Host "Ensuring Firestore (may no-op if already exists)..."
    # firebase tools: create database if missing is project-dependent; use firestore:databases if available
    try {
        if ($env:FIREBASE_TOKEN) {
            & firebase --token $env:FIREBASE_TOKEN --project $ProjectId firestore:databases:list 2>&1
        } else {
            & firebase --project $ProjectId firestore:databases:list 2>&1
        }
    } catch {
        Write-Host "firebase CLI list failed: $_"
    }
    Write-Host ""
    Write-Host "If no database: open Console -> Firestore -> Create database (test mode for dev)."
    Write-Host "CLI cannot always create the first Firestore DB without billing/region prompts."
} elseif ($UseGcloud -or (Get-Command gcloud -ErrorAction SilentlyContinue)) {
    Write-Host "Using gcloud..."
    & gcloud config set project $ProjectId
    # Enable APIs
    & gcloud services enable firestore.googleapis.com firebase.googleapis.com --project $ProjectId
    # Create default DB if missing (nam5 = multi-region US; change if needed)
    $existing = & gcloud firestore databases list --project $ProjectId --format="value(name)" 2>$null
    if (-not $existing) {
        Write-Host "Creating Firestore database (nam5)..."
        & gcloud firestore databases create --location=nam5 --project $ProjectId --type=firestore-native
    } else {
        Write-Host "Firestore already exists: $existing"
    }
} else {
    Write-Host "Install Firebase CLI (npm i -g firebase-tools) or Google Cloud SDK (gcloud)."
    Write-Host "Or create Firestore manually: https://console.firebase.google.com/"
}

Write-Host ""
Write-Host "=== Paste into app/src/main/res/values/firebase_config.xml ==="
Write-Host "  firebase_project_id = $ProjectId"
Write-Host "  firebase_api_key    = (Firebase Console -> Project settings -> Your apps -> API key)"
Write-Host ""
Write-Host "Then: Settings -> Register this phone on Firebase"
Write-Host "Collection 'devices' is created automatically on first heartbeat PATCH."
Write-Host "Done."
