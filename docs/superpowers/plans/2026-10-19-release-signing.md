# Local Release Signing

**Goal:** `./dev.sh release` produces a signed, versioned APK for manual distribution; keys and passwords never enter Git.

**Design:** Preserve the optimized unsigned Gradle intermediate and existing OCR JNI/Lint gates. After those gates pass, align and sign that exact APK with Android SDK tools. Read its filename and version from AGP output metadata and its ABIs from ZIP entries. Publish the verified APK and SHA-256 checksum under ignored `artifacts/releases/`.

**Key lifecycle:** An explicit `./dev.sh init-release-key` creates a separate PKCS12 key and random password under `.local/signing/release/` (directory 0700, files 0600). Repeated initialization preserves the identity. Ordinary releases require existing complete signing material; they never generate replacement keys or fall back to Debug signing. Passwords reach tools through files, not command arguments or logs. The entire directory must be backed up privately.

**Scope:** Preserve existing worktree edits and Debug behavior. The user additionally requested committing and pushing the source changes to the current GitHub branch; APK/secret publication and device installation remain out of scope. The formal signature differs from existing development APKs; installing over them is not supported. Move fresh OCR reports into ignored build output so builds do not overwrite historical evidence.

## Implementation and verification

- [x] Add focused behavior tests for key reuse, incomplete key rejection, restrictive permissions, metadata-based naming, and invalid metadata rejection. Run before implementation.
- [x] Share existing Java/SDK environment selection between Gradle and signing commands; add signing initialization/preflight/export helper and wire `dev.sh`.
- [x] Update README with initialization, output path, private backup and version-update instructions; verify key/password/output paths with `git check-ignore`.
- [x] Run shell syntax checks and behavior tests, initialize this machine's key, then run `./dev.sh release` end to end.
- [x] Independently verify the final APK signature, alignment, manifest version, certificate stability, checksum and Git exclusion. Run focused code review and resolve findings.

Validation on 2026-10-19: 13 behavior tests passed, including real Android signing with a temporary identity and failure recovery. The actual `./dev.sh release` completed its Gradle build, OCR JNI and Release Lint gates and exported `DawnYew-v0.1.0-arm64-v8a.apk`. Independent checks confirmed the local release certificate, signature, 16KB ZIP alignment, non-debuggable manifest, versionCode 1/versionName 0.1.0, checksum, and retained JNI ABI. Reinitialization preserved both existing signing files. Review findings around non-building Gradle options, password-file consumption and failed artifact replacement were reproduced and covered by regression tests; exports are serialized with a local file lock.

Validation does not claim device installation or functional acceptance of this newly signed artifact.
