# DawnYew 离线领域、存储与备份契约调查

日期：2026-10-24。性质：源码调查与可实施设计建议，尚未运行编译、测试或设备验证。本文只新增调查文档，不修改业务代码。

## 1. 结论与范围

建议保留现有 `Entry`、`Category`、`MonthlyBudget` 及备份 v1 的九类记录形状，采用同库扩展实体：`EntryDetails`、账户、单记录双端转账、资料维度、借还报销单、周期计划、储蓄计划及其进度记录。所有实体进入同一个 `LedgerState`，由 `DomainEngine` 命令在同一 Room 写事务中校验、落库、写回执并递增 `contentVersion`。这是既有账本的扩展，不是另建 preferences/文件账本。

仅 UI 读取采用 `FinancialActivity` 统一投影：普通收支/退款来自 `Entry`，转账来自 `AccountTransfer`。投影不能成为另一份可写流水数据。账户余额、首页、统计、预算、明细、回收站、导入撤销、备份必须明确消费哪种事实和过滤条件。

相较给 `EntryKind` 直接增加 TRANSFER 并把 categoryId 改可空，该方案保留现有分类复合外键、退款聚合、导入内容摘要、v1 严格解析及旧 payload。代价是新增实体关系、统一交易分页/过滤和备份 v2；这些是必做工作，不能以 UI 内存拼表掩盖。

前置依据已读取：用户指定工程约束记忆、2026-10-18 技术方案、薄荷完整功能与 UI 分析及当前实现。仓库没有搜索到附加 `AGENTS.md`。工作分支为 `p/duanyao.0516/codex/dawnyew-android-app`；已有 `gradle.properties`、`docs/implementation/ocr-release-jni-verification.json` 修改及未跟踪 `gradle/gradle-daemon-jvm.properties` 保留。旧技术方案“无账户/资产/负债/转账”由本轮用户新目标替代；免费、无账号、无网络和候选代恢复等约束继续成立。

## 2. 当前实现证据

下表路径均相对仓库 `/Users/bytedance/Desktop/code/DawnYew`；行号为调查时基线。

