<p align="center"><img src="docs/icon.svg" width="128" alt="Pika Checker icon"></p>

# Pika Checker

Android app that reads Pokémon TCG cards through the camera in real time and shows each card's
**PSA 10 price from [PriceCharting](https://www.pricecharting.com/es/category/pokemon-cards), in euros**,
floating over the card like augmented reality. Tap **Check in Pricecharting here** to open the card's page.

## How it works

1. **Camera + OCR** – CameraX streams frames to on-device ML Kit text recognition. The app finds the
   collector number (`025/165`, `TG12/TG30`, `SWSH050`…) and the card name (largest text near the top).
2. **Lookup** – it searches PriceCharting for `<name> <number>` and keeps products whose title has
   `#<number>`. When the same card exists in several sets, tap *Not this set?* to cycle matches.
3. **Price** – the PSA 10 (and ungraded) price is read from the product page and converted from USD to EUR
   using the daily ECB rate ([frankfurter.dev](https://frankfurter.dev)).
4. **AR overlay** – the price bubble follows the card on screen. Touch it to freeze it in place.

PriceCharting is protected by Cloudflare, so pages load in a hidden WebView. If Cloudflare asks for a
human check, the app shows it — complete it once and scanning continues.

## Install

Download the latest `PikaChecker-vX.Y.Z.apk` from [Releases](../../releases/latest) and open it on your
phone (allow *Install unknown apps* when asked). Android 8.0+.

## Auto-update

On launch the app checks the latest GitHub release. If a newer version exists it offers to download the
APK and hands it to the Android installer. All releases are signed with the same key so updates install
over the existing app.

## Publishing a release

Either push a tag:

```bash
git tag v1.1.0 && git push origin v1.1.0
```

or run the **Release** workflow from the Actions tab with a version number. The workflow runs the tests,
builds a signed APK and publishes it as a GitHub release.

Required repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Build locally

```bash
./gradlew assembleDebug
```

For signed release builds put a `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword)
in the project root. It is git-ignored.

*Not affiliated with PriceCharting, Nintendo, The Pokémon Company or PSA.*
