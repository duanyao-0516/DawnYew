# 财务领域与备份代码交叉审阅（存储实现者）

日期：2026-10-24。审阅对象为当前工作树 `shared/core` 财务命令、全图校验、查询、旧命令兼容，以及 `BackupProtocol` 与 Android `JsonStreamCodec` 的版本契约。审阅者未改 core/Android 代码。存储与 shared/protocol 由本审阅者实现，因此这些部分仅记一致性自查，不能当作独立审阅。

## 结论与问题

本轮未发现新的可证实且未关闭的 P0/P1/P2 领域缺陷。审阅期间发现一个同步中的跨层规则差异，已按真实失败测试修正：

| ID | 证据与影响 | 处理与状态 |
| --- | --- | --- |
| RV-ST-01 / P2 | core 将周期模板账户起始校验改为下一未处理期；候选 SQL 仍用原 anchor。已有历史计划改用后来开户的账户后，合法 v2 备份会被候选拒绝。失败测试 `FinancialStorageTest.recurringTemplateUsesNextUnprocessedOccurrenceForLaterAccountOpening`，日志 `.local/finance-storage-schedule-red-20261024.log` 为 7 tests / 1 failure。 | 候选逐计划调用公共 `recurringNextOccurrence`，保持历史 occurrence 锚点校验不变；有限已耗尽计划不再限制账户起始日期。源码已修，主任务已在周期协调器 RED 之前重跑此用例并确认 GREEN，问题关闭。 |

待入账结算是否预占额度曾存在方案歧义，主任务已明确选择“不预占，转入账时复核本金”。目前 core 与候选都只检查 active + recorded 的结算不超过 active + recorded 本金；这不是遗漏或假装已入账。

## 已核对的调用链

- `DomainEngine.apply` 对旧 `SaveEntry` / `AddRefund` 的两个新增参数同时为空时省略序列化字段，使旧 payload digest 保持原形状；已有回执先比较摘要再返回结果。新增 details/tags 被纳入新命令摘要，编辑同步递增 Entry.revision。旧回执固定摘要测试已由主任务执行。
- `Purge` 和 `UndoImport` 清理 Details/Tag，并只清空已处理周期的结果引用，保留 occurrence 与 nextIndex；恢复删除组仍走完整金额及关联验证。移动账本不能静默留下跨账本账户/维度，最终 state 校验会拒绝。
- 普通转账、单据资金、存钱资金有明确命令归属。单据账户一对一、零期初、固定类型/资产计入和起始时间；普通流水/周期/存钱不能引用单据专属账户。单据资金修改、删除、恢复后复核整单已入账本金/结算。
- 存钱 movement 与可选单端/双端 transfer 原子更新；资金方向按显式 from/to，进度方向按 movement kind，两者不混算。Wish 只以 fulfilledMovementId 作为完成事实，取消同时清理 movement 与 transfer；完成后编辑/删除先拒绝。
- 分类预算 amount 是当前额度，reallocation 不再次累加；反向撤回有唯一引用和余额检查。删除整月预算同步清理子额度/历史，不改交易。
- 周期日期固定原始锚点：月/季/年使用原 anchor 加月份，工作日/周末按对应序列计算；凭证唯一 (planId,index)，连续覆盖 `[0,nextIndex)`。生成 Entry 使用每期独立 creationOperationId；批操作外层回执独立。失败不发布 mutation，手动跳过保留凭证。
- 查询 parity：refund 预算资格动态读取父支出；未入账仍进入普通统计和预算；分类过滤排除 transfer；标签为 ANY；family + id 区分同 UUID；所有 scope 字段绑定 cursor。资产仅由 opening + active/recorded 的资金增量计算。
- v1 仅按原九类、原字段和原 source counts/digest 验证，提升只补新表空集合；v2 精确 23 类。signed opening 与 nullable target 使用不同的十进制字符串规则。来源 metadata 与 expectedCandidateCounts 分离；Android 根字段乱序仍逐记录暂存，摘要只遍历来源版本的类型列表。

## 运行证据与限制

主任务统一运行编译/测试，本 agent 未运行 Gradle、ADB，也未提交 Git。已收到并检查首轮 `shared:storage` 53 tests 和 `shared:protocol` 21 tests 全通过的日志/汇报，覆盖旧 schema 1/2/3 副本升级、冻结 v1 字节摘要、全 14 新实体连接图、候选失败回滚、23 类流式往返、10k+ SQL 查询与 >20MiB/10k 流式既有用例。之后新增的 RV-ST-01 测试已观察 RED，修正后主任务已重跑并确认 GREEN。

上述测试不证明 Android SAF/加密/锁屏生命周期、Release 包、真机 UI 或性能全部验收。CSV 接入是独立后续增量，本审阅不对尚未完成的 CSV 修改给出通过结论。

## 后续窄增量复核

- CSV 本机同 ID 映射：已只读检查 `isSameDatasetCsvIdentity` 与 preview/commit 两处使用。只有 DAWNYEW_CSV、当前 dataset、匹配外部 ID/entryId/kind、业务内容一致，且现有相同来源不存在或完全一致时复用本机条目；commit 的 continue 路径不覆盖 Entry、不追加或替换原来源。当前没有新发现；CSV 其余后续增量仍由独立测试及主任务验收。
- 前台周期协调器：按主任务新增授权，新增 `RecurringCoordinator.kt` 与 `RecurringCoordinatorTest.kt`。八项真实行为 RED 位于 `.local/finance-recurring-red-csv-green-20261024.log`；实现后最新测试 XML 为 8 tests / 0 failures / 0 errors。覆盖重复前台身份与 100 期上限、显式继续、busy/两类表单、未知回执、单计划失败继续、后台取消/epoch 更换、确认失败、自己的回执不匹配。此项由本 agent 实现，等待其他实现者交叉审阅，不能把本段当独立审阅。
- 协调器自动任务及显式继续使用 `AppController.performAutomatic` 的单写门。协调器不调用 acknowledge；该入口在执行后核对本次 operationId、Finance 类型和 resultIds 的完整回执，确认成功后才继续。所有摘要中的 generated 只计确认成功的期次；未知或未确认结果进入原恢复流程。