| 责任 | 文件、位置、规模 | 已有契约 |
| --- | --- | --- |
| 模型与命令 | `shared/core/src/commonMain/kotlin/com/dawnyew/core/Models.kt:6`，102 行 | EntryKind 仅 EXPENSE/INCOME/REFUND；LedgerState 有九类备份实体及 receipts；没有账户、项目、商家、标签、周期 |
| 写入与幂等 | `shared/core/src/commonMain/kotlin/com/dawnyew/core/Domain.kt:73`，210 行 | operationId 与序列化 command 摘要绑定；expectedVersion；每次变更统一 validateState；receipt/contentVersion 与业务一起生成 |
| 退款/删除 | `Domain.kt:132`、`:152`、`:166` | 活跃退款同账本分类、日期不早于原支出、累计不超原额；删除原支出带当前活跃退款；单删旧退款不换组 |
| 业务验证 | `shared/core/src/commonMain/kotlin/com/dawnyew/core/Validation.kt:21`，95 行 | 全图引用、名称、删除组、退款合计、导入批次资格；偏好 key 白名单 |
| 导入身份 | `shared/core/src/commonMain/kotlin/com/dawnyew/core/Import.kt:41`、`:47`、`:132`，148 行 | sameImportedBusiness 明确比较字段；committedContentDigest 哈希完整 Entry；撤销核对 revision、摘要和批外引用 |
| 内存统计 | `shared/core/src/commonMain/kotlin/com/dawnyew/core/Statistics.kt:18`，43 行 | 收入、原支出、退款；净支出=支出−退款；budgetUsed=max(净支出,0) |
| SQL 查询 | `shared/storage/src/commonMain/kotlin/com/dawnyew/storage/EntryQueries.kt:12`、`:41`，101 行 | 同一 SQL 过滤用于分页与汇总；单读事务，cursor 绑定 dataset/contentVersion/scope；当前只查询 entries |
| Room 表 | `shared/storage/src/commonMain/kotlin/com/dawnyew/storage/Entities.kt:16`，48 行 | 11 张表，Entry 分类复合 FK；每实体同时有查询列与完整 JSON payload；creationOperationId 唯一 |
| 仓库 | `shared/storage/src/commonMain/kotlin/com/dawnyew/storage/RoomLedgerRepository.kt:51`、`:110`、`:134`，237 行 | immediateTransaction 内读旧状态→engine.apply→差量 persist；解码后重编码，Rows 完全相等才接受 |
| SQLite 保护 | `shared/storage/src/commonMain/kotlin/com/dawnyew/storage/DawnYewDatabase.kt:54`，103 行 | FK、WAL、FULL、约束 triggers，缺少 trigger 拒绝打开 |
| 数据版本 | `shared/storage/src/commonMain/kotlin/com/dawnyew/storage/SchemaUpgrade.kt:7`，44 行；`Migrations.kt`，41 行 | CURRENT_DATABASE_VERSION=3；1→2 提取 creationOperationId，2→3 增加 archived/noteSearch；无 destructive migration |
| 批传输 | `shared/core/src/commonMain/kotlin/com/dawnyew/core/LedgerEntityBatch.kt:4`，41 行 | 九类实体固定列表；逐批字段校验与全图验证分离 |
| 候选与导出 | `shared/storage/src/commonMain/kotlin/com/dawnyew/storage/Streaming.kt:17`、`:49`、`:61`，97 行 | 固定 type→table；任意输入顺序、deferred FK；128 条分页；同一只读事务导出 |
| 候选复核 | `shared/storage/src/commonMain/kotlin/com/dawnyew/storage/CandidateVerification.kt:19`、`:77`，109 行 | 完整性、位置、索引列/payload、重编码等价、关系与摘要；恢复候选 contentVersion=1、无设备 receipts |
| 备份协议 | `shared/protocol/src/commonMain/kotlin/com/dawnyew/protocol/BackupProtocol.kt:14`，98 行；`RecordSchema.kt`，44 行 | v1 九类实体固定顺序；完整字段严格匹配；Long 字符串；拒绝未知字段/null 类型/缺省字段 |
| Android JSON | `platform/android/src/main/kotlin/com/dawnyew/platform/files/JsonStreamCodec.kt:45`，224 行 | 严格重复字段、标量记录、有界逐条解析、磁盘暂存排序、完整 SHA256；当前 writer/parser 写死版本 1 |
| 代际协调 | `platform/android/src/main/kotlin/com/dawnyew/platform/storage/GenerationRepository.kt:85`、`:305`、`:337`，484 行 | epoch、授权、pending-operation、候选基线检查、保护代、atomic pointer；升级复制候选后迁移，不改保护原代 |

本次直接梳理的 core/storage/protocol 与 Android storage/files Kotlin 文件共 3,004 行。实现影响不仅限于 Models/Domain；至少覆盖本表中的所有数据链路，以及 presentation 查询与 UI 路由。已有测试是验证基础，不是本次运行通过的证据。

## 3. 旧数据兼容的两个硬约束

### 3.1 默认参数不等于磁盘兼容

`RoomLedgerRepository.decode()` 用当前 serializer 解码后再 `Rows.from()` 比较原 rows；`CandidateVerification.readRecord()` 还要求 encode 后的 JSON 字符串等于 payload。直接给旧实体新增默认字段，即使 Kotlin 能解码旧 JSON，encodeDefaults=true 也会生成新字段，旧 payload 会被判损坏。因此保留旧模型形状确有价值；若最终仍修改任一旧模型，必须在迁移中同步重写全部受影响 payload 和派生查询列，并测试旧数据打开与保护代读取。

新增 Command 子类型不改变旧命令序列化内容；给既有 Command 增加默认参数则可能改变旧 operation receipt 的摘要，旧命令重试需兼容策略。因此建议 `SaveFinancialEntry` 包装旧账单输入及扩展输入，旧 `SaveEntry` 继续可用且保留其哈希形状。

### 3.2 导入撤销不能忽略扩展编辑

