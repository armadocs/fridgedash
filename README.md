# FridgeDash

A lightweight launcher/dashboard for the Hisense RQ760N4IFE refrigerator screen
(Android, no Google services). Plain Android Views and Java; the only library is
OkHttp (for the voice WebSocket). No background services.

## Features (v0.3)

- **Home screen:** glass cards over a daily portrait nature photo (Bing photo of
  the day, cached on the device; bundled landscape until the first download).
  Clock, date, Wi-Fi, today's brief (placeholder until the brief is connected),
  and a dock: Fridge · Home Assistant · **Ask ChatGPT** · Calendar · Apps.
- **ChatGPT voice:** full-screen call using OpenAI's Realtime API
  (`gpt-realtime-2.1`, WebSocket, 24 kHz PCM). The API key is entered on the
  fridge in Settings and stored only there. The mic pauses while ChatGPT speaks
  unless "interrupt by speaking" is on. Leaving the screen ends the call; 2 min of
  silence hangs up.
- **Settings:** API key (paste + test), voice, mic test, model, daily photo
  on/off, text size, updates.
- **Layout scaling:** every screen is laid out on a fixed 540dp-wide canvas
  (adjustable), so the design matches the mockup whatever the panel density.

- **Apps:** grid of all installed apps. Long-press an app for its Android App Info.
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

Version is `<base>.<run number>`; versionCode is the run number.

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
