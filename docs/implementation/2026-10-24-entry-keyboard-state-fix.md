# 紧凑记账键盘状态故障修复

## 真实失败证据

用户复现“输入 17 只剩 7”。root 的设备测试 `singlePageEntrySavesAmountCategoryAndTagsWithoutScrolling` 在修复前失败，`.local/correction-entry-diagnostic/interactions.txt` 记录：

- 点击 1 → 草稿 `1`
- 点击 2 → 草稿 `2`
- 点击 . → 草稿 `0.`
- 点击 3 → 草稿 `3`
- 点击 4 → 草稿 `4`

同一用例的分类和标签正确写入草稿。`AppController.changeEditor` 的 Amount 分支只把事件值复制到当前草稿；错误金额已产生于 UI 点击回调，不是持久化或草稿合并丢失。

若只有光标停在 0，第二次输入会得到 `21`，而不是 `2`。这里每个键都从初始空文本重新计算。

## 根因

acceptance 独立检查失败构建的 javap 输出：`::amountKey` 是捕获 `$draft` 的 `FunctionReferenceImpl`。EntryForm 随新草稿生成新的引用，但每个键的 Surface 点击 lambda 使用 `Composer.changed(onKey)` 比较。函数引用的相等性按 owner/name/signature 判断，不包含捕获的草稿字段，因此记住了首次回调。`::transfer` 具有相同风险。

## 有界修复

生产修改只在 `EntryScreens.kt` 和 `EntryComposerControls.kt`：

- `::amountKey` 和 `::transfer` 改为显式 lambda。
- EntryForm 用 `rememberUpdatedState` 保存最新 draft、busy 和外部回调。金额键、保存和转账在事件发生时从 State 读取当前数据。
- 键盘控件使用 `rememberUpdatedState(onKey)`，各键点击读取当前回调，避免回调缓存继续保留旧捕获。
- 系统键盘“输入”按钮每次递增请求序号；effect 以模式和序号为键执行 focus/show。因此系统返回键只隐藏 IME、模式仍为 true 时，再点“输入”仍会重开。
- 表达式计算、原 draft.command 验证、OCR 确认、selection 插入/删除、领域命令和根导航保持原契约。

`git diff --check` 已通过。本实现代理未执行 Gradle/ADB；root 正统一编译并设备复测 `17`、退格、`12.34`、`3+4` 保存及转账、IME 返回后重新打开。构建/设备通过与否以该轮实际日志为准，本文不预先宣称通过。
