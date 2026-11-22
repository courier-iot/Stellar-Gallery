# Stellar Gallery

A private, offline photo & video gallery for Android with a Stellar/cyberpunk look.
No accounts, no tracking, no Google services.

## Features (v1.0.0)
- Photos & videos grid with date headers, albums, fullscreen viewer, pinch/double-tap zoom, video playback
- Favorites, multi-select, copy/move to albums, share, recycle bin (30 days), sort options
- Editor: crop (free + presets), rotate, flip, 9 filters, brightness/contrast/saturation — saves as a copy
- Private vault: PIN or fingerprint, AES-256 encrypted inside the app, screenshots blocked
- Wi-Fi share: send photos phone-to-phone on the same Wi-Fi, with an accept/decline prompt
- In-app update check against this repo's GitHub releases

## Building
Push to `main` → GitHub Actions builds the APK and publishes a release tagged `vX.Y.Z`
(read from `appVersion` in `app/build.gradle.kts`). Bump `appVersion` for each new release.

### Signing (recommended, one-time)
So updates install over older versions, add these repo secrets
(Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | your `.jks` file, base64-encoded (`base64 -w0 release.jks`) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Without them the APK is debug-signed (still installs, but each build has a different key).

Requires Android 11 or newer.
