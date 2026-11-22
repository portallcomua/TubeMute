# TubeMute 2.0.4 — verification candidate

## 2.0.4 candidate changes

- Keeps the Google account chooser and manual email fallback.
- Restores target SDK 28, matching the known-installable 1.7.3 baseline, to isolate the target SDK change during Play Protect troubleshooting.
- Restores explicit Android manifest hardening: backup disabled and cleartext traffic disabled.
- Keeps application ID, permanent signing key, server access, trial and PRO logic unchanged.

## 2.0.2 candidate changes

- First-run setup offers the Android system Google-account picker; selected account email is used only as the TubeMute account email.
- Manual email entry remains available when the account picker is unavailable or the user wants another billing email.
- The confirmed email is stored locally and reused for server-side trial, PRO and payment checks.
- The account picker does not sign the user into YouTube and does not add broad account/contact permissions.
- Application ID and permanent release signing configuration are retained for in-place upgrades.
- Target SDK 34 was tested in 2.0.2/2.0.3 and is reverted to API 28 in this diagnostic candidate.

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
- Diagnostic candidate: 2.0.4 / versionCode 17.
- Minimum Android API: 19 (Android 4.4).
- Target SDK: 28 (restored to the 1.7.3 baseline for controlled verification testing).
- Android TV / Google TV / Android smartphones. Samsung Tizen and LG webOS are not Android and cannot install APK files.

## Build and signing

GitHub Actions builds a signed Release APK and USB installer. The same permanent signing key must be used for every update.

Required GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Never commit the keystore or passwords.
