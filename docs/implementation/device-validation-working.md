# Android device validation log (working)

This log records actual actions against synthetic data only. It is not a release approval. Final build hashes and the completed matrix will be recorded after the last regression run.

Device: PKM110 / Android API 36 / arm64-v8a. Serial is kept in local test commands, not required for app functionality. Initial package query showed DawnYew was not installed. Debug package installation was completed through the OEM install confirmation; app first launch succeeded.

## First executable build

- Saved an expense of CNY 32.50 with note `Test-lunch`; details showed one record and net expense 32.50.
- Attempted a CNY 50.00 refund; UI rejected it with the cumulative-refund limit message, retaining the form.
- Saved a CNY 5.00 refund; overview showed original expense 32.50, refunds 5.00, net expense 27.50.
- Attempted to archive the only ledger; UI rejected it and retained the active ledger.
- Created `Travel-Test`, archived and unarchived it, renamed it to `Travel-Verified`.
- Created an expense category named `Device-Test` in the daily ledger.
- Moved the expense to `Travel-Verified`; detail showed the new ledger and the linked 5.00 refund.
- Deleted the expense; overview returned to zero. Recycle bin showed a two-record deletion group. Restoring the group emptied the recycle bin and restored the expense/refund.
- Selected `Travel-Verified` in statistics and set a CNY 100.00 monthly budget; UI showed CNY 72.50 remaining.
- Opened the equivalent trend data table; zero days showed separate income, expense, refund and net expense values.

Local screenshots: `artifacts/device/01-first-launch.png`, `02-refund-verified.png`, `03-recycle-group.png`, `04-budget.png`. These files are ignored by Git. Subsequent builds use secure-window protection and can suppress screenshots; accessibility hierarchy and instrumented checks remain available.

## Pending regression after reviewed fixes

- Schema 1 → 2 update preserves these records and budget.
- Editor identity / rotation / process death; two-series charts, drilldown scope and budget month.
- JSON / encrypted backup export, full restore preview, protection recovery and corruption rejection.
- Native OCR synthetic image, native libsodium and real generation switch through instrumentation.
- Search, category state/order, income, editing, permanent purge, large type and landscape.
- Final Release build, lint, dependency/manifest/native checks and capacity benchmarks.

Biometric enrollment, personal device credential entry, multi-vendor OCR corpus and human usability measurements may require the manual checklist; do not infer success from a UI button or a compiled library.

## Security checkpoint (2026-10-18)

- Covered installation of the schema-2 build preserved the two entries and net expense 27.50.
- Android instrumentation ran against the actual arm64 device: **4 tests passed in 2.431 seconds**. Manifest denied network permissions and automatic backup; libsodium JNI roundtrip and tail-tamper rejection passed; generation replace/reopen/idempotent save passed; bundled OCR recognized synthetic `12.34` using the actual native pixel pipeline. Output: `artifacts/device/checkpoint-instrumentation.txt`.
- JSON was exported through the OEM system file picker. App displayed successful read-back integrity verification. The synthetic exported JSON was pulled for inspection (2 ledgers, 2 entries, retained budget).
- Selecting that JSON showed a 2-ledger/2-active-entry preview. Confirming replacement retained net expense 27.50.
- Password-encrypted backup was exported and read back successfully. A deliberately incorrect test password produced “口令不正确或备份已损坏”; the correct test password opened the same restore preview and replacement succeeded.
- Internal protection-copy preview showed 2 ledgers/2 active entries. Confirming it restored the data, and net expense 27.50 remained visible.
- The UI helper initially used Ctrl+A, which this OEM file picker ignored; the resulting test filenames contain a harmless inserted substring. The helper was corrected to clear the observed field from its end. This was test-driver behavior, not an app failure. Only the files created during this session will be removed during cleanup.

These checks precede the final streaming/pending-operation build and will be rerun for changed paths.

- After both JSON and encrypted restore, selecting `Travel-Verified` still showed budget 100.00, used 27.50, remaining 72.50 (schema migration and restore retained the budget).
- Tapping the disabled app-lock switch invoked the real system strong-fingerprint prompt, including its system-password fallback. Cancelling returned to DawnYew with the switch still off and “认证未完成，请重试”. No personal credential was entered and no biometric settings were changed; successful human authentication remains manual.

- A generated, clearly labelled synthetic WeChat payment image was selected through the real local picker. Native OCR produced amount 12.34, 2026-10-18 12:00 and merchant 测试咖啡店 in an unconfirmed draft; nothing was automatically saved. Portrait → landscape → portrait retained amount, chosen ledger, date/time and unconfirmed status.
- System Back displayed the discard confirmation; cancelling kept the draft. At font scale 2.0, fields and the save button remained reachable by scrolling. The device's original font scale 1.0 and rotation lock 0 were restored and read back. Confirmed discard returned to net expense 27.50, proving the draft did not enter statistics.
- This synthetic end-to-end check does not replace the real-payment corpus or TalkBack visual/usability acceptance.

## Final candidate upgrade and device instrumentation

- Final integrated build passed all **207** host tests (30 core, 16 protocol, 39 storage, 38 presentation, 84 platform), Debug/test APK, optimized Release and lint. Lint: 0 errors, 7 warnings. Log: `.local/final-device-release-build.log` (54 seconds).
- Real covered installation migrated schema 2 → 3 by changing the active generation. The previous active generation remains the protection copy at schema 2; both contain the same two synthetic entries. Evidence: `artifacts/device/pre-candidate-upgrade.json` and `post-candidate-upgrade.json`.
- Final Android instrumentation: **6 tests passed in 22.393 seconds**. Added streamed restore candidate isolation/stale-preview rejection and a real-device 10,000-entry database measurement. Output: `artifacts/device/final-instrumentation.txt`.
- On this PKM110, 20 runs gave page-of-50 query P95 **35 ms** and monthly statistics P95 **86 ms**. These debug/instrumented measurements are not the specified 100-run reference-device Release acceptance.
- Final optimized arm64 package was signed with the project's local development key, verified and installed over Debug. Release package has no INTERNET/ACCESS_NETWORK_STATE permission; APK 16KB ZIP alignment passed.
- After installation, selecting Travel-Verified showed the preserved net expense **27.50**.

## Final closure

- Final scope: 211 host tests and 6 real-device tests (20.656s), with latest query measurements page50 P95 27ms / statistics P95 86ms.
- Real-device deterministic business rejection exposed an inappropriate pending dialog. Fixed with rollback/receipt-aware marker cleanup, 4 new test groups, independent review, and final Release device regression.
- Real optimized Release OCR exposed missing ORT consumer rules (JNI class lookup abort). Added complete binding keep rules and actual DEX class/member checks; old APK fails, new APK passes. Final signed Release successfully recognized and explicitly saved the synthetic image twice with duplicate acknowledgement.
- Final Release JSON restore from an empty dataset showed 2 ledgers/4 entries, then net expense 27.50 and 4 entries after confirmation.
- Final app data and protection were cleared through app UI; only our synthetic external files were removed. Instrumentation package removed. Main app remains installed with default empty ledger. Cold start and device settings verified.
- Authoritative delivery details, hash, evidence and residual manual gates: [final acceptance report](2026-10-18-device-acceptance.md).
