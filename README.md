# TubeMute 2.1.0

TubeMute is an Android TV / Google TV / Android application with server-managed access and GitHub Releases update checks.

## What's New in 2.1.0

- **Android App (`2.1.0` / `versionCode 16`)**:
  - System Google Account picker (`AccountManager.newChooseAccountIntent`) on initial launch when no account is saved, falling back smoothly to manual email entry.
  - Floating Settings button (`⚙`, also accessible via TV remote `Menu`/`Settings` keys and Paywall/Error dialogs) containing: **Test access**, **Check for updates (GitHub)**, **Select device Google account**, **Enter another email**, and **Purchase PRO**.
  - Automatic GitHub Releases update check (`portallcomua/TubeMute`) opening the release/APK URL safely in the system browser without `REQUEST_INSTALL_PACKAGES`.
  - Play Protect hardening retained (`targetSdk 34`, `allowBackup="false"`, `usesCleartextTraffic="false"`, no JS ad-click injection, `SharedPreferences` UUID, HTTPS WebView domain allowlist, V1+V2 signing with `release.keystore`).
- **WordPress Plugin (`server/tubemute-access-api.php` `2.1.0`)**:
  - Synchronized plugin version `2.1.0` shown in WordPress Admin.
- **CI/CD (`.github/workflows/build.yml`)**:
  - Builds signed Release APK with Gradle 9.3.1 / JDK 21, verifies signature with `apksigner`, uploads artifact, and attaches APK to GitHub Releases on `v*` tags.
