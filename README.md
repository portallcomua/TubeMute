# TubeMute 2.3.0

TubeMute is an Android TV / Google TV / Android application with server-managed access, GitHub Releases self-update, in-app link/website browsing on smartphones, and offline MP4 saving.

## What's New in 2.3.0

- **In-App «Open link / site» on Smartphones (`2.3.0` / `versionCode 26`)**:
  - Hidden on Android TV / Smart TV (`FEATURE_LEANBACK` / `UI_MODE_TYPE_TELEVISION`).
  - Smartphones get a quick **URL** button next to Settings (and **Open link / site** inside Settings) to paste or open any HTTPS website or Facebook video link directly inside TubeMute, plus a 1-tap **Back to YouTube** option.
  - Detects progressive MP4 streams on opened websites and public Facebook video pages, offering the same **Yes / No** save-to-phone popup (`Movies/TubeMute`) and seamless switch to the built-in offline player.
- **Included from 2.2.0**:
  - Smartphone-only offline **360p MP4** saving on YouTube with **Yes / No** prompt and built-in offline `VideoView` player.
  - Automatic visible Google account detection + **Continue without sign-in** guest device mode.
  - Direct in-app GitHub Releases updater via `UpdateApkProvider` (`TubeMute-<version>.apk`).
- **WordPress Plugin (`server/tubemute-access-api.php` `2.3.0`)**:
  - Synchronized plugin version `2.3.0` shown in WordPress Admin.
