# Device Security Overview

Mobile devices are inherently prone to physical loss, unauthorized extraction, and malware threats. The Aduki Android SDK adheres to a strict **zero-trust, hardware-anchored device security policy**:

1. **Hardware Root of Trust**: Cryptographic master keys are generated inside the device's hardware security module (**StrongBox Keymaster** or **Trusted Execution Environment - TEE**). Keys never leave this hardware boundary.
2. **Authenticated Envelope Encryption**: All on-device databases and sensitive tokens are encrypted using **AES-256-GCM** with 128-bit authentication tags and cryptographically random 96-bit Initialization Vectors (IV).
3. **In-Memory Zeroization**: Plaintext passwords, tokens, and encryption keys are scrubbed from RAM immediately after use (`Arrays.fill(0)`).
4. **Restricted Network Security**: SPKI certificate pinning and strict TLS 1.3 protocol specifications prevent man-in-the-middle (MITM) attacks.
