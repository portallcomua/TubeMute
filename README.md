# TubeMute 2.4.2

TubeMute is an Android TV / Google TV / Android application with server-managed access, GitHub Releases updates, ad-free YouTube & external video browsing on smartphones, and offline MP4 saving.

## What's New in 2.4.2

- **Simple TV Remote Settings Access (`2.4.2` / `versionCode 35`)**:
  - Hold **Back** anywhere on Android TV (~0.65s) or press **Back** at the home screen to open **TubeMute Settings** on any minimal TV remote (plus Menu / Info / Guide / Blue / Yellow keys).
  - Updated top-right TV badge to `⚙ Налаштування (утрим. Назад)`.
- **Zero-Flash YouTube Ad Stripping**:
  - Fixed XHR listener registration order (`open()` instead of `send()`) and wrapped `Response.prototype.json`/`.text` so `/youtubei/v1/player` and `/next` ad fields are stripped even when `window.fetch` was cached early.
  - Added `MutationObserver` + opacity hiding during `.ad-showing` fallback to prevent pre-roll frame flashes.

## What's New in 2.4.1

- **Android TV Banner, Clean Poster & TV Ad Stripper (`2.4.1` / `versionCode 34`)**:
  - Replaced blank TV launcher banner (`tv_banner.png`) with the branded TubeMute 320x180 banner.
  - Added transparent `WebChromeClient.getDefaultVideoPoster()` so the full-screen sideways play-triangle no longer flashes before video playback.
  - Intercepts YouTube `fetch` and `XMLHttpRequest` player/next JSON responses to strip `adPlacements`, `playerAds`, `adSlots`, and `/ad_break` before ads start, and limits TV fallback playbackRate/seek to buffered ranges so hardware decoders never stall on a gray screen.

## What's New in 2.4.0

- **YouTube & External Site Ad/Popunder Blocker (`2.4.0` / `versionCode 33`)**:
  - Blocks YouTube ad endpoints (`/pagead/`, `/api/stats/ads`, `/ptracking`, `/get_midroll_info`, `doubleclick.net`, `googlesyndication.com`) and injects an automatic YouTube pre-roll/mid-roll ad skipper & cosmetic ad-hider.
  - Blocks casino/betting (`777`, popunders, ad trackers) requests, `window.open` popups, and unprompted cross-site redirects on external websites, keeping playback strictly inside TubeMute.
- **Website MP4 Download & Cross-Origin Iframe Detection**:
  - Added `WebView.setDownloadListener` so site "Download" buttons trigger offline MP4 saving into `Movies/TubeMute`.
  - Triggers the smartphone MP4 save prompt directly when progressive `.mp4` streams are detected inside cross-origin embedded players (`<iframe>`).
- **PRO Price in App & WordPress Plugin (`2.4.0`)**:
  - Added **«Вартість PRO (текст для додатку)»** in WordPress Settings, returned in `/wp-json/tubemute/v1/check` as `price_text`, and displayed inside the in-app **TubeMute PRO** dialog.
  - Prioritized 1-tap **Device Google Account** option on first launch.
- **Includes all `2.3.x` improvements**:
  - Google 2FA domain & `onPause` fix, **«Скинути вхід YouTube»**, WP plugin GitHub auto-updater (`tubemute-wp-plugin.zip`), **«Білий список email (довічний PRO)»**, and user Delete / End Trial controls.
