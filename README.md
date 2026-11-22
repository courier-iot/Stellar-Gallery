# Stellar Gallery

A private, offline photo & video gallery for Android with a Stellar/cyberpunk look.
No accounts, no tracking, no Google services.

## New in v2.1.0
- Photo sorter: on-device AI that suggests smart albums (approve, rename, teach it). Toggle in Settings.
- Optional "Search & sort" download (~45 MB) for searching by what's in a photo.
- The build downloads and test-runs the models automatically (scripts/prepare_sorter.py) and attaches
  the search model to each release. Nothing to upload by hand.

## New in v2.0.0
- Open photos & videos from other apps, search with type/date filters, drag-to-select, pinch to resize the grid
- Slideshow, set as wallpaper, accent colors + pure black theme, "On this day" memories
- Duplicate finder, storage breakdown, photo map (OpenStreetMap), video trim & frame grab
- Editor: draw, text, stickers, pixelate (blur) boxes, vignette
- Strips location & camera info when sharing (toggle in Settings)
- Vault: decoy PIN, hidden vault icon, unlock log
- Home screen photo-frame widget
- Wi-Fi share: QR pairing, send whole albums, resume interrupted transfers (both phones need v2.0.0)
- Optional WebDAV backup to your own PC / NAS (off by default)

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
