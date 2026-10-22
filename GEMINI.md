# Barky Project Instructions & Global Rules

## Mandatory Global Coding & Release Rules

1. **Always Comment Code Changes (#1 & #2):**
   - Every code addition or modification **must** include clear explanatory comments.
   - If an existing comment accompanies code being modified, update the comment so it stays accurate.
   - If there is no comment around the code being changed or added, add a comment explaining the purpose and behavior of the change.
2. **Run Tests on Every Upgrade (#6):**
   - Every feature upgrade or bug fix must be verified by running the project's automated smoke/device tests (e.g. `scripts/publish-wifi.ps1`, `scripts/full-feature-smoke.ps1`, `scripts/post-publish-smoke.ps1`, `scripts/button-matrix-smoke.ps1`) against connected phones.
3. **Version Bump, Dual-Phone Upload, Firebase & GitHub Publish (#7):**
   - Bump `versionCode` and `versionName` in `app/build.gradle` for each release/upgrade.
   - Build the APK and upload/install it onto both connected phones (Pixel 8 and Samsung) via wireless ADB.
   - Publish the updated APK catalog to Firebase (`node scripts/upload-apk-to-firebase.mjs`) when applicable and commit/push changes to GitHub.
