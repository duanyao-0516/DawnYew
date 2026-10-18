# DawnYew UI State Repair Implementation Plan

> **For agentic workers:** Execute inline in the existing shared workspace; root owns integration and assigns an independent review after this implementation.

**Goal:** Preserve complete editor identity only in process memory, keep statistics drill-down amounts consistent, and expose explicit error/discard states.

**Architecture:** Application-owned AppController holds EditorDraft and navigation StateFlows. Shared pure helpers construct editor commands and derive a narrowed QueryScope; Compose only renders and edits these values. Activity owns system back dispatch and security windows.

**Tech Stack:** Kotlin Multiplatform, StateFlow, Compose Material3, kotlin.test.

- [x] Add JVM tests for new OCR operation IDs and confirmation reset, edit/refund identity, retained editor input, category/period/ledger drill intersection, bounded percentage formatting and error states. Run tests before implementation and retain failing evidence.
- [x] Implement `EditorDraft.kt` and `StatisticsNavigation.kt`; extend AppController while preserving existing public methods; add restoreProtectionBackup platform action.
- [x] Update DawnYewApp/EntryForm to render controller-owned drafts, confirm unsaved discard, register system back, keep per-page scopes and restore drill origins, and show calculation failures without zero totals.
- [x] Correct budget target month, future historical rows, income-only trends, percentage labels, and small-screen wrapping; keep current visual style with teal secondary containers.
- [x] Run `:shared:presentation:jvmTest :shared:ui:compileKotlinJvm`, record limits, and hand off Activity integration plus independent review. No device operations in this agent.


## 交叉复核后的补充（2026-10-18）

- [x] 保存并继续保留有效类型/分类/账本，清金额/备注且创建新操作身份。
- [x] UI 改用 `EditorChange` 字段事件；异步疑似重复结果不受旧渲染快照覆盖，确定性竞争测试回归。
- [x] 零原始支出移除支出下钻入口；退款入口保留，drillScope 对类型/分类/账本只缩小范围。
- [x] pendingOperation 界面恢复和明确确认，正常提交回调后 ack；ack 期间保持 busy，已提交与界面确认失败区分。
- [x] 首次数据加载恢复有效账本/月份偏好；显式选择串行合并写入，等待当前账单及待确认结果；全部账本不持久化非法身份。
- [x] `:shared:presentation:jvmTest` 38 项通过，`:shared:ui:compileKotlinJvm` 通过（ui-default-order-green.log）；含恢复后乱序数组的默认 sortOrder/id 选择回归。
- [ ] 主线程最终 Android APK 构建、设备交互与大字体验收。
