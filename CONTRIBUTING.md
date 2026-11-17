# Contributing to Lumen Desk

Thanks for wanting to help! Bug reports, ideas, translations and code are all welcome.

## Ways to help

- **Report a bug** – open an issue with the *Bug report* template. Say what you did, what happened and which tablet and Windows version you use.
- **Suggest a feature** – use the *Feature request* template. A sketch or screenshot helps a lot.
- **Pick an issue** – issues labelled [`good first issue`](../../issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) are small and well described.
- **Translate** – the tablet app and the PC settings are English; a translation is a great first contribution.

## How the project is built

| Folder | What it is | Language |
|---|---|---|
| `pc/` | The Windows app: server for the tablet, settings window (`pc/ui`), installer | Node.js, plain HTML/CSS/JS |
| `tablet/` | The Android tablet app | Kotlin, Jetpack Compose |
| `phone/` | Lumen Mirror – shows an Android phone on the tablet | Kotlin |
| `shared/` | Icons and clock fonts used by both | JSON, TTF |
| `docs/` | Live demo (GitHub Pages) and screenshots | HTML |

More detail for working on the code: [AGENTS.md](AGENTS.md).

## Run it from source

**PC** (Windows 10/11, Node.js 22 or newer):

```bash
cd pc
npm install
npm start          # server + settings at http://127.0.0.1:47803
npm test           # security and protocol tests (uses its own ports and data folder)
```

**Tablet app** (JDK 17 and the Android SDK, platform 34):

```bash
cd tablet
./gradlew assembleRelease
```

## Pull requests

1. Fork the repo and create a branch from `main`.
2. Keep the change focused – one fix or feature per pull request.
3. Run `npm test` in `pc/` if you touched the PC side, and build the tablet app if you touched it.
4. Describe what changed and how you tested it. Screenshots for anything visual.

The CI builds the tablet and phone apps and runs the PC tests on every pull request.

## Ground rules

- **Security first.** The tablet connection is encrypted (`pc/lib/secure.js` and `tablet/.../Secure.kt` must stay compatible). Never add a plain-text path or trust a tablet by its id alone. Report security problems privately – see [SECURITY.md](SECURITY.md).
- **Privacy.** No telemetry, no accounts, no cloud. Lumen Desk only talks to the user's own PC and the services they connect (Spotify, weather).
- **No personal data in the repo** – no IP addresses, computer names or tokens in code, tests or screenshots.
- Be kind. Everyone was new once.
