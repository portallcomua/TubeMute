# TubeMute 2.0.9

TubeMute is an Android TV / Google TV / Android application with server-managed access.

## Repository layout

The Android application is a single-module Gradle project stored directly in the repository root:
- `build.gradle` — Android application build configuration.
- `settings.gradle` and `gradle.properties` — Gradle project settings.
- `src/main/` — Android manifest, Kotlin source code and app resources.
- `tubemute-access-api.php` — WordPress access API plugin.
- `.github/workflows/build.yml` — automated APK build, signing and release.

There is no nested `app/` or `server/` directory.

## Components

- Android app: email registration, access check at launch, YouTube WebView and ad-skip functionality.
- WordPress plugin: central user database, trial periods, device records, email reminders, manual lifetime access, blocking and PRO status.
- Payment provider: configured from the TubeMute WordPress settings. A confirmed payment must reach the TubeMute webhook before PRO is granted.

## Server setup

1. Install or replace the existing TubeMute Access API plugin using `tubemute-access-api.php`.
2. Keep the existing plugin active; the updated code creates or upgrades its database tables automatically.
3. In WordPress admin, open **TubeMute**.
4. Set trial length, reminder lead time, administrator email and the PRO checkout URL.
5. Enter the UA Pay Box project/service ID. TubeMute reads the existing `upb_licenses` table from the same WordPress database, so the current Pay Box plugin can remain unchanged.
6. Set the Pay Box checkout URL as the PRO payment link.
7. Add any existing users manually in the TubeMute admin page, including lifetime users.

The Android app calls:
- `POST /wp-json/tubemute/v1/check` — registers a new email, checks trial/PRO access and returns the checkout URL.
- `POST /wp-json/tubemute/v1/webhook` — optional authenticated payment relay.
- `POST /wp-json/tubemute/v1/freemius/webhook` — optional direct signed Freemius integration.

## Android compatibility

- Application ID: `com.custom.reklamoid`.
- Version: 2.0.9 / versionCode 22.
- Minimum Android API: 19.
- Target SDK: 34.
- Android TV / Google TV / Android smartphones. Samsung Tizen and LG webOS cannot install Android APK files.

## Build and signing

GitHub Actions builds a signed Release APK and USB installer. The same permanent signing key must be used for every update.

Required GitHub Actions secrets:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Never commit the keystore or passwords.
