# TubeMute 2.1.2

TubeMute is an Android TV / Google TV / Android application with server-managed access and GitHub Releases self-update.

## What's New in 2.1.2

- **Android App (`2.1.2` / `versionCode 24`)**:
  - Automatic visible Google account detection on first launch, plus one-tap **Continue without sign-in** guest device mode (no forced email/password on TVs without an account).
  - Direct in-app GitHub Releases updater: downloads the `.apk` with live progress inside the app and launches Android's system installer directly via `UpdateApkProvider` (no browser download step).
  - Settings menu (`⚙`, also via TV remote `Menu`/`Settings`) with **Test access**, **Check for updates**, **Select device Google account**, **Enter another email**, and **Purchase PRO**.
  - `android:installLocation="auto"`, `configChanges` for smooth orientation/resize, V1+V2 signing with `release.keystore`.
- **WordPress Plugin (`server/tubemute-access-api.php` `2.1.2`)**:
  - Synchronized plugin version `2.1.2` shown in WordPress Admin.
- **CI/CD (`.github/workflows/build.yml`)**:
  - Builds signed Release APK (`TubeMute-<version>.apk`) with Gradle 9.3.1 / JDK 21, verifies signature with `apksigner`, uploads versioned artifact (`TubeMute-<version>-APK`), and attaches `TubeMute-<version>.apk` to GitHub Releases on `v*` tags.
