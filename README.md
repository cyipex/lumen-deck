# Lumen Desk

Turn an old Android tablet into a control panel for your Windows PC – with a dark-glass look, Spotify, a volume mixer, smart lights, macro tiles, notifications and even a **second monitor**.

A small Node.js server runs on the PC; the tablet app connects to it over Wi-Fi or a USB cable.

> Built and tested on a Huawei MediaPad T3 10 (Android 7.0) with a Windows 11 laptop. Other devices should work but are not tested yet.
>
> Looking for the TV launcher with the same look? See **Lumen TV**.

## Features

**On the tablet**
- Home screen with a big clock, date, weather, CPU / RAM and a Spotify card
- Spotify: play / pause / skip / seek, plus a **library** (liked songs, your playlists, search) – tap a track and it plays on the PC
- PC volume slider, mute, audio output switch (shows the Windows volume popup)
- **Mixer page**: a volume slider for every app that is playing sound (Spotify, Discord, browser, games…)
- Tiles for macros (shortcuts, apps, files, web pages in a chosen browser profile, media keys, system actions), Yeelight bulbs and page switching; 4 × 3 and big 3 × 2 layouts, multiple pages
- **Second monitor**: the tablet shows a virtual Windows display; touch = mouse (tap, drag, hold for right click, two-finger scroll, three-finger tap to go back)
- Notifications from Windows apps (Discord, WhatsApp…) pop up on the tablet
- App catalog, launcher settings (appearance, battery saving, set/exit default launcher)
- Dims the screen after 3 minutes without touch, reconnects by itself, prefers the USB cable when plugged in

**On the PC** – a settings window (`http://127.0.0.1:47801`) to edit pages and tiles with drag & drop, pick colors and the clock font, set up Spotify and lights, approve tablets and read the server log.

## Requirements

| Part | Needs |
|---|---|
| PC | Windows 10/11, [Node.js](https://nodejs.org) **22.5+** |
| Tablet | Android 7.0+ |
| Spotify (optional) | Spotify Premium + a free app at developer.spotify.com |
| Lights (optional) | Yeelight bulbs with *LAN Control* enabled |
| Second monitor (optional) | [Virtual Display Driver](https://github.com/VirtualDrivers/Virtual-Display-Driver), [ffmpeg](https://www.gyan.dev/ffmpeg/builds/) |
| USB connection (optional) | `adb` from [Android platform-tools](https://developer.android.com/tools/releases/platform-tools), USB debugging on the tablet |

## Install

### 1. PC server
```bash
cd pc
npm install
```
Optional tools go into `pc/bin/` (not included in the repo):
- `ffmpeg.exe` – from the *ffmpeg-release-essentials* zip (`bin/ffmpeg.exe` inside it)
- `adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll` – from Android platform-tools

Then run `pc/install.ps1` once (right-click → *Run with PowerShell*, or `powershell -ExecutionPolicy Bypass -File pc/install.ps1`). It adds a **Lumen Desk** desktop icon and starts the server with Windows. You can also start it by hand with `node run.js` in `pc/` – the supervisor restarts the server after a crash; logs are in `pc/data/logs/`.

### 2. Tablet app
Build it (Android SDK with platform 34, JDK 17):
```bash
cd tablet
./gradlew assembleRelease
```
Install `tablet/app/build/outputs/apk/release/app-release.apk` on the tablet (`adb install -r …`), open **Lumen Desk** and set it as the default home app if you like. The tablet finds the PC automatically; approve it on the PC under **Tablet**.

> The release build is signed with the debug key for convenience. Use your own key if you distribute it.

### 3. Optional features
- **Spotify** – in the PC settings open *Spotify* and follow the three steps (create an app, redirect URI `http://127.0.0.1:47801/spotify/callback`, tick *Web API*, paste the Client ID, log in).
- **Lights** – enable *LAN Control* for each bulb in the Yeelight app, then *Lights → Search for bulbs*.
- **Second monitor** – install Virtual Display Driver, add your tablet's resolution (e.g. `1280×800`) to `C:\VirtualDisplayDriver\vdd_settings.xml`, set Windows to *Extend these displays* (Win + P), then add a tile with the action *Second monitor*.
- **USB cable** – enable USB debugging on the tablet and plug it in; the server sets up `adb reverse` by itself.

## Install with an AI coding agent

Open this repository in **Claude Code**, **Codex** or a similar agent and say:

> Install Lumen Desk on my PC and tablet by following AGENTS.md.

[`AGENTS.md`](AGENTS.md) describes every step, what to ask you, and what never to do.

## How it works
- HTTP + WebSocket server on port **47801**; the PC announces itself over UDP broadcast on port **47802**, so the tablet finds it without configuration.
- Settings can only be changed from the PC itself (`127.0.0.1`); new tablets must be approved once.
- Keyboard, mouse, volume and window focus go through small persistent PowerShell helpers (Win32 / Core Audio), so actions take milliseconds.
- The second monitor is captured with ffmpeg `ddagrab`, encoded to H.264 (AMD AMF, falls back to libx264) and decoded by the tablet's hardware decoder.
- Notifications are read (read-only) from the Windows notification database.

## Credits
- Fonts: [Inter](https://rsms.me/inter/), [Fraunces](https://github.com/undercasetype/Fraunces), [Outfit](https://github.com/Outfitio/Outfit-Fonts), [Space Grotesk](https://github.com/floriankarsten/space-grotesk) – SIL Open Font License
- Weather: [Open-Meteo](https://open-meteo.com) · Music: Spotify Web API
- Not bundled, downloaded by you: ffmpeg (LGPL/GPL), Android platform-tools, Virtual Display Driver

## License
[MIT](LICENSE)