保留 Entry 字段形状可保留旧 `committedContentDigest`。但修改账户、是否入账、预算排除、项目、商家、标签也是业务编辑，必须同时递增所属 Entry.revision 并更新 updatedAt；不能只改 EntryDetails/EntryTag。这样旧 ImportMember revision 检查会拒绝已编辑记录的撤销。新导入若支持这些字段，其去重内容比较/预览摘要也必须覆盖扩展内容；不能仍仅用 `sameImportedBusiness(Entry,Entry)` 宣称完整等价。

v1 恢复不生成伪造 EntryDetails，缺失扩展具有明确的兼容语义。v1 恢复后未编辑的旧导入批次仍应具备原有撤销资格；已经改过的批次不得因迁移被重新授权。

## 4. 推荐实体与单一事实来源

### 4.1 流水扩展、资料与账户

| 实体 | 建议字段/关系 | 约束与默认 |
| --- | --- | --- |
| EntryDetails | id=entryId；accountId?；recorded；excludedFromBudget；projectId?；merchantId?；reconciled | PK/FK Entry；缺记录时 accountId=null、recorded=true、excluded=false、无维度、未对账；软删状态跟随 Entry，不能另有 active |
| EntryTag | entryId/tagId 复合主键 | FK Entry 与标签；标签属于同账本；重复标签拒绝；不要在当前标量 wire 中直接塞 tagIds 数组 |
| Dimension | id、ledgerId、type(PROJECT/MERCHANT/TAG)、name、archived、sortOrder、revision | 同账本同 type 规范化名唯一；归档不删历史关联；被 Entry/Transfer/周期模板引用时不允许硬删 |
| Account | id、ledgerId、type、name、openingBalanceMinor、openingAt、includedInNetWorth、hidden、archived、sortOrder、revision | CNY；余额以有符号 Long 分表示；hidden 只影响列表，不改变资产；期初不是收入；旧流水 accountId=null，不能自动全部塞进现金账户 |
| AccountTransfer | id、ledgerId、fromAccountId、toAccountId、amountMinor、occurrence、note、recorded、reconciled、deletionState、revision、createdAt/updatedAt、creationOperationId；可选业务单关联 | 一条事实表示双账户移动；同账本同币种、账户不同、金额正数；操作 ID 唯一；不可把双腿存成两条收入/支出 |

Reference 只有小额同币种数据和资料目录的充分证据。本轮继续 CNY；外汇行情、自动收益同步、云共享等依赖网络的功能明确占位，不使用静态数字假装在线结果。

账户余额只派生：

`balance(account) = openingBalance + Σ active && recorded && occurrence >= openingAt 的账户变动`

变动为收入 +、支出 −、退款 +、转出 −、转入 +。`openingAt` 的包含边界必须固定（建议含起始秒）；不能在账户余额之外再累加借还单的 remaining，否则重复计算资产。净资产为所有 included 账户有符号余额之和，资产为正余额和，负债显示负余额和；隐藏账户仍参与。账户性质用于分类和表单校验，不再额外翻转已经带符号的余额。

退款可默认继承原支出 accountId，但退款本身的 recorded 和到账账户属于退款事实。不能因之后编辑原支出的账户而偷偷重写已到账退款的资金路径。原支出分类/账本移动仍遵守既有退款规则；跨账本移动涉及账户/维度时应要求完整映射或明确拒绝该动作，不能静默清空关联。

### 4.2 借入、借出、报销

增加 Contact 和 Obligation（类型 BORROW/LEND/REIMBURSE、ledgerId、contactId?、名称、专属结算账户、状态、revision）。应收/应付使用 Account 子类型，不再另存权威余额：

- 借入 10：应付账户 → 现金 10，现金 +10，应付 −10。
- 归还 2：现金 → 应付账户 2，应付变 −8。
- 借出 6：现金 → 应收账户 6，应收 +6。
- 报销支出 4：现金 → 报销应收账户 4；它是转账，不增加普通消费。
- 收回款：相应应收账户 → 现金；不得凭界面“已收款”标记修改 remaining。

每单专属 tracking account 或每单明确的 transfer link 都可实现；推荐专属账户并记录 obligationId，以免同人多笔欠款混淆。列表联系人汇总按其单据账户聚合；隐式技术账户应在账户页按应收/应付分组呈现，不伪装成独立用户现金。

