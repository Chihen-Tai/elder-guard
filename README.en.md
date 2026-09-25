<p align="center"><img src="docs/images/icon.png" width="96" alt="Elder Guard icon"></p>

<h1 align="center">Elder Guard (守門員)</h1>

<p align="center">
Helps older people find and stop the apps that keep throwing full-screen ads<br>
or fake "your phone is infected" warnings. Everything runs on the phone —
<b>the app has no internet permission</b>.
</p>

<p align="center"><a href="README.md">繁體中文</a> ｜ <a href="README.zh-CN.md">简体中文</a> ｜ <b>English</b></p>

<p align="center">
<img src="docs/images/home.jpg" width="30%" alt="Home">
<img src="docs/images/detail.jpg" width="30%" alt="App detail">
<img src="docs/images/settings.jpg" width="30%" alt="Settings">
</p>

> **Status: 0.5.0, family testing.** Tested on a vivo (Android 16), an OPPO (Android 15) and the Android 16 emulator.
> Not a production release; the APK is debug-signed. The UI is in Traditional Chinese, and so are the docs under `docs/`.

## Why

A familiar story: someone installs a "PDF reader", "QR scanner" or "phone cleaner" from the Play Store. From then on,
full-screen ads appear over whatever they are doing, or notifications shout "Virus detected! Clean now!". A younger
person can tell which app is behind it; an older person usually just concludes that the phone is broken.

Elder Guard answers one question — **which app is doing this** — and walks the user through removing it.

## Features

| Feature | How |
|---|---|
| **Check all apps** | Static review of every installed app: number of ad SDKs, wake-up triggers (boot/power/unlock), a hidden launcher icon, bait categories (cleaner, antivirus, PDF, QR, photo vault, …), permissions that do not fit the stated purpose, deliberately scrambled component names. Algorithm: [docs/ALGORITHM.md](docs/ALGORITHM.md) |
| **Find the ad that just popped up** | Reads the system's screen-change history (Usage Access) to find which app put a full-screen ad page over *another* app. Works retroactively, even if the guard was not running at that moment |
| **Fake-warning notifications** | Flags "threat word + urgency word" notifications and names the sending app — or, for Chrome web push, the web site. Text is classified in memory and never stored |
| **Auto-close repeated pop-ups** (optional) | When the same app covers another app with an ad for the **second** time within 30 minutes, the guard presses Back/Home. The first pop-up is only recorded. Enabled in Accessibility settings |
| **Check new installs immediately** | 3 s, 2 min and 30 min after install (some apps hide their icon only after the first launch) |
| **Guided removal** | Force stop first (the ads stop), then uninstall. The guard cannot remove apps by itself and never pretends it can |
| **Diagnostic log** (opt-in) | With consent, records scan results, errors and auto-close decisions on the phone. "Settings → Export log" hands a text file to the system share sheet; the user decides who gets it |

The UI is designed for an older person using it alone: large type, one task per screen, robust to enlarged system
fonts, and Back always goes one page back. See [docs/DESIGN-NOTES.md](docs/DESIGN-NOTES.md).

## Privacy

- **No `INTERNET` permission.** Nothing leaves the phone unless the user exports and shares the log.
- Notification text is never stored; for web push only the site host is kept.
- The accessibility service receives window-change events only and **cannot read screen content**
  (`canRetrieveWindowContent=false`).
- The diagnostic log is off by default, kept at most 14 days, and deleted when consent is turned off.

## What it cannot do

- **Ads inside YouTube videos or web pages** — those are the platform's own ads, not another app.
- **Floating overlay windows** — Android offers no public way to tell which app owns them.
- Once the guard is **force-stopped** it gives no protection until it is opened again.
- Switching back (via Recents) to an app whose top page is an ad counts as one pop-up; public APIs cannot tell this
  apart from an ad that launched itself.
- "Nothing found" does not mean "safe", and the app says so.

## Install (test build)

1. Download `elder-guard-0.5.0-debug.apk` from [Releases](../../releases). Android 8.0 or newer.
2. Open the app → Settings, and turn on everything under "protection permissions": Usage access, Notification access,
   Notifications, Unrestricted battery.
3. **OPPO / realme (ColorOS):** also enable App info → Battery usage → "Allow background activity".
4. **vivo:** if the battery page only shows a switch, tap the words "Allow background usage" and choose "Unrestricted".
5. For auto-close on Android 13+: sideloaded apps first need App info → ⋮ → "Allow restricted settings" before the
   guard can be enabled under Accessibility.

## Development

```bash
# Android app (JDK 17+ and the Android SDK)
cd android
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # unit tests; replays of real device events are skipped without local data

# Desktop reference implementation (needs aapt2; set AAPT2=/path/to/aapt2 if not on PATH)
python3 engine/egda.py path/to/app-dir-or-apks

# Evaluation (needs a local APK dataset, not in the repo)
EG_DATA=/path/to/dataset python3 engine/evaluate.py
```

**Test apps** (`testapps/`, **emulator only**):

- `adsim` simulates an app that hides its icon, shows ad pages over other apps and posts fake warnings.
- `normalapp` is a well-behaved app with an in-app interstitial, used to check for false positives.

Neither contains real ad SDKs or network access.

## Layout

```
android/     the app (Kotlin, Jetpack Compose)
engine/      EGDA desktop reference implementation and evaluation scripts (Python)
testapps/    emulator-only test apps
tools/       test helpers (ui.sh: read/tap on-screen text; safe_shot.sh: screenshot only when the guard is on top)
docs/        plan, algorithm, review standard, design notes, a real incident report (Traditional Chinese)
design/      icon source drawing
```

## License

[GPL-3.0](LICENSE). The icon is a hand-drawn "fk Ad".
