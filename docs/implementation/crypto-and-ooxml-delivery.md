# Crypto and restricted OOXML delivery

2026-10-18. Both modules contain working implementations and real fixture tests; no payment-template adapters were added.

## Files

Crypto:

- `platform/android/src/main/kotlin/com/dawnyew/platform/crypto/BackupCrypto.kt`: stream container, strict profile/framing, password encoding and cleanup, authenticated private staging.
- `platform/android/src/main/cpp/dawnyew_crypto.c`, `CMakeLists.txt`: minimal real libsodium JNI bridge; opaque native-state registry and one-process KDF gate.
- `platform/android/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libdawnyew_crypto.so`: NDK r28c/API30, all ELF LOAD segments 16 KiB aligned.
- `platform/android/crypto-consumer-rules.pro`, `src/main/assets/licenses/libsodium-LICENSE`: JNI R8 names and upstream license.
- `platform/android/src/test/kotlin/com/dawnyew/platform/crypto/BackupCryptoTest.kt`, `src/test/native/generate_vectors.c`, `src/test/resources/crypto-v1/*`: 12 tests and six native golden fixtures.
- `scripts/build-crypto.sh`, `scripts/build-crypto-vectors.sh`: signature/hash-verified builds and independent byte-for-byte fixture regeneration.
- [Native build and provenance details](crypto-native-build.md).

OOXML:

- `platform/android/src/main/kotlin/com/dawnyew/platform/tabular/OoxmlCodec.kt`.
- `platform/android/src/test/kotlin/com/dawnyew/platform/tabular/OoxmlCodecTest.kt`: 12 tests against real ZIP packages and a real XML parser.

## OOXML API and boundaries

`OoxmlCodec(privateDirectory, checkCancelled, ensureSpace)` provides:

- `read(input): TableWorkbook`, a convenience collector for small workbooks.
- `readRows(input, onRow): WorkbookMetadata`, the bounded-row ingestion path. Callback arguments are `SheetInfo`, the original one-based worksheet row coordinate, and cells. Callbacks must write only isolated preview/staging; later semantic errors can reject the file.
- `write(workbook, output)`, which generates a real OPC/ZIP `.xlsx`, revalidates it against import limits, and only then copies the complete file to the caller's output.

`TableWorkbook` contains `TableSheet(name, rows)`, the workbook's `date1904` flag, explicit `numberFormats`, and `styleNumberFormatIds`. `Cell` retains `kind`, raw `value`, and optional `styleIndex`. Kinds are `TEXT`, `NUMBER`, `BOOLEAN`, `ISO_DATE`, and `BLANK`. Numeric scientific notation is preserved without rounding or guessing money; numeric dates remain numeric with their style facts. Formatting, currencies, time zones, payment-provider columns, statuses, and business meaning are not inferred. Text beginning with `= + - @` is written as explicit inline text, never as a formula.

Accepted scope is UTF-8, transitional SpreadsheetML data workbooks with shared or inline strings, raw numeric/Boolean/ISO-date cells, and number-format metadata. The codec intentionally rejects formulas/cached formula cells, external relations, macro/embedded binary parts, merged cells, drawings, controls, table objects, named formulas, ZIP64/multipart archives, and unsupported OOXML cell types. It does not claim arbitrary Excel compatibility or reproduce cosmetic font/layout styling on a read/write cycle.

ZIP validation uses actual bounded decompression and CRC verification, then cross-checks local entries against the central directory and the EOCD boundary. Members are never extracted to paths from the file. Names, normalized collisions, traversal, external relationship targets, duplicate cells/rows, and sparse oversized coordinates are rejected. Every XML part is scanned, including unreferenced parts; DTD/entity declarations are rejected with `nextToken`, DTD processing is disabled, and external/custom entities are forbidden.

Limits: 20 MiB original ZIP, 256 members, 32 MiB each XML part, 100 MiB total decompression, 1000:1 entry ratio, XML depth 32, 128 attributes, 32 Ki Unicode scalars/64 KiB UTF-8 per text, 128 columns, and 10,000 parsed rows across sheets. The row budget counts raw rows including headings; a future template needing 10,000 business rows plus headers must explicitly reconcile that budget. Decoded cell text also has a 100 MiB cumulative budget so repeated shared-string references cannot amplify a small package into unbounded materialized output. Export uses the same limits. Shared strings are stored in a random private temporary file with a bounded offset index, rather than retaining all texts in memory.