还款/收款不得超过可结算额；删除/修改原始借款而保留还款可能制造反向债权，命令内重验整单。明确的差额结清若实现，必须生成真实收入/支出并关联该单，不能把 outstanding 直接改零。普通删除、恢复、归档也必须重验整单，不只校验当条 transfer。

### 4.3 储蓄计划

SavingsPlan 保存目标、起止、名称、类型、revision；SavingsMovement 保存计划 ID、金额、存入/取出、日期、note、可选 transferId 与删除状态。计划进度是 movement 净额，不是收入/支出，也不是另一份账户资产。

- 两账户都空：纯计划记录，只改变进度。
- 两账户齐全：同一命令创建一条 transfer 与一条引用它的 movement，进度和账户一起提交。
- 只选一个账户：建议本轮校验拒绝，提示同时选择来源/目标或都留空。参考静态链说明“至少一账户有效时建交易”，但未取得单账户方向和统计口径实测；不要靠猜测生成普通消费。该差异须在技术决策/界面说明留档。

不能从计划中删除关联 movement 而保留未告知用户的资金变动；删除/恢复需原子处理引用或明确允许只解除计划关联且保留 transfer。第一版建议关联记录成组操作，直接编辑被计划关联 transfer 的金额亦同步重验 movement。

### 4.4 周期计划

RecurringPlan 保存模板、频率、原始 anchor 日期、次数/结束条件、暂停状态、revision。新增 RecurringOccurrence(planId, sequence) 唯一执行台账，指向生成的 Entry 或 AccountTransfer；生成记录与 occurrence、计划游标必须同事务。不能只保存 lastRunAt 后在 UI 触发普通 SaveEntry。

规则保存时，如首期已到，应按参考生成首笔；未来首期不违反现有“不能创建未来账单”。月末日期用原始 anchor 的日号截到当月末，不能 1 月 31 日→2 月 28 日之后永久变成每月 28 日。时间恢复/时区变化不能重写已生成事实。

离线启动/回前台计算到期项，按有界批次执行；长时间未启动时列明待处理次数。每期唯一键防多次启动、崩溃重试、恢复后重复执行；已经生成后被删除的流水仍保留 occurrence 审计，不自动补生。暂停不生成；修改模板仅影响未来未执行期，历史不随模板变化。系统后台调度若实现，仍调用同一仓库门，不另开写库进程。

## 5. 三套金额口径及统一交易列表

| 场景 | 余额 | 收支统计 | 预算 |
| --- | --- | --- | --- |
| 普通已入账支出 | 扣对应账户 | 计支出 | 未 excluded 时计入 |
| 普通未入账支出 | 不变 | 仍计支出 | 未 excluded 时仍计入 |
| 不计预算支出 | 按 recorded | 仍计支出 | 排除 |
| 普通收入 | 按 recorded 加账户 | 计收入 | 不抵消费预算 |
| 退款 | 按 recorded 加账户 | 计退款，净支出减少 | 按退款自身发生期；是否 excluded 默认承接原支出，允许规则需固定 |
| 账户转账/借还/报销 | 双账户等额反向 | 不计普通收支 | 不计消费预算 |
| 账户期初 | 定义起始余额 | 不计收入 | 不计 |
| 无账户储蓄进度 | 不变 | 不计 | 不计 |

`StatisticsSnapshot.budgetUsed` 当前直接用净支出，新增 excludedFromBudget 后会错误。必须独立计算 budgetExpense/budgetRefund 或独立 `BudgetUsage`，不能继续拿普通支出汇总充预算；不要把 recorded 加进通用 entryFilter。

SQL 用 `LEFT JOIN entry_details d ON d.id=e.id`，以 `COALESCE(d.recorded,1)` 和 `COALESCE(d.excludedFromBudget,0)` 表达兼容默认；账户余额过滤 recorded，预算过滤 excluded，普通统计均不按 recorded 过滤。tag 过滤用 EXISTS，不能直接一对多 JOIN 后重复 SUM 金额。内存计算与 SQL 需要同一组金样例交叉验证。

