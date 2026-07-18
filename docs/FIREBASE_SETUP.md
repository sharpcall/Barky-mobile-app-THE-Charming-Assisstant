# Firebase free-tier setup for Barky (device registry)

Firebase is used as a **central directory** (who is online, local Wi‑Fi IP, public IP/port from STUN).  
**Media (audio/video) stays peer-to-peer** — Firebase does **not** relay call media on the free plan.

## What Barky stores in Firestore

Collection: `devices`  
Document ID = **device unique ID** (stable per install when possible).

| Field | Meaning |
|--------|---------|
| `deviceId` | Unique Barky ID (hash of phone + Android ID + model + coarse GPS when available) |
| `phoneNumber` | Carrier number or `NoPhoneNumber` |
| `displayName` | Model / user label |
| `localIp` | Current Wi‑Fi LAN IP (if any) |
| `localUdpPort` | Walkie UDP port on LAN |
| `localVideoPort` | Walkie TCP video port on LAN |
| `publicIp` / `publicUdpPort` | Mapped address from STUN (for remote / NAT) |
| `wifiSsid` | Optional SSID hash/name for “same Wi‑Fi” hints |
| `lat` / `lon` | Last coarse location (optional) |
| `updatedAt` | Server timestamp (heartbeat) |
| `appVersion` | versionName |

## Console steps (Spark free plan)

1. Open [Firebase Console](https://console.firebase.google.com/) → **Add project** (e.g. `barky-registry`).
2. Disable Google Analytics if you want the lightest free setup (optional).
3. **Add Android app**:
   - Package name: `com.example.barky` (must match `applicationId` in `app/build.gradle`)
   - Download **`google-services.json`**
   - Place it at: `barky/app/google-services.json`  
     (do **not** commit secrets if the repo is public — use a private copy)
4. Enable **Cloud Firestore**:
   - Create database → **Start in test mode** for first bring-up  
     (replace rules before production — see below)
5. (Optional) Enable **Authentication** → Anonymous (if you later lock writes to signed-in devices).

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
   - Heartbeat writes IPs to Firestore.  
   - Caller **looks up** callee `deviceId` or `phoneNumber`.  
   - Uses **public IP:UDP** (STUN) + existing invite/STUN hole-punch style connect.  
   - If UDP fails, fallback remains “share pair code” / retry STUN.
3. **Handover Wi‑Fi ↔ cellular**:  
   Network callback refreshes local + STUN public mapping and **rewrites** Firestore; next packets target the latest addresses.  
   In-progress sessions re-HELLO / re-bind sockets (smooth as possible; brief glitch possible on hard NAT).

## Cost note (free Spark)

- Firestore free tier is enough for heartbeats every 30–60s for a small number of devices.  
- Do **not** put audio/video bytes through Firebase (expensive + high latency).  
- STUN servers used by Barky are public (Google/Cloudflare) and free for Binding requests.

## Verify

1. Install app with `google-services.json` present.  
2. Settings → enable **Internet pairing** (Wi‑Fi only OFF) if you want remote path.  
3. Logcat: `FirebasePeerDirectory` / `DeviceIdentity` should show `registered deviceId=…`.  
4. In Firebase Console → Firestore → `devices` → your document updates.
