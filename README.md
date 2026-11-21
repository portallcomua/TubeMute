# TubeMute

TubeMute is an Android TV application based on Android WebView.

## Features

- Android TV launcher support
- YouTube web interface
- Remote-control navigation
- Full-screen video support
- Experimental ad-skip functionality
- Gmail registration and server-side access verification
- 7-day trial period
- Ukrainian, English and Russian interface strings

## Device compatibility

- Minimum Android API: 19 (Android 4.4)
- Target SDK: 28
- Application ID: com.custom.reklamoid
- APK is built as a signed Release package.
- YouTube playback compatibility also depends on the Android System WebView installed on the TV.

## USB installation

1. Download the `TubeMute-USB-Installer` artifact from the latest successful GitHub Actions run.
2. Extract the ZIP archive to a USB flash drive.
3. Connect the flash drive to the Android TV.
4. Open the TV's File Manager, select `TubeMute.apk`, and confirm installation.
5. Allow installation from that file manager if Android asks.

Android TV requires the user to confirm APK installation. A USB drive cannot silently install an application.

## Permanent release signing

The Release APK is signed with one permanent private key. Keep the keystore backup safe. Every future update must use the same keystore, alias and passwords or Android may reject the update.

GitHub Actions repository secrets required:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Never commit the keystore or passwords to this public repository.

## Build

The workflow builds only the signed Release APK and the USB installer ZIP. It does not publish a Debug APK.
