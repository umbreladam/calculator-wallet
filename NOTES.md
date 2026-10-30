# Calculator (watch-only BTC wallet disguised as a calculator) — progress notes

## Layout
- `core/` pure Kotlin/JVM module (unit-tested on the box): calculator engine, PBKDF2 code lock, UR (bc-ur port: bytewords,
  CBOR, xoshiro256**, fountain encoder/decoder), BBQr, import parser (xpub/SLIP-132, descriptors, Sparrow/Coldcard/BSMS/
  Electrum/Core JSON, UR crypto-output/account/hdkey), secret guard, BDK wallet engine (sync/PSBT/finalize/broadcast).
  Compiles against `bdk-jvm` (compileOnly); the app ships `bdk-android` (same API).
- `app/` Android Compose app.

## Log
- 2026-10-30: core module written, all unit tests green (UR vectors from bc-ur test.cpp, BBQr, BIP84 public vector,
  generated testnet keys for PSBT create/sign/finalize incl. 2-of-3 BIP48 multisig).
- App built (Compose, CameraX+ZXing, bdk-android 3.1.1). Release APK signed with its own keystore (gitignored).
- Icon redrawn as a Pixel-Calculator-style 2x2 operator grid on white.
- Server: no default. You choose your own server or a public one (after confirming a warning); a banner shows while a public server is active.
- Emulator screenshots: not produced. The AVD (API 34 x86_64, swiftshader) never got to adbd within about 15 min on this box.
- Not tested on a device yet: camera scanning, live Electrum sync/broadcast, Keystore encryption.
- v1.1 (versionCode 2): new Home with a live Kraken BTC/USD candle chart (Coinbase/Bitstamp fallback, toggle in Settings),
  Wallets list, and a Wallet screen with big balance (taps through to history), Send/Receive and wallet settings. `MarketTest` covers the parsing, 8h aggregation and live updates.
- Emulator screenshots, second try: not produced. The box user has no /dev/kvm permission, so the emulator exits straight away.
- v1.1.1 (versionCode 3): Home % is now a true rolling 24h change (vs the open of the hourly candle 24h ago, refreshed with the 60 s reload; same for Coinbase/Bitstamp fallbacks), not Kraken's 00:00 UTC 'o'. High/low/volume are rolling 24h (ticker h[1]/l[1]/v[1], or hourly candles on fallback).
