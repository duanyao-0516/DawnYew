# DawnYew Android implementation plan

**Goal:** Implement the approved offline Android app, install it on the connected device, exercise verifiable features, and deliver an honest manual checklist for remaining device/OCR scenarios.

**Architecture:** KMP common domain and command engine; Room KMP transactional persistence; versioned common JSON protocol; common presentation and Compose Material 3 UI; Android-only local files, biometric session, OCR and encrypted backup. iOS contracts only. Concrete bill-template adapters remain unavailable pending samples.

**Environment:** Original project checkout, branch `p/duanyao.0516/codex/dawnyew-android-app`. All downloaded toolchains/caches live in `.local`; existing JDK/SDK are read without global configuration changes. Test device is controlled only by the parent agent.

## Tasks and acceptance

- [x] 1. Build foundation: seven Gradle modules, pinned dependencies, wrapper checksum, project-local build script. Verify Gradle configuration, JVM tests, Debug, Release and lint. AGP 9.3.3 replaces 9.3.1 to include the JDK 17 lint fix; actual compatibility must be proven by build.
- [x] 2. `shared/core`: immutable domain records, exact cents, normalized names, occurrence validation, command idempotency, expense/refund/recycle invariants, budgets and consistent statistics. Write behavioral tests before implementation; run JVM tests including refund/move/delete/restore conflicts.
- [x] 3. `shared/storage`: Room entities, foreign keys/unique keys/check triggers, serialized writer, actual bundled SQLite, receipts and content version in one transaction. Test durable reopen, failure rollback, constraints, and command replay.
- [x] 4. `shared/protocol`: strict versioned full backup, deterministic metadata-bound checksum, references/limits/duplicate validation, template capability registry and generic import contracts. Roundtrip and corruption tests; no guessed WeChat/Alipay columns.
- [x] 5. `platform/android`: verified libsodium stream encryption and decrypt, filesystem generations/protection copy, bounded local document I/O, real XLSX codec, backup exclusion. Exercise portable golden vectors, tampering and interrupted restoration cases.
- [x] 6. `shared/presentation` and `shared/ui`: details, entry/edit/refund, filters, ledger/category management, charts with data tables/drilldown, monthly budgets, recycle bin, settings/backup flows. Material 3 semantic colors, accessibility labels and scalable layouts.
- [x] 7. Android security: independent persisted app-lock state, strong biometrics/device credential, cover on background, secure window, protected external entry and fresh sensitive-action authorization. No runtime network permissions.
- [x] 8. Offline OCR: bundled verified Paddle models, no-telemetry native runtime, bounded image input, serial recognition and explicit editable confirmation. No automatic entry. Unsupported/dangerous content must not become an ordinary expense silently.
- [x] 9. Integration verification: independent specification review followed by code-quality/security review; fix significant findings and rerun affected tests. Record exact APK, dependencies, native libraries and validation limits.
- [x] 10. Build, install and operate the connected device. Verify first launch, entry/edit/refund, charts/budget, ledgers/categories, search/filter, recycle/restore, backup/restore, relaunch persistence and lock where device capability permits. Save screenshots and command evidence. Provide manual checklist for image OCR, cross-device coverage and any genuinely unavailable scenario.

## Shared contracts

Package root `com.dawnyew`. Domain owns all money/refund/ledger rules. Platform packages never enter common models. Commands carry stable operation ID and expected content version; mutation and receipt persist together. UI uses a presentation controller and repositories, never DAOs. Native engines and crypto are real implementations; unavailable capability is reported explicitly.

## Completion rule

No placeholder success, no claims based only on compilation, no silent scope reduction. The goal is complete only after app implementation, executable checks, installation/device exercise and a precise residual manual checklist. OCR quality/performance across three vendors remains a separate manual acceptance gate if the available device cannot establish it.

## Delivery evidence

Completed 2026-10-18; see [device acceptance](../../implementation/2026-10-18-device-acceptance.md) and [manual checklist](../../implementation/manual-acceptance-checklist.md). 211 host tests and 6 device tests passed. Final optimized Release OCR, backup restore, deterministic rejection and cleanup were operated on the connected device. Screenshot protection remains enabled; accessibility hierarchy evidence replaces blocked screenshots. Human authentication, full OCR corpus and multi-device production gates remain explicitly manual.
