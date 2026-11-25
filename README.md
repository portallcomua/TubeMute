# TubeMute 2.3.3

TubeMute is an Android TV / Google TV / Android application with server-managed access, GitHub Releases updates, in-app link/website browsing on smartphones, and offline MP4 saving.

## What's New in 2.3.3

- **WordPress Plugin GitHub Updater & Whitelist (`server/tubemute-access-api.php` `2.3.3`)**:
  - Added WordPress native GitHub Releases update integration (`Plugins` page **"Перевірити оновлення"** / 1-click update via `tubemute-wp-plugin.zip`, plus status & download buttons directly inside `TubeMute` admin).
  - Added **«Білий список email (довічний PRO)»** in plugin Settings so whitelisted emails always receive `LIFETIME` PRO access.
  - Protected `lifetime = 1` accounts from being overwritten by UA Pay Box sync.
- **GitHub Actions Release Packaging (`.github/workflows/build.yml`)**:
  - Automatically packages `tubemute-wp-plugin.zip` (`tubemute-access-api/tubemute-access-api.php`) alongside `TubeMute-<version>.apk` on every release tag.
- **Clearer License Account Labels (`2.3.3` / `versionCode 29`)**:
  - Clarified *"Акаунт ліцензії TubeMute (Google)"* / *"Інший email для ліцензії TubeMute"* in Settings so users don't confuse TubeMute license sign-in with YouTube web login.
- **Included from 2.3.2 / 2.3.1 / 2.3.0**:
  - Standard `INTERNET`-only manifest permission (Play Protect safe).
  - Draggable smartphone **URL | ⚙** bar at bottom-left, **TubeMute PRO** info dialog, in-app **Open link / site** browser, and offline MP4 saving (`Movies/TubeMute`).
