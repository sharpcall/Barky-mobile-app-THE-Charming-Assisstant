# Firebase free-tier setup for Barky (device registry)

Firebase is used **only as a phone book** (who is online, LAN IP, public IP/port from STUN, device info).  

**Audio, video, HOLD, LISTEN, SEE, WAKEUP — always phone→phone (UDP/TCP peer-to-peer).**  
Firebase never relays media. STUN is only used briefly to *discover* your public IP (a few tiny packets), not to carry the call.

## What Barky stores in Firestore

Collection: `devices`  
Document ID = **device unique ID** (stable per install when possible).

| Field | Meaning |
|--------|---------|
| `deviceId` | Unique Barky ID (hash of phone + Android ID + model + coarse GPS when available) |
| `phoneNumber` | Carrier number or `NoPhoneNumber` |
| `displayName` | Manufacturer + model (label) |
| `phoneModel` | `Build.MODEL` |
| `manufacturer` | `Build.MANUFACTURER` |
| `androidVersion` | `Build.VERSION.RELEASE` (e.g. `14`) |
| `androidSdk` | `Build.VERSION.SDK_INT` |
| `androidId` | Android ID (for support / uniqueness) |
| `localIp` | Current Wi‑Fi LAN IP (if any) |
| `localUdpPort` | Walkie UDP port on LAN |
| `localVideoPort` | Walkie TCP video port on LAN |
| `publicIp` / `publicUdpPort` | Mapped address from STUN (for remote / NAT) |
| `wifiSsid` | Optional SSID name for “same Wi‑Fi” hints |
| `onWifi` | Currently on Wi‑Fi? |
| `lat` / `lon` | Last coarse location (optional) |
| `updatedAtMs` | Last heartbeat / path-change write |
| `appVersion` | Barky **versionName** (e.g. `5.21-scrn-no-pair`) |
| `versionCode` | Barky **versionCode** integer (e.g. `61`) |
| `updateHttpPort` | HTTP port if this phone serves its APK (8765) |

### Hosting the APK on Firebase (self-update)

Yes — use **Firebase Storage** (not Firestore) for the binary, and a small Firestore catalog:

1. On the PC, after building:
   ```powershell
   node scripts/upload-apk-to-firebase.mjs
   ```
2. Script uploads `app/build-outputs/app-debug.apk` to  
   `gs://…/updates/barky-latest.apk`  
   and writes document **`_meta/app_update`**:
   - `apkUrl` — public HTTPS download URL  
   - `versionCode` / `versionName` — from `app/build.gradle`
3. Each phone (Walkie service) checks that catalog ~30 min and when you tap **Check for update**.  
   If remote `versionCode` **>** installed code, it downloads and opens the system installer  
   (user still taps Install once — Android requires that).

Media/calls never go through Storage — only the APK file.

You can add more directory fields later (e.g. nickname, pair group) — still **no media** in Firebase.

### “Test mode” rules (what that click was for)

When you **Create database → Start in test mode**, Firestore uses open security rules for ~30 days so any client with the project API key can read/write documents. That is only for **bring-up** of the phone book so devices can register without Auth.  

It does **not** mean media goes through Firebase. After bring-up, lock rules down (auth + per-device writes) before any public deployment.

## How the app picks your Firebase project

Barky reads these string resources from `app/src/main/res/values/firebase_config.xml`:

| Resource | Source in Firebase |
|----------|--------------------|
| `firebase_project_id` | Project settings → **Project ID** |
| `firebase_database_id` | Firestore database id (this project: **`barkydefault`**) |
| `firebase_api_key` | Android app **API key** (`client[].api_key[].current_key`) |

At runtime it calls:

`https://firestore.googleapis.com/v1/projects/<project_id>/databases/<database_id>/documents/devices/...?key=<api_key>`

So **project_id + database_id** choose which Firebase store; the **API key** is the public client key.

Seed / verify schema from a PC (optional):

```powershell
node scripts/seed-firestore-directory.mjs
```

Creates `_meta/schema` (field catalogue) and `devices/_example_template` (sample document).  
Real phones write their own docs under `devices/{deviceId}` on heartbeat.

### Service account vs API key (important)

| Credential | Use it for | Ship in APK? |
|------------|------------|--------------|
| **Service account** JSON (`"type": "service_account"`, private key) | PC-only admin (create Android app, scripts under `secrets/`) | **Never** |
| **Android / browser API key** (`AIza…`) | App Firestore REST directory | Yes (client key) |

If you paste a service-account key into chat or a ticket, **rotate it** in Google Cloud → IAM → Service accounts → Keys after setup.

Current project wired for Barky: **`barky-mobile-app---assisstan`**.  
Local copies (gitignored): `secrets/firebase-service-account.json`, `secrets/google-services.json`, `app/google-services.json`.