Inputs, shared-string cache, and temporary exports stay in caller-supplied private no-backup storage and are deleted on normal completion/failure/cancellation. The default reserve is 128 MiB; callers can provide their coordinated dynamic space budget. Process-death cleanup and provider no-progress timeouts are caller responsibilities. The caller owns streams. A failure during final output copying can leave a partial external/candidate file and must be handled by the file-port coordinator.

## Evidence and remaining checks

- Official libsodium host suite: 101/101 passed.
- Crypto host JNI/JUnit: 12/12 passed, including 21 MiB + 17 byte streamed round trip and malicious framing.
- Golden-vector regeneration: six fixtures byte-identical.
- Android JNI: all four ABIs compiled; each LOAD alignment verified as `0x4000`.
- OOXML host JUnit with kXML2 2.3.0: 12/12 passed. Android uses the platform XmlPullParser; kXML2 is test-only.
- Integrated Gradle command: `scripts/project-gradle.sh :platform:android:testDebugUnitTest`. The latest integration result belongs to the main workstream; earlier attempts encountered unrelated work-in-progress OCR/storage/import compilation issues and do not establish a passing whole-project suite.
- Synthetic interoperability file: `.local/crypto-work/synthetic-codec-check.xlsx`, generated entirely from public test values. Microsoft Excel is installed, but the computer-use tool reported the Mac locked and unable to unlock. Therefore Excel UI opening/content acceptance was **not verified**; no existing user workbook was inspected. LibreOffice compatibility was not tested.
- Device KDF performance, Android runtime/SAF behavior, lifecycle cancellation, and app-level restore acceptance are outside these host tests. JNI ELF alignment does not alone prove APK ZIP alignment or physical-device behavior.

## Storage publication, authorization and streaming integration

The implementation was extended after the independent review found publication and lifecycle windows. Additional files:

- `platform/android/src/main/kotlin/com/dawnyew/platform/storage/GenerationRepository.kt`, `GenerationFileOps.kt`, `GenerationUpgradeOperations.kt`: injectable durable file operations, restore comparison tokens, fail-closed generation publication, recoverable clearing, explicit protection-copy recovery, pending-operation receipts, direct activation of validated candidates, and isolated schema upgrades.
- `platform/android/src/main/kotlin/com/dawnyew/platform/security/SessionCoordinator.kt`: process-only, expiring picker/authentication leases; protected picker returns require fresh device authentication. Screen-off and new-entry revocation invalidate callbacks. No lease or password is saved to a Bundle.
- `androidApp/src/main/kotlin/com/dawnyew/app/{MainActivity,DawnYewApplication}.kt`: lifetime/cancellation/cleanup wiring, secure windows, protection-preview UI, streaming backup routes, and candidate ownership.
- `platform/android/src/main/kotlin/com/dawnyew/platform/files/{JsonStreamCodec,CanonicalRecordStore}.kt`: strict token parsing and bounded private disk sorting. Existing small-document APIs remain for compatibility; Android production export/restore uses the streaming APIs.
- `platform/android/src/test/kotlin/com/dawnyew/platform/storage/GenerationRepositoryTest.kt`, `security/SessionCoordinatorTest.kt`, `files/JsonStreamingBackupTest.kt`, `files/CanonicalRecordStoreTest.kt`: real temporary-file fault injection, session transitions, large backup fixtures, and partial-open/close resource failures.

A restore binds `DatasetToken(epoch, generation, datasetId, contentVersion)` before provider access. JSON records are validated and written to private sorted runs, at most 128 records or 4 MiB per buffered run. Online 16-way compaction limits open readers and run metadata. Arbitrary input record/entity order still computes the prescribed canonical digest. The complete checksum and counts must match before bounded typed chunks are inserted into a new Room database. SQL checks relationships and commits that candidate as one isolated transaction. The preview carries only summary counts and an opaque candidate. Publication compares the baseline under the same mutex as the pointer rename; the application's ordinary state is loaded only after activation. No full `JsonObject` backup tree or `BackupProtocol.create()` call remains on the Android production route.

