# TubeMute 2.0.0

TubeMute is an Android TV / Google TV / Android application with server-managed access.

## Components

- Android app: email registration, access check at launch, YouTube WebView and ad-skip experiment.
- WordPress plugin: central user database, trial periods, device records, email reminders, manual lifetime access, blocking and PRO status.
- Payment provider: configured from the TubeMute WordPress settings. A confirmed payment must reach the TubeMute webhook before PRO is granted.

## Server setup

1. Install or replace the existing TubeMute Access API plugin using `server/tubemute-access-api.php`.
2. Keep the existing plugin active; the updated code creates or upgrades its database tables automatically.
3. In WordPress admin, open **TubeMute**.
4. Set trial length, reminder lead time, administrator email and the PRO checkout URL.
5. Enter the UA Pay Box project/service ID. TubeMute reads the existing `upb_licenses` table from the same WordPress database, so the current Pay Box plugin can remain unchanged.
6. Set the Pay Box checkout URL as the PRO payment link.
7. Add any existing users manually in the TubeMute admin page, including lifetime users.

The Android app calls:

- `POST /wp-json/tubemute/v1/check` — registers a new email, checks trial/PRO access and returns the checkout URL.
- `POST /wp-json/tubemute/v1/webhook` — optional authenticated payment relay.
- `POST /wp-json/tubemute/v1/freemius/webhook` — optional direct signed Freemius integration. The primary no-code-change integration is reading UA Pay Box's existing license table.

The app never receives a payment secret and cannot grant itself PRO. The old GitHub `users.txt` list and GitHub read token are no longer part of the access flow.

## Payment webhook contract

UA Pay Box remains the payment processor and continues receiving Freemius webhooks through its existing endpoint. TubeMute reads the resulting license row from the same WordPress database using the configured service ID and customer email. This is the preferred integration because the UA Pay Box plugin code does not need to be changed. Optional direct Freemius and custom webhook endpoints are also available, but are not required for the standard setup.

## Android compatibility

- Application ID: `com.custom.reklamoid` (preserved for updates).
- Version: 2.0.0 / versionCode 13.
- Minimum Android API: 19 (Android 4.4).
- Target SDK: 28.
- Android TV / Google TV / Android smartphones. Samsung Tizen and LG webOS are not Android and cannot install APK files.

## Build and signing

GitHub Actions builds a signed Release APK and USB installer. The same permanent signing key must be used for every update.

Required GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Never commit the keystore or passwords.
