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
5. Set a long random webhook secret. The payment integration must send it in the `X-TubeMute-Secret` header.
6. Add any existing users manually in the TubeMute admin page, including lifetime users.

The Android app calls:

- `POST /wp-json/tubemute/v1/check` — registers a new email, checks trial/PRO access and returns the checkout URL.
- `POST /wp-json/tubemute/v1/webhook` — receives authenticated payment entitlement events.

The app never receives a payment secret and cannot grant itself PRO. The old GitHub `users.txt` list and GitHub read token are no longer part of the access flow.

## Payment webhook contract

The webhook requires the `X-TubeMute-Secret` header and a JSON body containing the customer's `email` and an event. Paid/active events grant PRO; cancellation/revocation events remove paid access. The default entitlement is one year. An optional `expires_at` (Unix timestamp or date string) or `lifetime: true` can be supplied.

Supported paid event names include `payment.completed`, `payment.succeeded`, `subscription.active`, `license.created`, `license.activated`, `purchase.completed`, `active` and `paid`.

This endpoint is a TubeMute webhook contract. The Freemius / UA Pay Box side must be configured to forward verified payment events in this format; do not assume that pasting the URL alone makes a provider's native webhook payload compatible.

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
