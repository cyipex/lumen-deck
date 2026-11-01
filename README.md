# Lumen Desk

**Give your old Android tablet a new job: a beautiful control panel that sits next to your Windows PC.**

See the time and weather, control Spotify, change the volume of every app, switch your lights, run shortcuts with one tap – or use the tablet as a **second monitor**. Everything is set up from a simple window on your PC.

![Settings window on the PC](docs/screenshots/pc-tiles.jpg)

---

## What can it do?

| | |
|---|---|
| 🕒 **Clock & weather** | A big clock, the date, the weather and your PC's CPU / RAM usage. |
| 🎵 **Spotify** | See what's playing, pause, skip, seek – and browse your playlists and liked songs. Tap a song and it plays on your PC. |
| 🔊 **Volume mixer** | One slider for the whole PC and one for every app that plays sound (Spotify, Discord, your browser, games…). |
| ⚡ **One-tap tiles** | Open apps, files or websites (even in a specific browser profile), press keyboard shortcuts, mute your mic, lock or sleep the PC. |
| 💡 **Smart lights** | Turn Yeelight bulbs on and off. |
| 🖥️ **Second monitor** | The tablet becomes an extra screen for your PC – touch works like a mouse. |
| 🔔 **Notifications** | Messages from Discord, WhatsApp and other apps pop up on the tablet. |
| 🎨 **Your style** | 10 background colors, 3 clock fonts, multiple pages and layouts. |

![Volume mixer page](docs/screenshots/pc-mixer.jpg)

---

## What you need

- A **Windows 10 or 11** PC
- An **Android tablet** with Android 7 or newer (old tablets are perfect)
- Both on the **same Wi-Fi** – or connected with a USB cable
- Optional: **Spotify Premium** for the music controls

---

## Set it up (about 10 minutes)

