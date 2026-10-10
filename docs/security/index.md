# Device Security Overview

The `crypto` module provides building blocks for protecting secrets on a
device. This page states what the SDK does today; each page below gives the
exact API.

| Area | What exists | Page |
| :--- | :--- | :--- |
| Key storage | `Provider`: an AES-256 master key (alias `aduki_master`) in the Android Keystore, with a software fallback off Android | [Keystore](keystore.md) |
| Encryption | `Envelope`: AES-256-GCM with a random 12-byte IV per message | [Cipher](cipher.md) |
| Memory | `wipe()`, `Guard`: overwrite secrets after use | [Sanitization](sanitizer.md) |
| Transport | Opt-in SPKI certificate pinning with your own pins (none shipped), TLS 1.3/1.2 connection spec | [TLS](tls.md) |
| Device keys | `dpop.Key`: sign-only device keys for [DPoP](../auth/dpop.md) | [DPoP](../auth/dpop.md) |

## What is not done for you

- The SDK does not encrypt the local ObjectBox database as a whole; it seals
  only the sensitive payload columns ([Vault](vault.md)) and leaves indexed
  metadata in the clear. The Android Keystore part is unverified until a device
  run.
- Access and refresh tokens are held in memory only; the SDK never writes them
  to disk.
- The SDK does not request StrongBox; the Keystore key is generated with the
  platform default (hardware-backed where the device provides it).
