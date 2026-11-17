# Security

Lumen Deck controls a PC (keyboard, mouse, programs), so security problems matter.

**Please do not report security problems in public issues.** Use GitHub's private reporting instead:
**Security → Report a vulnerability** on this repository. You will get an answer as soon as possible.

## How Lumen Deck protects your PC

- The tablet connection is encrypted and authenticated: a fresh ECDH P-256 key exchange for every connection, a 256-bit pairing key per tablet and PC, and AES-256-GCM messages with counters (no replays).
- A new tablet must be allowed on the PC after comparing a 6-digit code shown on both screens.
- The settings window and its API only listen on `127.0.0.1` and accept requests only from the settings page itself (Host, Origin and a custom header are checked), so websites cannot change your macros.
- Lumen Deck has no accounts and no cloud; nothing leaves your network except requests to the services you connect (Spotify, Open-Meteo weather).