Export holds one Room read snapshot across metadata/counts and sorted chunk visitation; it writes records and the canonical digest incrementally. JSON backup input has no 20 MiB or 10,000-record limit. Token bounds remain: depth 32, property name 128, object members 128, 32 Ki Unicode scalars / 64 KiB UTF-8 per string. Unknown record properties are rejected before consuming their values. Both parser and disk-merge/chunk loops check cancellation. Private scratch and unactivated candidates are deleted on cancellation/failure; free-space checks reserve 128 MiB. Uninterruptible provider I/O still depends on the provider returning control; the implementation does not claim a universal provider timeout.

Rename is the publication point. A later directory sync/log/cleanup failure closes the repository rather than leaving an old writable delegate or deleting the active candidate. Startup follows the active pointer, completes a committed clearing journal, and never silently rolls back to protection. A damaged active database can instead use explicit authenticated protection preview and new-candidate reconstruction. Existing zero-byte or metadata-free databases cannot be silently initialized as empty books.

Immediately before publication, the candidate is reopened under the publication mutex for a bounded integrity, payload/column, relationship, summary and content-digest check. Failure at this stage leaves the current pointer, epoch and writable delegate intact. This closes the interval between preview and confirmation: an altered or truncated candidate cannot acquire the active pointer merely because its file still exists. Disk-merge readers are registered as each opens, so failure on a later open closes earlier readers; close failures are suppressed onto the original error without skipping remaining closes.

Before an ordinary command, only its opaque operation UUID is durably written to `pending-operation`. Success keeps this marker until the UI callback acknowledges it. Startup resolves receipts, with entry creation-operation IDs as a fallback. A pending unacknowledged result prevents a different operation from replacing its marker. Restore clears an old-generation marker only after publication; clear-all keeps its own new receipt until acknowledgement.

Startup probes `user_version` through raw bundled SQLite before Room can open an existing active generation. Missing, zero or future schemas are rejected. For supported older schemas, the source passes integrity/foreign-key validation and a completed `wal_checkpoint(TRUNCATE)`; all source connections close before the fsynced main database is copied into a new private generation. Room migrates only the candidate. Full domain and row/payload consistency validation, a second raw integrity/foreign-key/checkpoint pass and reopening all precede pointer publication. `UPGRADE` journals retain the original operation receipts and pending marker. The old generation becomes the protection copy in its old schema. Reading that protection copy also uses a disposable migrated copy, preserving the protected original. `shared/storage/.../SchemaUpgrade.kt` provides read-only probing and read-write-without-create checkpointing, and the Room annotation shares its `CURRENT_DATABASE_VERSION` constant.

Final platform freeze validation in `.local/crypto-work/logs/platform-final-review-green.log`: 80 platform JVM tests passed (zero failures, errors or skips), and Android application Kotlin compilation passed. This includes a 12,001-entry JSON fixture larger than 20 MiB, byte-identical streaming round trip, arbitrary-order canonical hashing, invalid metadata/duplicates, cancellation, low-space failure, publication/acknowledgement crash windows, candidate corruption after preview and partial reader-open cleanup. The corruption regression first failed against the prior implementation (`candidate-revalidation-red.log`) before the publication fix. Shared storage's seven separate candidate tests cover 15,001 records larger than 20 MiB through real SQLite, bounded chunks, deferred relationships, rollback, payload/column drift, changed valid contents, invalid revisions and truncation. Parent-agent device results are tracked by the main workstream; do not infer fresh APK/device acceptance from these JVM results.

The subsequent isolated-upgrade freeze is `.local/crypto-work/logs/generation-upgrade-integration.log`: 84 platform tests, six real SQLite migration tests, and Android application Kotlin compilation all passed. The migration suite checks source schema/receipt retention on successful copied migration, byte-identical original preservation after candidate migration failure, missing/future-file safety and refusal to copy while WAL checkpointing is blocked. Generation's injected-file tests cover copy/migration/pre-rename failures, post-rename/fsync/commit failures, pending-receipt recovery, future schemas and old protection-copy preview. Final production Android schema 2 to 3 upgrade and APK acceptance belong to the main workstream's device run.

An earlier frozen device checkpoint is retained at `.local/releases/security-checkpoint-20261018/`; it predates the streaming/pending-operation work and is not the final artifact. That checkpoint contains schema 2 and `createIfEmpty=false`. The shared storage workstream has subsequently added explicit schema 3 migration for SQL query columns; final APK acceptance must use the subsequently rebuilt artifact.
