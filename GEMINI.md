# Barky Project Instructions & Global Rules

## Mandatory Global Coding & Release Rules

1. **Always Comment Code Changes (#1 & #2):**
   - Every code addition or modification **must** include clear explanatory comments.
   - If an existing comment accompanies code being modified, update the comment so it stays accurate.
   - If there is no comment around the code being changed or added, add a comment explaining the purpose and behavior of the change.
2. **Run Tests on Every Upgrade — Both Wi‑Fi & Cellular Paths (#6):**
   - Every feature upgrade or bug fix must be verified by running the project's automated smoke/device tests (`scripts/publish-wifi.ps1`, `scripts/full-feature-smoke.ps1`, `scripts/post-publish-smoke.ps1`, `scripts/button-matrix-smoke.ps1`) against connected phones, covering **both Wi‑Fi LAN and Cellular / Public Internet Hub Relay (`FIREBASE_LIST` + STUN + `BarkyUdpRelay`) paths**, and restoring normal media volume (`STREAM_MUSIC`) when finished.
3. **Version Bump, Dual-Phone Upload, Firebase & GitHub Publish (#7):**
   - **Never call `replace` or `write_file` directly on `build.gradle` / `app/build.gradle`** (doing so triggers a hardcoded Gemini CLI `CRITICAL SECURITY WARNING: Build File Modification` prompt).
   - Instead, pass `-VersionName "5.xx-..."` to `scripts/publish-wifi.ps1`, which automatically increments `versionCode` and updates `versionName` inside `app/build.gradle`:
     `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/publish-wifi.ps1 -VersionName "5.xx-..." -UploadFirebase -CommitMessage "..."`
   - This single command bumps the version, builds the APK, installs in parallel onto both connected phones, publishes the APK catalog to Firebase/GitHub Releases, runs the Wi‑Fi + Cellular smoke tests, and commits/pushes to GitHub with zero prompts.