FinancialActivity 统一 key 为 `(activityType,id)`，排序为 occurrence DESC、id DESC、activityType 固定顺序；分页 cursor 必须增加 type 并继续绑定完整 QueryScope、datasetId、contentVersion。不可分别从 entries/transfers 各取 50 条再拼成“前 50 条”而丢后续游标。可采用 SQL UNION ALL 的统一投影，或同一读快照的有界双路归并；所有过滤先下推，再分页。

主页最近交易、全部交易、日历、搜索、账户/项目/商家/标签下钻与交易笔数统一使用 Activity 查询。普通收支金额仍按 Entry 计算；交易笔数应包含一笔转账一次，不能按转账两端计两次。Account 明细有方向与交易后余额，不能复用普通收入标签标记借入。

## 6. 预算扩展与删除约束

现有 MonthlyBudget 是 `(ledgerId,yearMonth)` 唯一月总限额，应继续是既存月总预算的单一来源。先完成独立 budgetUsage 与未入账/排除口径，再扩展分类/项目预算。若添加预算定义/分配实体，需明确定义其与 legacy 月总限额的关系，不能同时保存两份同月总额度并由不同页面分别读。

建议分类预算以月总预算下的 allocation 表表达（ledgerId、yearMonth、categoryId、amount），子预算消费按 category 查询；总消费不是各子预算消费相加，因为可能存在未分配分类。额度转移仅调整 allocation，保持总分配额不变，不创建金融流水。跨期结转、任意周期等尚未有充分实测口径，应另写明确公式与验收再开放；不能以“剩余值”持久化代替可重算的额度和实际消费。

删除关系必须逐项覆盖：

1. EntryDetails/EntryTag 跟随 Entry 硬删；软删期间保留原扩展，恢复重验账户/维度/周期/业务单。
2. 转账是单条双端事实，可以自带 deletionGroupId? 或 deletedAt，但不能直接使用现有 DeletionGroup 验证：当前非空组的 root 必须是 entries 中成员。推荐转账独立删除状态、统一回收站读投影；如复用组，必须先升级组模型和全部旧校验，不可偷绕。
3. 账户被活跃/回收站 EntryDetails、transfer、计划模板或储蓄关联引用时只允许归档/隐藏；硬删拒绝。维度同理。
4. DeleteLedger 当前只检查 entries，扩展后必须检查 transfers、accounts、计划、借还单等；ClearAll 通过 initialState 清全域，不遗漏新表。归档账本停止周期生成。
5. UndoImport 除既有批外退款外，还须检查新计划/借还/储蓄等对本批 Entry 的强引用；同事务移除 Details/Tags。改扩展必须 bump Entry.revision。
6. 操作结果恢复目前无 receipt 时回查 Entry.creationOperationId，需补新实体的创建 operationId 查询，避免转账已提交却恢复界面误判未提交；一条命令产生多个实体的业务根身份应可持久恢复。

### 6.1 旧命令的扩展整合点

| 旧入口 | 必须保留/补齐的行为 |
| --- | --- |
| SaveEntry | 同账本普通编辑保留已有 Details/Tags；不因旧 UI 没传新字段而清空。改种类与账户资金方向联动重算；有关联借还/储蓄/周期审计时重验其关系。跨账本且存在账户/维度引用时必须要求映射，不暗中迁移引用对象 |
| AddRefund | 创建新退款时默认从原支出复制 accountId、recorded、excludedFromBudget、projectId、merchantId 和标签，reconciled=false。这是初始默认；之后退款的账户与 recorded 可以独立编辑。未提供扩展的旧支出保持无账户默认；不修改旧 AddRefund 的序列化形状 |
| MoveEntry | 同账本换分类保持 Details；跨账本移动原支出和所有有效退款时必须一起提供每条活动的账户/维度映射。已在回收站退款暂不移动扩展，RestoreGroup 时按当前目标账本重验，必要时要求映射；不能只映射分类后放过跨账本 account |
| SoftDelete/RestoreGroup | Details/Tags 自身不软删，是否有效取所属 Entry；恢复除现有退款约束外重验引用与业务单。历史停用分类可恢复与既有规则相同；隐藏/归档账户是否允许恢复必须定为显式策略，不能由 UI 偶然决定 |
| Purge | 与原支出/退款级联范围使用同一 ID 集合删除 Details/Tags；被业务单/周期/储蓄引用时选择明确拒绝或保留不可强引用的历史审计标识；不能制造悬空 FK |
| UndoImport | 成员版本/摘要检查后，再拒绝新增强业务引用；一起删除 Details/Tags。扩展编辑一律 bump Entry.revision，防止原 ImportMember 错判未修改 |