### Step 1 – On your PC
1. Install **Node.js** (the free *LTS* version) from [nodejs.org](https://nodejs.org).
2. Download Lumen Desk: on this page click the green **Code** button → **Download ZIP**, and unzip it anywhere (for example into *Documents*).
3. Open the `pc` folder and **double-click `Setup.cmd`**.

That's it for the PC. Lumen Desk now runs in the background, starts with Windows, and has a **Lumen Desk** icon on your desktop that opens the settings.

> If Windows asks whether Node.js may use the network, click **Allow** (private networks) – the tablet needs it to find your PC.

### Step 2 – On your tablet
1. Download **`lumen-desk.apk`** from the [Releases](../../releases) page (on the right side of this page) – open the link directly on the tablet, or copy the file over.
2. Open the file to install it. If Android asks, allow installing apps from this source.
3. Open **Lumen Desk** on the tablet. It finds your PC by itself.

### Step 3 – Allow the tablet
On the PC, open the **Lumen Desk** settings → **Tablet** → click **Allow**. You only need to do this once.

Done! 🎉 Now make it yours: in the settings window click a tile to change what it does, drag tiles around, add pages, or pick a color under **Appearance**.

![Appearance settings](docs/screenshots/pc-appearance.jpg)

**Tip:** to make Lumen Desk the tablet's home screen, open the ⚙ settings on the tablet (bottom right of the clock card) → **Set as default launcher**.

---

## Extras (optional)

<details>
<summary><b>Spotify</b> – control music and browse your library</summary>

1. In the Lumen Desk settings on the PC, open **Spotify**.
2. Follow the three steps shown there: create a free app at [developer.spotify.com](https://developer.spotify.com/dashboard), paste the redirect address it shows, tick **Web API**, and copy the **Client ID** back.
3. Click **Log in to Spotify**.

Without this, the play / next / previous buttons still work (as media keys).
</details>

<details>
<summary><b>Smart lights</b> (Yeelight)</summary>

1. In the Yeelight app, open each bulb → Settings → turn on **LAN Control**.
2. In the Lumen Desk settings: **Lights** → **Search for bulbs**.
3. Click a tile, set *Action* to **Light** and pick the bulb.
</details>

<details>
<summary><b>Second monitor</b> – use the tablet as an extra screen</summary>

1. Install the free [Virtual Display Driver](https://github.com/VirtualDrivers/Virtual-Display-Driver/releases) (*VDD.Control* → **Install Driver**). It adds a virtual monitor to Windows.
2. Download [ffmpeg](https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip), and copy `ffmpeg.exe` (from its `bin` folder) into Lumen Desk's `pc\bin` folder.
3. Press **Win + P** → **Extend**. In *Settings → Display*, set the new monitor to your tablet's resolution (e.g. 1280 × 800).
4. In the Lumen Desk settings, add a tile with the action **Second monitor**.

On the tablet: tap = click, drag = drag, hold = right click, two fingers = scroll, **three-finger tap = back** to the panel.
</details>

<details>
<summary><b>USB cable</b> – faster and steadier than Wi-Fi, and the tablet charges</summary>

1. On the tablet, enable **Developer options** (tap *Build number* 7× in *Settings → About tablet*) and turn on **USB debugging**.
2. Download [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) and copy `adb.exe`, `AdbWinApi.dll` and `AdbWinUsbApi.dll` into Lumen Desk's `pc\bin` folder.
3. Plug the tablet in and accept the *Allow USB debugging* prompt. Lumen Desk switches to the cable by itself.
</details>

---

## Something doesn't work?

| Problem | Try this |
|---|---|
| The tablet keeps *Looking for PC…* | Make sure both are on the same Wi-Fi and Lumen Desk runs on the PC (desktop icon). Allow Node.js in the Windows firewall. Or enter the PC's address manually in the tablet's ⚙ settings. |
| Spotify shows nothing | Start playing something in Spotify on any device first. |
| Some playlists show no songs | Spotify only lets apps list songs of playlists *you* created; others can still be played with **Play** / **Shuffle**. |
| Second monitor is black | Check that Windows shows two displays (Win + P → Extend) and that `ffmpeg.exe` is in `pc\bin`. |
| Anything else | The settings window → **Tablet** → *Server status* shows a log of errors. |

---

## Install with an AI assistant

Using **Claude Code**, **Codex** or a similar coding agent? Open this folder and say:

> Install Lumen Desk on my PC and tablet by following AGENTS.md.

It will walk you through every step and ask before doing anything important.

---

## For developers

- `pc/` – Windows server (Node.js 22.5+, no build step). `run.js` supervises `server.js`; libraries in `lib/`, settings UI in `ui/`.
- `tablet/` – Android app (Kotlin, Jetpack Compose, Android 7.0+). Build with `./gradlew assembleRelease` (JDK 17, Android SDK 34).
- `shared/icons.json` – icons shared by both.

How it works: HTTP + WebSocket server on port 47801, auto-discovery over UDP broadcast (port 47802), settings only editable from the PC itself, new tablets need approval. Keyboard, mouse, volume and windows are controlled through small PowerShell helpers (Win32 / Core Audio). The second monitor is captured with ffmpeg `ddagrab`, encoded to H.264 and decoded by the tablet's hardware. Notifications are read from the Windows notification database (read-only). Optional env var `LUMEN_PC_NAME` changes the PC name shown on the tablet.

See [AGENTS.md](AGENTS.md) for details.

## Related
**Lumen TV** – the same look as a launcher for Android TV / Google TV.

## Credits
Fonts: [Inter](https://rsms.me/inter/), [Fraunces](https://github.com/undercasetype/Fraunces), [Outfit](https://github.com/Outfitio/Outfit-Fonts), [Space Grotesk](https://github.com/floriankarsten/space-grotesk) (SIL Open Font License). Weather by [Open-Meteo](https://open-meteo.com). Music via the Spotify Web API. Second monitor uses [Virtual Display Driver](https://github.com/VirtualDrivers/Virtual-Display-Driver) and [ffmpeg](https://ffmpeg.org) – both are installed separately by you and keep their own licenses.

## License
[MIT](LICENSE)
