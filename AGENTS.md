# Instructions for AI coding agents

This file tells an agent (Claude Code, Codex, …) how to install and work on **Lumen Desk**. Read `README.md` first for what the project is.

## Repository layout
- `pc/` – Windows server (Node.js, no build step). Entry: `run.js` (supervisor) → `server.js`. Libraries in `lib/`, settings UI in `ui/`.
- `tablet/` – Android app (Kotlin, Jetpack Compose, minSdk 24). Package `com.lumen.desk`.
- `shared/icons.json` – icon paths shared by the PC UI and the tablet (packed into the APK as an asset).

## Installing for a user

The easy way for most users: `LumenDeskSetup.exe` from the GitHub Releases (PC) + `lumen-desk.apk` (tablet). Use the steps below when installing from source.

Do the steps in order, check each one before moving on, and tell the user what you are doing.

1. **Check the PC**: Windows 10/11 and `node --version` ≥ 22.5. If Node is missing or older, ask the user to install it from nodejs.org (do not install it silently).
2. **Server dependencies**: `npm install` in `pc/`.
3. **Optional tools** – ask the user which features they want before downloading anything:
   - Second monitor → ffmpeg (`ffmpeg-release-essentials.zip` from gyan.dev, verify the published SHA-256, copy `ffmpeg.exe` to `pc/bin/`). The user must install Virtual Display Driver themselves (a system driver that needs admin rights) and set Windows to *Extend these displays*.
   - USB connection → Android platform-tools; copy `adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll` to `pc/bin/`.
4. **Start the server**: `pc/Setup.cmd` does `npm install` + `install.ps1` (desktop icon + start with Windows) + starts it – run it only if the user agrees; otherwise start it with `node run.js` in `pc/`. Confirm `http://127.0.0.1:47801/ping` answers.
5. **Build the tablet app**: needs JDK 17 and the Android SDK (platform 34). Create `tablet/local.properties` with `sdk.dir=…` if missing, then `./gradlew assembleRelease` in `tablet/`.
6. **Install on the tablet**: the user enables Developer options → USB debugging and connects the tablet. Run `adb shell am force-stop com.lumen.desk` **before** every `adb install -r` – otherwise old processes of the app can keep running and fight over the connection (only a reboot clears them).
7. **Pair**: open the app on the tablet; it shows "Allow the tablet on the PC" with a 6-digit code. The user approves it in the settings window under **Tablet** after checking that the code there is the same.
8. **Optional setup** the user does in the settings window: Spotify (Client ID + login), Yeelight bulbs, tiles and pages.

## Rules – never do these
- Never put secrets in the repository: `pc/data/` holds the user's config, Spotify tokens and allowed devices and is git-ignored. Keep it that way.
- Never log the user into accounts (Spotify, Google, GitHub) or enter passwords for them; tell them what to do.
- Never change Windows system settings, power plans or drivers without asking; the user installs drivers themselves.
- Never run recursive deletes with relative paths; use absolute paths and stop on the first error.
- Do not commit `pc/bin/` (ffmpeg is over GitHub's 100 MB limit; adb/ffmpeg have their own licenses).

## Working on the code
- Server: `node --check` every changed file; restart by killing the process on port 47801 – the supervisor (`run.js`) starts it again within a second.
- Security: the tablet protocol is encrypted (`pc/lib/secure.js` ⇄ `tablet/.../Secure.kt`, must stay identical); never add a plain-text path, and never trust a tablet by its `deviceId` alone. The settings server (port 47803) must stay bound to 127.0.0.1 and keep its Host/Origin/`X-Lumen` checks. Test copy of the server: `LUMEN_PORT=48801 LUMEN_SETTINGS_PORT=48803 LUMEN_QUIET=1 node server.js`.
- Settings UI: plain JS, no framework; saves are sent as path/value patches (`PATCH /api/config`), so never replace the whole config.
- Tablet: the layout is designed for 1280×800 and scaled (`.u` / `.us` units in `Style.kt`). Android 7 has no variable fonts, so static font files are bundled.
- Installer: `npm run installer` in `pc/` (needs Inno Setup 6). In the packaged exe `lib/paths.js` puts data and tools in `%APPDATA%\Lumen Desk`; never write next to the exe (Program Files is read-only).
- Second monitor: the tablet's hardware decoder needs a constant frame stream; do not enable ffmpeg `dup_frames=0`.
- Keep comments and UI text in English.
