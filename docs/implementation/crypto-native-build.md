# DWYBAK v1 crypto implementation and build evidence

Date: 2026-10-18. Source and wire profile remain the approved libsodium **1.0.22**, Argon2id13 (opslimit 3, 67,108,864 bytes, 32-byte key), and secretstream XChaCha20-Poly1305. This file does not change the authoritative [80-byte container protocol](../research/2026-10-18-local-data-security-design.md#62-精确帧格式).

## Trusted source and reproducible build

- Point release: [official 1.0.22 release](https://github.com/jedisct1/libsodium/releases/tag/1.0.22-RELEASE), published 2026-04-09.
- Source: `https://download.libsodium.org/libsodium/releases/libsodium-1.0.22.tar.gz`.
- Locked SHA-256: `adbdd8f16149e81ac6078a03aca6fc03b592b89ef7b5ed83841c086191be3349`.
- The adjacent `.minisig` is verified using the Ed25519 public key from [official installation instructions](https://doc.libsodium.org/installation): `RWQf6LRCGA9i53mlYecO4IzT51TGPpvWucNSCh1CBM0QTaLn73Y7GFO3`. Both source signature and comment signature passed. Trusted comment: `timestamp:1775774745 file:libsodium-1.0.22.tar.gz hashed`.
- Verification tool: upstream minisign 0.12 macOS binary; download archive SHA-256 `89000b19535765f9cffc65a65d64a820f433ef6db8020667f7570e06bf6aac63`.
- Android: project-local NDK r28c, revision `28.2.13676358`, target API 30. The unmodified official `dist-build/android-aar.sh` creates all 16 ABI/build variants. Explicit NDK `llvm-ar`, `llvm-ranlib`, `llvm-nm`, and `llvm-strip` prevent accidentally selecting macOS archive tools.
- `scripts/build-crypto.sh host` builds the host JNI dylib. `scripts/build-crypto.sh android` verifies the AAR's actual libraries and headers, links each JNI `.so`, and verifies all ELF LOAD segments have at least 16 KiB alignment. `all` does both. The normal Android Gradle build consumes these libraries via `jniLibs`, and does not build native dependencies on every Java unit test.
- No global SDK, environment configuration, or device is changed. All downloads/build products are under `.local/crypto-work`; Android JNI outputs are under `platform/android/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}`. The libsodium license is bundled in `assets/licenses`.

The official AAR script does **not** propagate every child build failure. An initial NDK r21/API30 probe lacked `android30-clang`, nevertheless printed “Congrats” and emitted an AAR containing only metadata. This artifact is not used. A subsequent r21 probe also exposed host `ar/ranlib` selection. Acceptance uses real AAR entries, native linking, ELF inspection, and test results rather than the script's exit code or success text.

The reusable arm64 static library is `.local/crypto-work/android-prefab/prefab/modules/sodium-static/libs/android.arm64-v8a/libsodium-static.a`; matching headers are in that directory's `include/`. The original official AAR is `.local/crypto-work/android-src/libsodium-1.0.22.0.aar`.

## Runtime contract

`BackupCrypto(privateStagingDirectory, checkCancelled, ensureSpace)` exposes `encrypt(InputStream, OutputStream, CharArray)` and `decrypt(...)`. The caller runs them on an I/O worker and owns both streams. The password array is consumed and zeroed on every exit; native copies, derived keys, and native stream states are also cleared. No claim is made that all JVM/input-method historical copies can be erased.

The input password is encoded directly from validated Unicode scalars without creating an immutable password String. It is never trimmed or normalized. Header white-list checks run before KDF. Native KDF parameters cannot be selected by a file. A native mutex permits only one KDF at a time and rejects concurrent attempts instead of queueing them. JNI state identifiers are registry IDs, not exposed raw pointers; stale and unknown IDs are rejected safely.

Decryption writes only to a random private staging file until every frame authenticates, canonical MESSAGE/FINAL lengths and tags pass, and FINAL is immediately followed by EOF. Only then is plaintext copied to the supplied output. The output must still be an isolated JSON parser/candidate; business JSON/manifest validation and generation commit remain outside this class. Cancellation or output failure during that final copy can leave a partial candidate, which the caller must discard. The native KDF cannot be interrupted mid-call; cancellation is checked immediately afterward and between I/O operations. Staging is deleted on all exits, with deletion failures surfaced. Startup cleanup must handle files left by process death.

`ensureSpace(nextBytes)` defaults to a 128 MiB reserve on the private staging volume. The maintenance coordinator may supply its dynamic whole-operation budget. This API deliberately uses an extra authenticated plaintext staging file, whose disk cost must be included with input/candidate/protection copies. Stream/provider no-progress timeouts remain the file-port caller's responsibility.

## Verification

- Verified host libsodium: `make -j8 check`: **101 passed**, 0 failed/skipped/errors.
- Host JNI compiled with `-Wall -Wextra -Werror`; test JNI uses the same C wrapper source and verified libsodium source as Android.
- `BackupCryptoTest`: **12 passed** in the standalone host test run: six independent fixed native vectors, random salt/header round trips, Unicode rules and wiping, unsupported headers, hostile frame sizes, wrong password/tampering, truncation/FINAL/EOF, frame reorder/repeat/delete, authenticated noncanonical frame shapes, cancellation cleanup, stale native handles, and a **21 MiB + 17 byte** streamed round trip with bounded read/write buffers.
- Run integrated tests using `scripts/project-gradle.sh :platform:android:testDebugUnitTest --tests 'com.dawnyew.platform.crypto.*'` after `scripts/build-crypto.sh host`.
- Golden fixtures are in `platform/android/src/test/resources/crypto-v1/manifest.json`. The test-only C generator calls official secretstream APIs with a deterministic RNG; it is never linked into production. `scripts/build-crypto-vectors.sh` independently regenerates and byte-compares all six files.

These are build and host-runtime results. They do not prove minimum-device Argon2 latency/memory behavior, Android lifecycle cancellation, Android JNI loading, SAF-provider behavior, or physical-device restore. Final APK ZIP alignment is separately checked by the application build.
