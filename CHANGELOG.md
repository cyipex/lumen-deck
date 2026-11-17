# Changelog

## 1.1.0 – 2026-11-17

### New
- **Touchpad and keyboard** – the tablet becomes a full touchpad (tap, two-finger scroll and right click, pinch zoom, drag, three-finger app switching) with a laptop-style keyboard: Ctrl, Alt, Win, F-keys, arrows, accented letters on long press.
- **Presentation remote** – big Next / Back buttons, timer, laser pointer pad; the tablet's volume keys switch slides.
- **Drawing tablet** – the tablet surface maps onto a whole PC monitor; one finger draws in Paint, Photoshop, Krita…
- **Clock screensaver** – after 2 minutes or from its button; styled in the PC settings: 20 clock fonts, your own photo and fonts, position, size and color.
- **20 clock fonts** plus uploading your own TTF / OTF font.
- **Claude usage** on the home screen, from Claude Code's status line.
- **Spotify start** – Play while nothing plays (or *Open Spotify*) starts the Spotify app on the PC, or the web player when it is not installed; the empty player offers your playlists.
- **Tablet updates over Wi-Fi** – the PC offers new tablet versions; no cable needed after this version.
- **Lumen Mirror** – a phone app that shows and controls an Android phone on the tablet.
- **Connection details** on the tablet and the PC (addresses, Wi-Fi, USB).
- **Layout backups** – the PC keeps the last 30 versions of your pages and tiles; restore with one click.
- Works on phones too, and on 16:9 and 4:3 tablets – the layout scales to fit.
- One-command install: `irm https://raw.githubusercontent.com/cyipex/lumen-desk/main/get.ps1 | iex`.

### Security
- Encrypted tablet connection: ECDH P-256 key exchange, pairing key per tablet and PC, AES-256-GCM. New tablets are allowed after comparing a 6-digit code.
- The settings window moved to `127.0.0.1:47803` and only accepts requests from itself.
- Second-monitor video uses one-time tickets and is encrypted.

### Fixed
- Settings could be replaced by the defaults if the file was briefly unreadable.
- USB connection dropped when adb restarted or after one slow answer.
- Tablet hopped between PCs when more than one announced itself.

**Note:** after updating, pair the tablet once again (compare the code, press Allow).

## 1.0.0 – 2026-11-01

First release: clock and weather, Spotify, volume mixer, one-tap tiles and macros, Yeelight lights, second monitor, notifications, Windows installer.
