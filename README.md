# TubeMute 2.3.2

TubeMute is an Android TV / Google TV / Android application with server-managed access, GitHub Releases updates, in-app link/website browsing on smartphones, and offline MP4 saving.

## What's New in 2.3.2

- **Play Protect Install Compatibility Fix (`2.3.2` / `versionCode 28`)**:
  - Removed `REQUEST_INSTALL_PACKAGES`, `WRITE_EXTERNAL_STORAGE`, and `UpdateApkProvider` so the manifest uses only the standard `INTERNET` permission (matching `2.1.1`) and avoids triggering `INSTALL_FAILED_VERIFICATION_FAILURE` on Google Play Protect.
  - Updates still open the direct `TubeMute-<version>.apk` GitHub Release asset link in 1 tap.
- **Included from 2.3.1 / 2.3.0 / 2.2.0**:
  - Draggable smartphone **URL | ⚙** bar at bottom-left (no overlap with YouTube's Search icon).
  - In-app **TubeMute PRO** benefits & account details dialog before opening payment.
  - In-app **Open link / site** browser + offline MP4 saving (`Movies/TubeMute` via MediaStore) for YouTube (`360p MP4`), websites, and public Facebook videos on smartphones (hidden on Android TV).
  - Automatic visible Google account detection + **Continue without sign-in** guest device mode.
- **WordPress Plugin (`server/tubemute-access-api.php` `2.3.2`)**:
  - Synchronized plugin version `2.3.2` shown in WordPress Admin.
