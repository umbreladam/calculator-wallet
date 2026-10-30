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
