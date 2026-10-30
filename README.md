# Calculator — a watch-only Bitcoin wallet disguised as a calculator

An Android app (Kotlin + Jetpack Compose) that opens as a fully working calculator. Typing a secret code and pressing
`=` opens a **watch-only** Bitcoin wallet. It never handles seeds or private keys; spending happens by exporting a PSBT
to an air-gapped signer and scanning the signed result back.

## Features
- **Disguise**: app label "Calculator", Pixel-style adaptive calculator icon, real calculator (+ − × ÷, %, ±, decimal,
  C/AC, backspace, chaining, repeated `=`). Default code `21000000`, changeable in Settings. Only a salted
  PBKDF2-HMAC-SHA256 hash (120k iterations) is stored. Wrong codes are just maths.
- Always relocks on `onStop` (home, recents, screen off). `FLAG_SECURE` while the wallet is open so screenshots and
  the recents thumbnail stay blank. No notifications.
- **Import** (QR camera incl. animated UR / BBQr, file, or paste): xpub/ypub/zpub/tpub/upub/vpub, output descriptors
  (`wpkh`, `sh(wpkh)`, `tr`, `pkh`, `wsh/sh(wsh)(sortedmulti)` incl. BIP48, key origins, BIP389 multipath `<0;1>`,
  BSMS `/**`), Sparrow descriptor `.txt` and wallet JSON, BSMS, Coldcard generic JSON and multisig `.txt`, Electrum
  JSON, Bitcoin Core `listdescriptors`, UR `crypto-account` / `crypto-output` / `crypto-hdkey` (and the new
  `account-descriptor` / `output-descriptor` types).
- Rejects xprv/tprv, WIF, BIP39 seed phrases, SeedQR digits and secret UR types.
- Multiple wallets, rename, remove. Balance (BTC or sats; optional GBP, off by default), transaction history and
  details, receive addresses with QR and a hint to verify them on the hardware wallet.
- **Send**: BDK coin selection (BnB / largest / oldest), fee picker using server estimates or a custom rate, RBF toggle,
  send max, change to the wallet's change chain, global xpubs for multisig. Export as animated **BBQr** (Coldcard Q),
  **UR `crypto-psbt`** (SeedSigner, Jade, Keystone, Sparrow) or a `.psbt` file. Scan back a signed PSBT or tx
  (BBQr / UR / base64 / hex / file), finalize, then broadcast or export the raw tx.
- **Server**: no server ships with the app. You pick either *your own Electrum server* (host, port, TCP/SSL, test
  button) or a *public server* from a list (or a custom one). Choosing public needs you to confirm a privacy warning,
  and a banner stays on screen while it is in use. Optional SOCKS5 (Tor/Orbot).
- Dark Material 3 UI with Bitcoin-orange accents and a hide-amounts toggle.

## Screens
- **Home** (after the code): live BTC/USD price, 24h change, high/low/volume, and a Canvas candlestick chart (Line, 1m,
  15m, 8h, 1d, 1w) with volume bars, MA5/MA10, high/low markers, a dashed last-price line and a press-and-drag crosshair.
  Data is public market data from Kraken (8h is built from 4h candles), with Coinbase or Bitstamp as fallback. It
  refreshes about every 10 s while Home is open. Settings → "Live BTC/USD price chart" turns it off and hides the chart.
  The lower half has a big **Wallets** button, plus lock and settings.
- **Wallets**: imported wallets with names and balances (unless hidden), and an add button.
- **Wallet**: big balance (tap for transaction history), big Send and Receive, and a gear for wallet settings
  (rename, descriptors and QR, master fingerprint, remove).

## Privacy model
- No analytics, no Google Play services, no ML Kit (scanning uses CameraX + ZXing). Permissions: `INTERNET`
  (Electrum, and the optional market data, which never includes wallet data) and `CAMERA`.
- The wallet list (descriptors) is encrypted with AES-GCM using a non-exportable Android Keystore key. BDK's SQLite
  cache lives in `noBackupFilesDir`, and backups are disabled.
- A phone that sees your xpub can work out your whole history, so use your own node.

## Build
Requirements: JDK 17, Android SDK (platform 34, build-tools 34).
```
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :core:test            # unit tests (JVM, uses bdk-jvm)
# signing: create keystore.properties (gitignored) with storeFile, storePassword, keyAlias, keyPassword
./gradlew :app:assembleRelease  # app/build/outputs/apk/release/app-release.apk
```
`-PallowScreenshots=true` builds a variant without FLAG_SECURE. It is only for making documentation screenshots, so never install it for real use.

## Layout
- `core/` is pure Kotlin: calculator, code lock, UR (a bc-ur port with bytewords, CBOR, xoshiro256** and fountain
  codes, checked against the reference test vectors), BBQr, import parser, secret guard and the BDK wallet engine.
- `app/` is the Android UI.

Tests use only public test vectors (bc-ur, the BIP84 "abandon … about" vector) and keys generated fresh at test time.

## License
MIT