## Console steps (Spark free plan)

1. Open [Firebase Console](https://console.firebase.google.com/) → project **barky-mobile-app---assisstan** (or **Add project**).
2. Disable Google Analytics if you want the lightest free setup (optional).
3. **Add Android app** (may already exist for package `com.example.barky`):
   - Package name: `com.example.barky` (must match `applicationId` in `app/build.gradle`)
   - Download **`google-services.json`**
   - Place it at: `barky/app/google-services.json`  
     (gitignored — do **not** commit if the repo is public)
4. **Required one-time:** enable **Cloud Firestore**:
   - [Enable API](https://console.developers.google.com/apis/api/firestore.googleapis.com/overview?project=barky-mobile-app---assisstan)  
   - Then Console → **Firestore** → **Create database** → **Start in test mode** for first bring-up  
     (replace rules before production — see below)
5. Copy `project_id` + `api_key` into `firebase_config.xml` (or run `node scripts/fetch-firebase-web-api-key.mjs` with a local SA in `secrets/`).
6. (Optional) Enable **Authentication** → Anonymous (if you later lock writes to signed-in devices).

### Example Firestore rules (development only)

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /devices/{deviceId} {
      allow read: if true;
      allow write: if request.resource.data.deviceId == deviceId;
    }
  }
}
```

Lock this down before any real public deployment (auth + per-device ownership).

## Gradle

Root `build.gradle` already can apply Google services when the JSON exists.  
After adding `google-services.json`, sync/rebuild:

```text
.\gradlew-build.bat :app:assembleDebug
```

If JSON is **missing**, the app still builds; Firebase registration is a **no-op** and logs setup hints.

## Runtime behaviour (design)

1. **Same Wi‑Fi** (both have private IPs on the same subnet / recent LAN HELLO):  
   Prefer **local IP + ports** (existing NSD / HELLO path). No Firebase required for media.
2. **Either remote**:  
   - Heartbeat writes IPs to Firestore every ~45s.  
   - Caller **looks up** callee `deviceId` or `phoneNumber`.  
   - Uses **public IP:UDP** (STUN) + existing invite/STUN hole-punch style connect.  
   - If UDP fails, fallback remains “share pair code” / retry STUN.
3. **Handover Wi‑Fi ↔ cellular (immediate)**:  
   - `NetworkPathMonitor` hooks the default NIC (`ConnectivityManager` default network callback).  
   - On **any** link / capability / IP change (debounced ~350ms):  
     - Re-STUN public mapping  
     - **Immediate** Firestore rewrite (does not wait for heartbeat)  
     - UDP `TYPE_PATH_UPDATE` + HELLO burst to known peers  
     - Peer remaps host/ports; video TCP reconnects if SEE/HOLD active  
   - During live talk/LISTEN/SEE, phones also **poll Firebase every ~5s** for tracked `deviceId`s so handover works even if the old UDP path is already dead.

## Cost note (free Spark)

- Firestore free tier is enough for heartbeats every 30–60s for a small number of devices.  
- Do **not** put audio/video bytes through Firebase (expensive + high latency).  
- STUN servers used by Barky are public (Google/Cloudflare) and free for Binding requests.

## Verify

1. Install app with config filled in `firebase_config.xml`.  
2. Settings → enable **Register this phone on Firebase**.  
3. Logcat: `FirebasePeerDirectory` should show `registered deviceId=…`.  
4. In Firebase Console → Firestore → `devices` → your document updates.  
5. Main screen menu → **Contacts** lists LAN peers + Firebase phones (3+ phones OK).

## Automated setup (you run locally — do not paste tokens into chat)

```powershell
# After: npm i -g firebase-tools   OR   install gcloud
$env:FIREBASE_PROJECT = "your-project-id"
# Optional CI token from: firebase login:ci
# $env:FIREBASE_TOKEN = "...."
cd barky
.\scripts\setup-firebase.ps1
# or
.\scripts\setup-firebase.ps1 -UseGcloud
```

Creating the **first** Firestore database often still needs a one-time Console click (region + mode).  
After that, the app’s first heartbeat **creates documents** under `devices` automatically.

## Firebase vs STUN (important)

| Role | Who |
|------|-----|
| Learn “what is my public IP:port?” | **STUN** (already in Barky) |
| Store & share “my IP:port + friend’s IP:port” | **Firebase Firestore** |
| Carry audio/video packets | **Phones peer-to-peer** (not Firebase) |

So Firebase is a **phone book that always has the latest IPs**, not a STUN server.  
Phones still STUN → write results to Firebase → friends read Firebase → call those addresses (LAN preferred when private IPs match).