实现应提取受限的 entry mutation/helper 供旧入口与新 `SaveFinancialEntry` 共用；不能在一次新命令内部递归执行 `DomainEngine.apply(SaveEntry)` 再执行另一条命令，否则会提前生成多份回执/contentVersion，破坏复合操作单次提交语义。每次用户动作只有一个外层 operationId、一个原子 mutation 和一次版本递增。

## 7. 数据库 v4 与备份 v2

### 7.1 数据库迁移

采用扩展实体可让 v3→v4 只创建表/索引/triggers，旧九类 payload、revision、creationOperationId、import digest 不变。新建外键均 deferred，account/维度引用同时校验 ledgerId，金额不使用 REAL。新增表必须进入 DAO、Rows、decode、persist 的父子写删顺序、isEmpty、差量映射、流式候选、导出和完整验证。

更新 `CURRENT_DATABASE_VERSION`、Room entities 与 schema 导出；注册 MIGRATION_3_4；为新表安装全部 INSERT/UPDATE guards，不能无条件重建旧同名 triggers。旧 trigger 缺失仍拒绝，不能用迁移顺手“修复”掩盖损坏。保留 v1→v2→v3→v4 连续升级与保护代的复制升级。

若给旧实体增加字段（如 Category 父分类/图标、Ledger 周/月/年起始）则不再属于仅建新表迁移；建议同样用 `CategoryDetails`/`LedgerSettings`，否则按第 3 节重写 payload，并明确旧 receipt/digest 影响。

### 7.2 协议兼容

当前 v1 严格拒绝缺/多实体、缺/多字段；不要打开 ignoreUnknownKeys，也不要把 v1 的 entityTypes 原地改成新全量后仍导出 formatVersion=1。

推荐 `schemaFor(formatVersion)`：v1 保留九类类型顺序/字段/数值表示；v2 保留旧记录形状并追加新实体；entityTypes、recordFields、recordKey、encode/decode、manifest/counts、digest 均按版本选择。新导出仅 v2；旧 v1 可导入，新未知版本拒绝。

生产 parser 允许根字段任意顺序，而 `formatVersion` 可能出现在 entities 后。不能在看到 record 时假设已知版本，也不能为方便破坏旧格式排序契约。可先有界落盘保留原记录、解析完整元信息，再按对应版本严格验证；或先扫描元信息后重新读私有暂存流。任何升级前，必须按旧原始记录及旧 counts 校验原 SHA256，不能先补字段/补空数组后再检查旧摘要。

v1→当前领域：新实体集合为空；EntryDetails 缺省由领域读取明确解释。候选库 counts 包含新表 0，而旧 manifest 只有九类；`GenerationRepository.prepareRestore` 目前直接比较两份 counts，需用已认证输入版本映射出的 expectedCandidateCounts 比较，不能删掉校验。候选内容摘要应覆盖所有新增表，发布前仍复核。

账户 openingBalance 可为负；当前 DECIMAL_STRING 仅非负，v2 必须新增有符号十进制字符串类型（拒绝 +1、-0、前导零、Long 溢出），不能复用非负 Money.requireValid。其他金额与 revision 继续各自范围。建议实体字段保持标量，EntryTag/RecurringOccurrence 用实体关联，避免把嵌套列表塞成不校验的 JSON 字符串。

`.dwybak` 外层加密容器无需因内部 JSON v2 改格式；导出/解密后仍进入同一 v2 JSON 校验。清单、解析、候选复核的严格性不因新增功能降低。设备安全设置、凭证、文件授权、临时图片、receipts 继续不备份；业务周期执行台账属于业务事实，必须备份。

