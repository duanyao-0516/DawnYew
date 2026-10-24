# 存储与前台周期代码交叉审阅（领域实现者）

日期：2026-10-24。审阅对象为当前工作树的新财务存储、迁移、候选恢复、统一活动查询，以及 `RecurringCoordinator` 与其使用的 `AppController.performAutomatic`。审阅者未修改这些生产文件，也未运行 Gradle、ADB 或提交 Git。领域和 CSV 为本审阅者实现，不以本报告代替这些部分的独立审阅。

## 结论

本轮没有发现可复现且尚未关闭的 P0/P1/P2 问题。此前已识别的“周期模板账户日期使用原始 anchor”差异已改成公共 `recurringNextOccurrence`，候选与完整 state 当前均按下一未处理期校验；有限已耗尽计划保留归属与非虚拟账户限制。

## 存储与候选验证

| 核对内容 | 当前实现与证据 |
| --- | --- |
| 迁移与旧内容 | `CURRENT_DATABASE_VERSION=4`；`MIGRATION_3_4` 只增加十四张表、索引和约束，没有改写旧 Entry/Category payload。数据库显式注册 1→2→3→4 迁移。 |
| 唯一身份与关系 | `FinanceEntities` 的 EntryTag 复合键、周期 plan/index 和结果引用、单据 virtualAccountId、存钱 transferId、愿望 fulfilledMovementId、分类预算键和反向撤回键，与 `validateFinanceState` 一致。延期外键允许任意类型暂存顺序，最终外键检查拒绝悬空引用。 |
| 单写事务 | `RoomLedgerRepository` 在同一 writer 事务中执行领域命令、差量持久化、metadata 与 receipt；新表进入同一 Rows 模型。删除顺序和变化唯一键的暂存/重建兼容循环引用，没有第二份资金余额。 |
| 标量与 payload | `inspectCandidate` 分页 128 条按实体键扫描，严格 decode、单条 validateEntityBatch、重编码一致性与查询列逐项一致性验证；逐表核对连续 position，摘要覆盖全部 23 类。恢复候选不接受 operation receipts。 |
| 跨实体规则 | `FinancialCandidateRelations` 与 `validateFinanceState` 对照账户账本/期初、三类维度、退款预算、单据虚拟账户/方向、周期连续凭证/日期、movement/transfer 双向关系、愿望唯一完成事实、预算父项/额度/反向历史逐项等价。 |
| 借还与存钱汇总 | 单据仅 active + recorded 本金/结算参与剩余校验，待入账结算不预占；存钱进度只取 active movement 正负金额。SQL SUM 与最终资产 Money.add 均拒绝整数溢出；负余额形成负债。 |
| 发布前验证 | 候选完成暂存后执行关系验证；重开时再次 inspectCandidate 并比较完整 CandidateSummary，修改标量列或 payload 会使校验失败。 |

## 查询

- `FinancialActivityQueries` 的 ENTRY/TRANSFER union 保留 family + id 身份；排序与游标均为 occurrence、family、id 降序，游标绑定完整 scope、dataset、contentVersion。
- 账户过滤包含 transfer 两端，分类/项目/商家/标签过滤排除没有这些维度的 transfer，标签匹配 ANY；keyword 使用转义后的字面 LIKE。recorded、reconciled、deleted、includeArchived、金额上下限均参与共享 filter。
- 分页只读取 limit + 1 条交易 payload，标签仅取当前页 Entry；日历与总额直接用相同 SQL filter 聚合，不遍历 UI 分页，也不构造全库 LedgerState。
- 普通统计保留原 income/expense/refund；预算独立聚合，refund 动态连接父支出的 excludedFromBudget。未入账交易仍参与收支及预算。

## 前台周期协调器

- `ForegroundAccess` 与 dataset 构成工作身份；不可用、关闭、epoch 或 dataset 变化会取消 runner 及其当前 operation。旧 job 的 finally 使用自身身份比较，不能清掉替换 job。
- 自动与显式继续都经 `AppController.performAutomatic` 单写门；busy、普通编辑器、财务编辑器、app/repository 任一 pending recovery 均阻止提交。
- 每个工作身份累计最多 100 次提交，重复 ready 事件和同身份前后台往返不重置；显式继续授予下一批额度。单个失败计划按 revision 记下错误，其余可执行计划继续。
- 协调器不自行确认回执；performAutomatic 核对本次完整 OperationOutcome 后才调用回调与确认，未知结果或确认失败保留既有恢复入口并停止后续期次。
- 每期之间 yield，并复核身份、写门和恢复状态；generated 只在本次已确认成功且 job 未取消后计数。

## 证据及验证范围

已读取已有测试源码：`FinancialStorageTest`、`FinancialActivityQueryTest`、`MigrationTest`、`RecurringCoordinatorTest`。覆盖十四类连接图的候选/快照/重开、篡改与跨账本拒绝、虚拟账户金额、周期新账户日期、动态退款预算、全部 scope 字段与同 UUID family、10,000 条分页/日历、范围外 payload sentinel，以及八项协调器取消/限额/恢复测试。

构建由主任务统一运行：`.local/finance-integration-pass2-20261024.log` 中 `:shared:storage:jvmTest` 完成；该轮整体失败来自另一模块的旧 QueryLoadingTest，不能称为全项目通过。`.local/finance-recurring-red-csv-green-20261024.log` 中协调器八项真实 RED；`.local/finance-recurring-green-csv-trim-red-device-20261024.log` 中 `:shared:presentation:jvmTest` 完成，主任务确认八项协调器 GREEN，该轮整体失败是 CSV 裁剪新增 RED。源码审阅与这些 JVM 结果不代替 Android SAF、锁屏恢复、真实设备及 Release 验收。
