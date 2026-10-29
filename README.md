# FridgeDash

A lightweight launcher/dashboard for the Hisense RQ760N4IFE refrigerator screen
(Android, no Google services). Plain Android Views, Java, no third-party
libraries, no background services.

## Features (v0.2)

- **Dashboard:** clock, date, network status, a large **Refrigerator** button
  back to the Hisense home screen, shortcut tiles (Fridge controls, Home
  Assistant, Browser, F-Droid — only those actually installed), and a grid of
  all apps. Long-press an app for its Android App Info.
- **Diagnostics:** read-only report of Android version, CPU ABIs, screen size
  and density, RAM/storage, WebView version, Google services, all Home-screen
  apps, vendor system apps and their activities. Lets you test-launch each Home
  activity and choose which one the Refrigerator button opens.

- **Self-update:** the Update button (and a quiet check every 6 hours while
  the dashboard is open) reads `version.json` from the latest GitHub Release,
  downloads the APK over Wi-Fi, verifies its SHA-256, package name and version,
  then opens Android's installer. The first time, Android asks you to allow
  FridgeDash to install apps.

### Safety

- No `CATEGORY_HOME` filter yet: installing FridgeDash cannot change or
  reset the default Home app. The Hisense Home stays default; the Android Home
  button always returns there.
- Read-only towards other apps. Uninstalling FridgeDash removes it completely.
- Refrigerator button order: manual choice → auto-detected Hisense Home
  activity → plain Home intent (goes to whatever the default Home is).

## Building

Every push to `main` runs GitHub Actions, which builds a signed release APK and
publishes a GitHub Release containing:

- `fridgedash.apk`
- `fridgedash.jpg`: the same file renamed, ready for the fridge's Bluetooth
  photo transfer (first install / fallback)
- `version.json`: what the in-app updater reads

Version is `0.2.<run number>`; versionCode is the run number.

Locally: Android Studio, or `gradle assembleRelease` with JDK 17 and the
Android SDK (compileSdk 34).

## Signing

`app/fridgedash.keystore` is committed so every build has the same signature,
which lets new versions install over old ones. It is a PKCS12 keystore encrypted
with AES-256 (PBKDF2). Its password exists only in the Actions secret
`FRIDGEDASH_KEYSTORE_PASSWORD` and is never committed. Builds fail without it.

## Installing on the fridge

1. On the phone, download `fridgedash.jpg` from the latest GitHub Release.
2. Fridge: My Apps → Album → +. Phone: share the `.jpg` via Bluetooth.
3. Fridge file manager: rename `.jpg` → `.apk`, tap it, install.
4. Open FridgeDash from KISS or the app list.

After that, use the Update button in FridgeDash.
