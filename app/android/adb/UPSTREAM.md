# Embedded ADB source provenance

This library carries a small ADB client subset adapted from [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) commit [`b844bc491f1790c72328e1a8e5b2349f8978f0ea`](https://github.com/RikkaApps/Shizuku/tree/b844bc491f1790c72328e1a8e5b2349f8978f0ea), licensed under Apache License 2.0.

Imported files:

- `AdbPairingClient.kt`, `AdbClient.kt`, `AdbKey.kt`, `AdbMessage.kt`, `AdbProtocol.kt`, `AdbException.kt`
- `adb_pairing.cpp`, `logging.h`

Local changes:

- Isolate the source in a small Android library without Shizuku UI, service, mDNS, branding, package ID or permissions.
- Replace Shizuku utility helpers and hidden Conscrypt access with standard APIs and public Conscrypt.
- Rename the JNI library and keep only the pairing native target.
- Add bounded connect/read/TLS deadlines and idempotent socket cancellation. Only the owning worker destroys the native pairing context.
- Remove key, peer and packet payload logging; reject oversized ADB packets and limit command output.
- Store the software RSA identity encrypted by an Android Keystore AES-GCM key, and fail explicitly if existing identity data cannot be decrypted or saved.
- Support sequential fixed-command streams with monotonically increasing local IDs.
- Add JVM socket lifecycle tests and an Android instrumentation test against the system wireless-debugging pairing service.

`disconnect` closes PocketRun's current connection and in-flight operation. It does not revoke a pairing already accepted by Android; revocation remains a system Settings action.

## Native and crypto dependencies

Exact versions are declared in `build.gradle`. Licenses and notices packaged with the APK are under `src/main/assets/third-party/`:

- Shizuku source: Apache-2.0.
- BoringSSL prefab: BoringSSL's bundled OpenSSL, SSLeay, ISC and fiat licenses.
- Conscrypt: Apache-2.0 and bundled notices.
- LLVM libc++ prefab: Apache-2.0 with LLVM exceptions/notices.
- Bouncy Castle: its MIT-style license.

The pairing TLS context accepts the ephemeral server certificate, like the selected upstream implementation. The six-digit Android pairing code authenticates the pairing exchange; PocketRun does not claim persistent server-certificate pinning.
