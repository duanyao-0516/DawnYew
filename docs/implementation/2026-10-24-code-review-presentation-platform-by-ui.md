# 2026-10-24 UI agent 对 presentation / Android 的独立交叉审查

审查范围：EditorDraft / AppController、FinanceController、JsonStreamCodec v1/v2 生产链、MainActivity CSV / Widget 和会话生命周期。源码快照为当前未提交工作树。只读审查，不修改这些模块，不运行 Gradle / ADB。以下行号为初次发现时位置，修复后可能变化。

## PLATFORM-UI-01 · P2 · CSV 暂存文件需要覆盖跨 dispatcher 取消

**状态：CLOSED-SOURCE；root 修复后已独立复核。集成测试 / 设备证据待主 agent 记录。**

位置：`androidApp/src/main/kotlin/com/dawnyew/app/MainActivity.kt:451-458`。

CSV 文件在 IO block 内创建并完整写出，随后由 `withContext(Dispatchers.IO)` 返回主线程。若恰在文件写完而主线程 continuation 尚未运行时触发 onPause / session revoke，外层 fileJob 被取消，withContext 的及时取消语义会直接抛 CancellationException。此时文件没有交给 csvDialog 的 dismiss listener 或 PendingExternal，局部 write 的 catch 也不会执行，runFileTask 的 finally 只清 busy。明文账务 CSV 会留在私有 staging，直到进程重新启动清理。

建议使用相邻 exportBackup 已采用的 outer prepared 引用和所有权转移标记，在外层 finally 以 NonCancellable 清理尚未交出的文件。验证覆盖取消发生在写出后 / 展示前、预览取消、认证取消、picker 取消和成功导出；每种路径只能由一个拥有者清理。

复核：root 已在 IO block 创建文件时设置外层 scratch，dialog 完成接管才置 handedToDialog；未交出路径在 NonCancellable + IO finally 清理。原 dialog dismiss / 认证取消 / PendingExternal 清理保留，跨 dispatcher 取消不再丢失文件所有者。

## PLATFORM-UI-02 · P2 · 会话失效未清理金融草稿和筛选

**状态：CLOSED-SOURCE；root 修复后已独立复核。集成测试 / 设备证据待主 agent 记录。**

位置：`androidApp/src/main/kotlin/com/dawnyew/app/DawnYewApplication.kt:67`、`MainActivity.kt:130-135,173-179`。

批准 spec 第 81 行要求授权失效清理新草稿和筛选。当前 revokeSession 只撤销 session / picker 并发布通知；Activity 收到通知只取消文件任务及原生预览，没有推进 controller.transientGeneration / resetTransientState。默认无锁用户在金融表单输入后熄屏会发生 session epoch 变化，但 authorized 仍为 true，Compose 保持挂载，旧表单和 operationId 原样保留；锁启用用户的 AppController.editor 也不会随 Compose 锁屏卸载清理。普通 onPause 的 SessionCoordinator.background 可能改变 epoch，而不经过 application 的 revocation flow，同样遗漏。

建议把真实 session epoch 失效统一接到 transient 清理，保留合法 auth / picker / rotation continuation，不把 pending 写入回执当成草稿删除。验证无锁熄屏 / 后台、锁启用后台、合法文件选择返回、认证弹窗往返及旋转。

复核：SessionCoordinator.revoke 为唯一 epoch++ 位置，现同步调用 onRevoked；Application 回调立即撤销 recurring access，resetTransientState、清理 external 并发布 revocation，Activity 同时清 capture。内部 background / foreground 的 revoke 也能到达回调；合法 picker / auth / rotation 没有 epoch 变化，不执行这次清理。pending 写入回执不由 resetTransientState 删除。已看到新锁定 / 无锁与 picker / rotation 回调测试及初始红测输出，绿测由主 agent 统一运行。

## 已检查未发现新增阻断项

- EditorDraft 原有 OCR 确认、疑似重复核对、operationId 与 expectedRevision 保留；新增 EntryDetails / tagIds 随原命令一次提交。改变账本会清理不属于目标账本的账户和维度；现有账单编辑保留存量附加字段。
- FinanceController 返回帧保存完整 UiNavigation 与 FinanceNavigation，账户 / 计划下钻返回有来源 ID 和 activityScope；新查询结果受 dataset / 内容版本 / 请求序列保护，分页有游标与更新冲突重载。
- JsonStreamCodec 仍逐记录暂存 / 排序，保留来源 formatVersion / counts / digest；v1 缺省新实体通过 expectedCandidateCounts 提升而不改来源摘要。输入严格重复键、字段、整数和实体集合校验，输出按当前 v2；读写资源用 use，暂存异常清理仍在生产路径。
- Widget 使用显式本地 Intent、静态动作和不可变 PendingIntent，不持久化财务摘要，也不从 Intent 自动提交交易。
- 新前台 recurring gate 包含无锁用户、resumed、covered、device lock、仓库 ready、文件任务、认证、picker 和原生 CSV / restore 预览；controller 内有用户 editor / 金融弹窗 / busy / pending 回执门。

这些是源码审查结论，不代替主 agent 的编译、回归测试和 Android 运行证据。
