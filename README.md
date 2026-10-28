# FridgeDash

A lightweight launcher/dashboard for the Hisense RQ760N4IFE refrigerator screen
(Android, no Google services). Plain Android Views, Java, no third-party
libraries, no background services.

## v0.1 — probe + safe skeleton

- **Dashboard:** clock, date, network status, a large **Refrigerator** button
  back to the Hisense home screen, shortcut tiles (Fridge controls, Home
  Assistant, Browser, F-Droid — only those actually installed), and a grid of
  all apps. Long-press an app for its Android App Info.
- **Diagnostics:** read-only report of Android version, CPU ABIs, screen size
  and density, RAM/storage, WebView version, Google services, all Home-screen
  apps, vendor system apps and their activities. Lets you test-launch each Home
  activity and choose which one the Refrigerator button opens.

### Safety

- No `CATEGORY_HOME` filter in v0.1: installing FridgeDash cannot change or
  reset the default Home app. The Hisense Home stays default; the Android Home
  button always returns there.
- Read-only towards other apps. Uninstalling FridgeDash removes it completely.
- Refrigerator button order: manual choice → auto-detected Hisense Home
  activity → plain Home intent (goes to whatever the default Home is).

## Building

Every push to `main` runs GitHub Actions, which builds a signed release APK and
publishes a GitHub Release containing:

- `fridgedash-<version>.apk`
- `fridgedash-<version>.jpg` — the same file renamed, ready for the fridge's
  Bluetooth photo transfer.

Locally: Android Studio, or `gradle assembleRelease` with JDK 17 and the
Android SDK (compileSdk 34).

## Signing

`app/fridgedash.keystore` and `app/keystore.properties` are committed so every
build has the same signature, which lets new versions install over old ones.
Keep this repository **private**.

## Installing on the fridge

1. On the phone, download the `.jpg` from the latest GitHub Release.
2. Fridge: My Apps → Album → +. Phone: share the `.jpg` via Bluetooth.
3. Fridge file manager: rename `.jpg` → `.apk`, tap it, install.
4. Open FridgeDash from KISS or the app list.
