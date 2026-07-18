# Barky mobile app - THE Charming Assisstant

Android walkie / companion app (same Wi‑Fi video messages, optional internet pair codes, unit conversion overlay settings).

## Firebase free directory (optional remote discovery)

See **[docs/FIREBASE_SETUP.md](docs/FIREBASE_SETUP.md)**.  
Put project id + API key in `app/src/main/res/values/firebase_config.xml`, enable **Firebase registry** in Settings.  
Media stays peer-to-peer (Wi‑Fi first, then public STUN IPs); Firebase only stores registration / lookup fields.

## Build

- Open the `barky` folder in Android Studio  
- Or: `./gradlew :app:assembleDebug` (use JDK 17 / Android Studio JBR)  
- Debug APK: `app/build-outputs/app-debug.apk`

## Notes

- Package id: `com.example.barky` (change before Play Store)  
- Do not commit keystores or `local.properties`