## 8. 必要验收与测试矩阵

现有可扩展测试入口：

- `shared/core/src/commonTest/kotlin/com/dawnyew/core/DomainTest.kt`、`DomainBoundaryTest.kt`、`ImportCommandTest.kt`。
- `shared/storage/src/jvmTest/kotlin/com/dawnyew/storage/RoomLedgerRepositoryTest.kt`、`EntryQueryTest.kt`、`MigrationTest.kt`、`StreamingCandidateTest.kt`、`ImportTransactionTest.kt`。
- `shared/protocol/src/commonTest/kotlin/com/dawnyew/protocol/BackupProtocolTest.kt`、`BackupStrictTest.kt`。
- `platform/android/src/test/kotlin/com/dawnyew/platform/files/JsonStreamingBackupTest.kt`、`JsonStreamCodecTest.kt`；`platform/android/src/test/kotlin/com/dawnyew/platform/storage/GenerationRepositoryTest.kt`。

| 层 | 必须证明的行为 |
| --- | --- |
| 领域金额 | 参考金样例：期初1000、支出12.34、收入100、周期支出3、借入10/还2、借出6、报销4、现金→储蓄20、未入账支出1、无账户储蓄5；现金1062.66、储蓄20、应收10、应付−8、净资产1084.66、收支支出16.34、收入100、结余83.66、300预算余283.66；储蓄进度5独立 |
| 幂等/事务 | 同操作重试不重复两端或计划进度；同 ID 不同 payload 冲突；receipt 写失败账户/流水/计划/版本全部回滚；并发结算不能超欠款 |
| 口径 | 未入账影响余额但不改收支/预算；excluded 只改预算；退款跨期；隐藏账户仍计资产；多标签过滤不重复 SUM；无账户旧流水不伪造现金 |
| 生命周期 | 删原始借款/退款/计划关联记录受完整关系约束；恢复失败全不变；清空/账本删除/归档覆盖所有表；归档停止周期执行 |
| 周期 | 保存已到期首笔；未来首期；暂停；重启/重复执行/恢复后幂等；月末/闰年；模板变更不改历史；已删除执行流水不补生 |
| 双实现一致 | 内存/SQL 在同一数据集、同一 scope 对金额、笔数、维度过滤、时间边界一致；统一分页同秒 Entry/Transfer 无重复遗漏；版本变化旧游标拒绝 |
| 本地升级 | v1/v2/v3→v4 保留旧 payload、统计、creationOperationId、未变批次撤销资格；旧有修改批次仍拒绝撤销；候选失败原代字节与指针保留；未来 schema 拒绝 |
| 备份 v1 | 固定原 v1 文件金样例，类型/数组/根字段乱序仍正确；原 SHA 先校验；新增实体为空；重新导出 v2 再恢复统计一致；旧重复字段/显式 null/坏类型继续拒绝 |
| 备份 v2 | 新实体每类至少一条及软删/归档/执行历史 roundtrip；缺/多字段、重复 ID/复合键、悬空跨账本引用、负零/溢出金额均拒绝；大文件保持128条有界传输 |
| 发布故障 | 新全域数据参与候选摘要；预览后普通编辑阻止替换；候选受损不发布；pointer rename/fsync/journal 故障后不删已激活代；新命令 pending-operation 可恢复 |

参考样例来源是 `.local/research/bohe-20261024/薄荷记账-完整功能与UI分析.md:312`；该结果仅支持这组已采样场景，不证明所有预算结转、投资、单账户存钱或结清差额规则。实现前应冻结上述已明确规则；设计与代码审阅都要逐项核对，不能用“页已打开”替代金额、迁移和故障测试。

## 9. 交付复核

本调查未运行 build、ADB、迁移或测试，所有“必须证明”均为后续验收要求。业务所有权保持 core，数据库约束与索引在 storage，文件版本在 protocol/Android codec，UI 仅消费统一查询和命令。没有创建网络/登录/会员实现，没有触碰已存在的本地构建修改。使用的工程约束重点为真实序列化/旧 schema、业务责任最窄层、独立交叉审查及区分源码和运行证据。
