# DawnYew 技术方案

版本：1.0。日期：2026 年 10 月 18 日。范围：Android 首版的完整技术设计；iOS 只保留契约；文件模板只定义扩展入口。本文的性能、OCR 准确性和设备测试数值是验收门槛，不是已有成绩。

设计审查状态：三名专题 Agent 完成交叉审查与修复复核，未发现剩余阻断性设计矛盾；可据此按模块编制实现计划。G1–G5实际运行门槛尚未执行，不等同于产品发布通过。

推荐采用 **KMP 共享领域、用例、数据规则与主要 Compose UI，Android 承接认证、文件、OCR 和安全生命周期；Room KMP + BundledSQLiteDriver 存储业务数据**。账务写入采用单一事务入口；导入采用预览后原子新增，完整恢复采用候选数据库代际切换。截图识别选择可审计、随包交付的移动端 OCR 路线，不能下载模型或回退云端。

本文与[需求设计](2026-10-18-dawnyew-requirements-design.md)、[UI 规范](2026-10-18-dawnyew-ui-interaction-standard.md)共同构成设计输入。专题证据见 [KMP 与数据库选型](../../research/2026-10-18-kmp-storage-selection.md)、[离线 OCR 选型](../../research/2026-10-18-offline-ocr-selection.md)、[本地数据与安全设计](../../research/2026-10-18-local-data-security-design.md)。最终审查意见及处理结果见[交叉审查记录](2026-10-18-dawnyew-technical-design-review.md)。

## 1. 范围与决策

### 1.1 本轮范围

用户最新指令优先于此前阶段准入条件：

- 交付领域模型、共享层与平台层、数据库、交互状态、离线 OCR、数据协议、安全、验证和模块交付边界。
- iOS **只保留平台接口和平台无关规则**；不建 iOS 工程、不写 actual/Swift 适配、不执行 iOS 编译，不承诺已经兼容 iOS。此前要求本阶段做 iOS 最小编译验证的条款由此替代。
- 微信、支付宝及 DawnYew 自身模板暂不提供具体适配器，不冻结列名、工作表布局、文件编码、平台状态值或退款映射。保留能力入口、通用管线和测试契约；模板样本到位后独立设计适配器。这不取消最终 P0 的历史账单导入要求。
- 本轮不创建应用工程，不安装依赖、不实施 OCR 真机测试，也不把文档审查当作运行验证。后续可以按模块进入实现计划，不能将模板和 OCR 验收缺口等同于整个 P0 已完成。

产品始终免费、无账号、无云同步、无广告、无运行时网络；仅人民币、中文；用途账本没有银行余额、资产、负债和转账。默认不收集真实用户账单作为测试样本。

### 1.2 架构备选

| 方案 | 优点 | 代价 | 决策 |
| --- | --- | --- | --- |
| KMP 领域/数据/状态 + Compose 主要 UI + 平台能力端口 | 账务和界面规则复用；未来平台可替换适配器 | 需要管理 KMP 依赖兼容和平台生命周期边界 | **采用** |
| KMP 领域/数据 + 各平台原生 UI | 系统体验自由度高 | 将来重复建设完整 UI；本项目暂无复杂原生页面需求 | 后续个别系统界面仍原生，不作为整应用路线 |
| Android 原生全栈，再迁移 | 初期依赖最简单 | 后续重做协议、状态和业务边界的风险较高 | 不采用；Android 优先不等于 Android 类型进入领域模型 |

依赖注入采用应用组合根显式构造，无服务定位器、反射扫描或全局可变仓库。只需要单进程单用户，不引入服务器、同步队列、事件溯源框架或微服务。

### 1.3 Android 支持基线

产品最低版本选择 **Android 11 / API 30**，这是为了收敛强生物识别与设备凭据组合、存储及生命周期验收范围的产品决策，**不是 KMP/Room 自身最低要求**。Android 10 及以下暂不支持；若以后扩大覆盖，作为单独兼容任务评审。发布 ABI 为 arm64-v8a；x86_64 用于开发和自动化。原生 OCR、SQLite 与加密库均需验证 16KB 内存页兼容。

性能基准规定为一台固定 Android 12、6GB 内存、骁龙 778G 级别的真机，设备型号、系统构建号、电源状态及最终 APK SHA-256 必须记录在报告。OCR 增加另外两家厂商设备，至少一台无 GMS。当前没有已登记的实测设备；不得据此填写性能通过。依赖候选组合与验证步骤以数据库选型文档的版本表为准，禁止动态版本和 alpha/beta 隐式升级。

## 2. 模块和平台边界

### 2.1 模块依赖

以下为拟建 Gradle 模块，不表示仓库已经存在这些工程。

| 模块 | 职责 | 允许依赖 |
| --- | --- | --- |
| `shared:core` | 值对象、领域规则、用例、仓库及能力端口、错误类型 | Kotlin 标准库、coroutines、平台无关日期库 |
| `shared:storage` | Room 实体、DAO、迁移、仓库实现、读写事务与快照 | core、Room KMP、SQLite 抽象；不依赖 UI |
| `shared:protocol` | 备份/交换 DTO、版本升级、来源规范化、记录级验证 | core、kotlinx.serialization；不依赖 Android URI |
| `shared:presentation` | 页面状态、事件、导航参数、草稿确认、任务状态编排 | core、protocol 的公开模型、共享 ViewModel/StateFlow |
| `shared:ui` | Compose 页面、主题、图表绘制、可访问性语义 | presentation、稳定 Compose/M3；不直接访问 DAO |
| `platform:android` | SAF、图片解码、OCR、系统认证、锁屏、备份排除、流式文件编解码、加密和代际文件操作 | core/protocol 的端口、Android SDK、经审查的平台依赖 |
| `androidApp` | Activity、应用组合根、Manifest、资源及发布配置 | 组装上述模块，不承载金额或退款规则 |

数据方向为“UI 事件 → 用例 → 事务仓库/平台端口 → 领域结果 → StateFlow → UI”。`shared:core` 不依赖 Room 注解、Context、Uri、Bitmap、Activity、Keystore 或任何 OCR SDK 类型。只注册 Android target 与用于共享逻辑测试的 JVM target；不通过空壳 iOS 实现假装具备 iOS 支持。

数据库定义与查询可共享，文件路径及 Driver 创建由组合根提供。JSON 的字段、整数金额、迁移语义属于共享协议；大文件 token I/O 是可替换平台端口，不把 JVM 流解析 API 强塞进 commonMain。

### 2.2 能力端口

| 端口与主要操作 | 输入与输出契约 | Android 实现 | 后续 iOS 边界 |
| --- | --- | --- | --- |
| `LedgerRepository` / `LedgerTransaction` | 版本化命令、快照查询、原子结果 | Room KMP 仓库 | 复用规则，另提供数据库构造 |
| `ClockPort` / `IdGenerator` | 当前本地时间/UTC、随机 UUID | 系统时钟/安全随机 | 契约相同，不规定系统类型 |
| `TextNormalizationPort` | NFC规范化；共享层规定trim/大小写/计数与唯一性规则 | java.text.Normalizer，固定金样例 | 未来Unicode规范化实现，仅契约 |
| `FileAccessPort` | 选择、读取、写入、撤销授权；返回 opaque `DocumentHandle` | Activity Result + SAF，本地文件 | 未来文件选择器/安全作用域 URL |
| `ImageInputPort` | MIME/尺寸预检、限额解码、临时句柄 | ContentResolver + ImageDecoder | 未来原生图片解码 |
| `OcrEnginePort` | 图像句柄、取消；文字框/置信度/耗时/引擎版本或结构化错误 | 单实例本地 OCR | 只定义契约，无 Vision/SDK 适配 |
| `AuthPort` | 能力查询、指定原因认证、取消；成功/取消/锁定/不可用 | AndroidX BiometricPrompt | 未来 LocalAuthentication |
| `SecuritySessionPort` | 会话代号、操作许可、锁定与设备配置异常 | 生命周期协调器、独立安全设置 | 同语义，不共享 Activity 生命周期 |
| `JsonStreamPort` / `TabularCodecPort` | 有界 token/行流；不做平台账单语义映射 | Jackson-core 流式 JSON；受限 OOXML codec | 未来流式实现；相同金样例 |
| `BackupCryptoPort` | 口令到认证流、完整性结果、取消 | libsodium 平台包装 | 未来兼容同一公开容器协议 |
| `DatabaseGenerationPort` | 候选代、核验、保护副本、原子激活和中断恢复 | 私有目录 + journal + fsync + 原子指针 | 未来文件系统实现 |
| `SecureWindowPort` / `BackupExclusionPort` | 遮盖、任务预览保护、排除自动备份 | FLAG_SECURE、CE/noBackup、备份规则 | 未来系统等价能力，不宣称完全一致 |

错误用密封类型区分 `Validation`、`Conflict`、`Locked`、`PermissionLost`、`UnsupportedFormat`、`CorruptData`、`NoSpace`、`Cancelled`、`EngineUnavailable`、`StorageFailure`，界面不直接展示异常堆栈。能力不可用不等于操作成功。

### 2.3 并发与任务归属

仅组合根持有 `DatabaseCoordinator`。普通命令在其写事务入口串行执行；读取使用 Room 事务快照；导入/恢复/清空/数据库升级取得排他维护许可。不得在授权期间开启第二个后台写库进程。所有命令携带 `databaseEpoch`、目标 `revision`、`operationId`；切换数据库代、清空或锁定后，旧回调没有提交资格。

短查询可取消；OCR 一次一个任务；文件解析在专用 IO dispatcher，OCR 使用受限 CPU dispatcher，不在主线程做 KDF/解码/查询。长期任务只在前台操作会话运行，首版不引入后台定时任务或 WorkManager。普通后台切换先遮盖、取消可取消工作；已进入 SQLite COMMIT 或代际指针切换的短临界区必须完成或回滚，之后保持锁定。回到应用通过持久操作回执判断结果，不能再盲目提交一次。

## 3. 领域模型与不变量

### 3.1 值对象

| 对象 | 表示与约束 |
| --- | --- |
| `EntityId` | 小写规范 UUID 字符串，随机生成；名称不是主键；全部业务记录身份在备份中保持 |
| `Money` | `Long` 整数分；币种固定 CNY；单笔 `1..99_999_999_999` 分；金额解析使用十进制字符，不经 Float/Double；加减乘检查溢出 |
| `Occurrence` | `localDate=YYYY-MM-DD`、`localTime=HH:mm:ss`；保留录入时 `zoneId`/offset 仅用于解释，不据此重算所属月份；UTC 创建/修改时间另存 |
| `DateRange` | 起止日期均包含；月/年生成确定边界；不接受 start>end |
| `Revision` | 每次业务变更加一，编辑、移动、删除、恢复均变更；用于并发校验和撤销资格 |
| `QueryScope` | 具体账本 ID 集合、归档包含标志、期间、类型集合、分类 ID、备注关键词；不可使用展示名称关联 |
| `CommandId` | 每次用户明确发起操作生成一次 UUID，重试复用；不同操作不得复用 |

本地日期采用 ISO 公历，年份 0001–9999；本地时间到秒。手动录入与新候选不得晚于当前设备本地时间；既存记录不会因时区变化自动变为非法或移动日期。恢复验证日期结构与退款先后，不按新设备的“现在”重写历史。导入缺失/模糊时间由适配器声明精度与待确认项，不能猜测。退款先后比较存储的本地日期时间；跨时区历史由用户主动纠正，而非隐式换算。

名称 trim 后以 NFC 规范化并按 Unicode code point 计数：账本 1–30、分类 1–20、备注 ≤500；中文、emoji 不按 UTF-16 单元误截断。唯一名称键使用同一规范化结果、区分大小写；排序字段可更新但不改变身份。协议导入也执行相同规则。

### 3.2 实体与关系

| 实体 | 核心字段 | 关系与用途 |
| --- | --- | --- |
| `Ledger` | id、name/key、iconKey、sortOrder、archived、currency、revision | 至少一本可用；归档后禁止新增/修改/退款/接收移动，历史可读 |
| `Category` | id、ledgerId、direction(INCOME/EXPENSE)、name/key、enabled、order、revision | 同账本同方向唯一名称；被有效或回收站记录引用不能删除 |
| `Entry` | id、ledgerId、kind、amountMinor、categoryId、occurrence、note、parentExpenseId、deletionGroupId、revision、createdAt、updatedAt、originKind、creationOperationId、captureFingerprint?、captureEngineVersion? | kind 为 INCOME/EXPENSE/REFUND；来源为 MANUAL/OCR/IMPORT；退款关联支出；无负金额流水 |
| `DeletionGroup` | id、deletedAt、reason、rootEntryId? | 成组软删/恢复的操作身份，成员由 Entry 的 groupId 决定 |
| `MonthlyBudget` | ledgerId、yearMonth、amountMinor、revision | 联合主键；正数；月间不继承、不分配资金 |
| `SourceIdentity` | id、entryId、provider、scope、eventKind、externalId、normalizationVersion、canonicalSource、sourceDigest | 来源身份独立于本地归属与内容；source 原始规范化值不随用户编辑改变；eventKind 必须是来源协议中不可变的事件命名空间，不由可变交易状态或收支映射推导 |
| `ImportBatch` | id、submissionId、adapterId/version、fileDigest、previewDigest、mappingDigest、counts、impact、createdAt、status | submissionId为本次确认的持久幂等键，随备份保留；记录预览与提交结果，不保留整份原文件或账户凭据 |
| `ImportMember` | batchId、entryId、committedRevision、committedContentDigest、state | 撤销依据；永久删除后保留已移除标记，不伪造实体引用 |
| `OperationReceipt` | operationId、commandType、payloadDigest、resultIds、committedAt | 与业务变更同事务，防双击/恢复回调重复执行 |
| `DatabaseMeta` | datasetId、schemaVersion、contentVersion | 业务数据集身份与提交版本；物理 generation/epoch 另属本机协调器 |
| `UserPreference` | theme、lastLedgerId、lastPeriod、lastBackupAt | 可迁移偏好；引用不存在时用默认账本 |
| `CaptureDraft` | draftId、imageHandle、candidates、warnings、confirmation、operationId | 仅会话内，未确认不入账，不参与任何汇总 |

`SecurityState`、文件授权、临时图片、解锁会话、Keystore 凭据不是业务备份实体。截图只保留可选图片 SHA-256 与识别引擎版本作疑似重复证据，不保留图片、完整 OCR 文本或置信度日志；图片摘要不能证明两笔账单相同。

### 3.3 退款、移动与回收站

`ExpenseAggregate` 是一笔支出与其退款的业务一致性边界，不要求把所有退款永久加载到 UI。

1. 有效退款要求原支出有效、同账本/分类、发生时间不早于原支出，累计有效退款 ≤ 原金额。新退款与修改原支出均在写事务内重读和验证，避免两个并发退款各自通过。
2. 修改原支出分类、跨账本移动时更新原支出和所有**有效**退款；所有成员 revision 增加。此前已删除退款保留关联 ID 和删除组，恢复时继承原支出的当前账本/分类。退款不单独移动；有任意退款关联（含回收站）时，原支出不允许改为收入。
3. 删除支出仅把支出及当前有效退款放入新的同一组；此前删除的退款不换组。单删退款只影响自身。恢复一个删除组时，按当前有效数据重验全组，任一失败则全部不变。
4. 永久清理仅可选回收站记录。选原支出时显式列出全部关联退款并一起清理；选退款只删除退款，不反向删原支出。删除相应来源身份/图片摘要，批次成员改为 REMOVED；再次导入此记录可成为新增，不保留“永久清理后仍阻止导入”的隐形墓碑。
5. 空账本删除前检查有效与回收站流水、预算和分类引用；仅空业务记录账本可删除，先确认一并移除其预算和无引用分类；不可删除最后一本可用账本。归档是含流水账本的常规处理方式。

停用分类在历史查看中有效；编辑未改变分类时可保留原停用分类，新增/改选/移动不能选停用分类；恢复旧记录可恢复到仍存在的停用分类，因为恢复不是新分类选择。归档账本必须先取消归档才能恢复或修改。

### 3.4 原子命令

| 命令 | 事务内必须完成 | 失败行为 |
| --- | --- | --- |
| SaveEntry / EditEntry | 校验归属、金额、时间、revision、退款上限；写记录+回执+contentVersion | 保留表单，显示字段错误或版本冲突 |
| AddRefund | 重读原支出及有效退款总额；写退款+回执 | 超额/过期上下文整笔拒绝 |
| MoveExpenseGroup | 验证目标可用、分类一致；原支出和有效退款一起改归属/revision | 全组不动，全部账本总额恒定 |
| SoftDelete / RestoreGroup | 划分/读取确切删除组；完整验证后一次变更 | 不部分恢复；单独删除旧退款不自动复活 |
| Purge | 校验回收站、展开级联范围；删除身份；更新批次存续状态 | 不能从有效列表直接永久删 |
| CommitImport | 重验预览版本/重复/关联；创建明确选择的容器、来源和流水、批次 | 任一步失败全批回滚 |
| UndoImport | 检查全部成员未变且无批外退款；删本批流水/身份、标记撤销 | 任一不合格拒绝整批；账本和分类保留 |
| SetBudget / ArchiveLedger | 正数/唯一月或最后一本检查与版本更新 | 不通过则原值不变 |

UI 的禁用按钮只是反馈；数据库唯一键与 OperationReceipt 才是幂等保障。同 operationId 和同 payload 返回原回执；同 ID 不同 payload 是冲突。持久草稿提交 ID 至少在 Entry 的唯一 `creationOperationId` 中保留，进程死亡后结果不明可查询。回执不含完整备注，不用于恢复旧界面敏感内容；全库恢复后旧设备 epoch 的命令一律失效。

## 4. 数据库设计

### 4.1 选型结论

采用 **Room 2.x KMP + BundledSQLiteDriver**，不直接复制最新文档中的 Room 3 API。Room 提供声明式 DAO、迁移与 Flow，BundledSQLiteDriver 降低厂商系统 SQLite 差异；仍需实测事务和迁移。SQLDelight 是合理备选，优势在 SQL 主导与查询生成；当前项目采用 Room 的原因是 Android 生命周期/工具生态和后续共享存储边界，而非断言 Room 性能优于 SQLDelight。详细版本、许可与源证据见专项选型文档。

本版不使用 SQLCipher。数据库位于应用私有凭据加密存储（CE），依赖 Android 沙箱与设备文件加密；应用锁限制应用入口，**不额外加密 SQLite 文件**。Root、设备解锁后的沙箱绕过、调试备份和物理取证不在应用锁保证范围；如未来要求独立数据库密钥，必须新增密钥生命周期、恢复与驱动组合评审，不能仅加依赖后宣称完成。

### 4.2 表、约束和索引

字段以 3.2 的领域表为基准映射为 snake_case。金额/版本为 INTEGER，布尔为 0/1，日期时间为规范文本，ID 为 TEXT；禁止 REAL 存账务金额。Room Entity、DDL 与 JSON DTO 分离。

| 表 | 数据库强约束 | 关键索引 |
| --- | --- | --- |
| ledger | PK id；UNIQUE name_key；currency=CNY；revision>0 | archived,sort_order,id |
| category | PK id；FK ledger RESTRICT；UNIQUE(ledger_id,direction,name_key)；UNIQUE(id,ledger_id,direction) | ledger_id,direction,enabled,sort_order |
| entry | PK id；金额范围；kind 枚举；FK ledger/category RESTRICT；parent FK entry RESTRICT；creation_operation_id UNIQUE | (ledger_id,local_date,local_time,id)、(category_id,local_date)、parent_expense_id、deletion_group_id、import_batch_id |
| deletion_group | PK id；root_entry_id 为审计标识，不强制指向仍存在记录 | deleted_at |
| monthly_budget | PK(ledger_id,year_month)；FK ledger RESTRICT；金额>0 | 主键覆盖 |
| source_identity | PK id；entry FK CASCADE；UNIQUE(provider,scope,event_kind,external_id) | entry_id；source_digest 仅辅助比较 |
| import_batch / import_member | batch PK；submission_id UNIQUE NOT NULL；成员 PK(batch_id,entry_id)；entry_id 为历史标识，允许 REMOVED | entry_id、batch_id,state |
| operation_receipt | operation_id PK；命令/摘要不可变 | committed_at |
| database_meta / user_preference | 单行 meta；已知偏好键唯一 | 无敏感安全偏好 |

Entry 另存 `category_direction`，收入为 INCOME，支出/退款为 EXPENSE；用 `(category_id,ledger_id,category_direction)` 复合外键保证归属。Room 生成 FK/UNIQUE；金额范围、kind/direction、布尔等表内断言通过显式 BEFORE INSERT/UPDATE trigger 执行 RAISE(ABORT)，在创建及每条迁移路径维护并检查，不能假设已有表可以 ADD CHECK。上表“数据库强约束”包括这些触发器。parent 必须为支出、有效退款同账本/分类及合计上限是跨行规则，由写事务统一验证，不误称普通 CHECK 能实现聚合约束。回收站退款仅强制父记录存在并仍为支出，恢复时才验证当前归属与余额。

SQLite 启用 foreign_keys、WAL，所有写连接 `synchronous=FULL`，测试逐个核验连接池实际writer配置，不能只信一次初始化回调；保存成功只在事务提交返回后发出。不能为性能目标降为 OFF。WAL 下 FULL 提供比 NORMAL 更强的掉电持久性，最终仍依赖系统文件同步能力。[SQLite PRAGMA](https://www.sqlite.org/pragma.html)

### 4.3 快照、分页与汇总

一份 `StatisticsSnapshot` 的摘要、各图和对应表格在同一读事务计算，携带 `contentVersion` 与 QueryScope。不能把若干独立 Flow 的最新值拼成一张图。写入成功增加 contentVersion 触发重算；不同筛选用 latest-wins 取消前一次查询，旧结果不能覆盖新筛选。

流水按 `localDate DESC,localTime DESC,id DESC` 使用 keyset 分页，每页 50 条；查询版本改变则重新加载并保留可恢复的滚动锚点。备注搜索以参数化 LIKE 处理并转义 `%`、`_`，v1 不引入全文索引。1 万条是性能数据集，不是数据库总容量限制。

汇总只取 deletion_group_id 为空记录。整数 SUM 溢出必须转为明确的统计不可计算错误；不要改用 SQLite total() 或 Double 绕过。进入 UI 绘图前允许将已检查的整数转换为坐标浮点数，所有标签、百分比文本和数据表继续用整数/确定舍入；百分比显示四舍五入到两位小数，比例乘法先作溢出安全计算。

### 4.4 迁移和损坏处理

禁止 destructive migration、异常时删库重建、从未来版本降级打开。每个已发布 schema 都留实体快照和迁移金样例。升级在候选 generation 进行：关闭写入、取得经 checkpoint/关闭的旧库保护副本、复制候选、按版本迁移、foreign_key_check/integrity_check、业务关系与合计核验，通过后切换。失败保留原代；新程序若不支持读取旧 schema，展示本地恢复/重试说明，不伪称能继续正常使用。

任何复制都不得单独复制仍有写入的 `.db` 文件；WAL/SHM 状态由协调器处理。运行库损坏时禁止继续写入，提供从用户备份恢复的受控入口，错误日志不含账单内容。完整恢复与内部保护副本的状态机见第 8 节及安全专题。

## 5. 统计和预算协议

`StatisticsQuery(scope,range,granularity)` 返回 income、grossExpense、refund、netExpense、surplus、trend、categoryShares、ledgerComparison、budget? 和同版本数据表。公式分别是收入合计、支出合计、退款合计、支出−退款、收入−净支出；预算 used=max(netExpense,0)，remaining=budget−used。

| 视图 | 查询语义 | 下钻契约 |
| --- | --- | --- |
| 趋势 | 月按日、年按月；自定义≤90天按日，否则按月，首尾桶按实际范围裁切；收入/净支出可正可负 | 传当前完整筛选+桶的闭合日期范围，净支出明细保留支出及退款 |
| 分类占比 | 仅原始支出，按(ledgerId,categoryId)分组；退款另列 | 原始支出切片仅下钻 EXPENSE；不能用净支出明细冒充同一饼图总额 |
| 账本比较 | 展示各本原始支出、退款、净支出；同名分类不跨本合并 | 传选中 ledgerId 与同期间/筛选 |
| 预算 | 仅单一本、完整自然月、无类型/分类/备注筛选；全部账本及自定义非整月隐藏 | 显示完整月口径；超100%显示真实百分比及超支 |
| 无数据/仅收入 | 真正空数据不绘样例；仅收入显示收入趋势而支出图为空 | 提供记账入口或去掉筛选 |

已发生的空日期填 0；今天之后的日期/月份为 null 并断开绘线，不补未来零消费。已有因设备时区变化落到“本地今天之后”的历史记录仍展示其真实值并标记日期异常，不静默丢弃。每个图与等价表使用同一 series，不重复计算；下钻期间若库变更则按新版本刷新并提示“数据已更新”，返回保留之前的筛选、期间及滚动位置，不承诺跨多个页面永久冻结旧库。

图表使用共享 Compose Canvas 绘制有限的线图/柱图/环形图，避免 Android View 图表阻碍共享。金额计算、聚合及点击命中模型与渲染分离。首版不做缩放、拖动分析或复杂动画，线/柱以最近数据点映射下钻；每个点提供可聚焦的等价表行，不能只给整张 Canvas 一句 contentDescription。[Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)

## 6. 交互与状态设计

### 6.1 共享状态与 Android 系统交互

共享页面使用不可变 UiState + 用户事件 + 单次 effect；StateFlow 是唯一可观察状态。ViewModel 调用用例，不持有 Activity/Uri。导航参数只传 ID、查询范围和任务句柄，不传 Bitmap、账单全文或口令。Android 管理系统返回、键盘/inset、认证和文件选择；页面不自行弹第二个系统认证窗口。

三主导航为明细、统计、设置；按各页保存筛选上下文，账本切换保留当前目的地。“全部账本”录入必须先明确真实账本。窗口小于600dp使用单栏；600dp及以上优先导航栏/栏轨切换与内容最大宽度，而非按设备型号区分。大字号不固定卡片高度，360dp、200%字号、横屏及键盘打开时优先可滚动并保持主操作可达。颜色、字号、间距遵循现有 UI 规范，不新增另一套主题。

### 6.2 页面状态矩阵

| 页面/任务 | 正常状态 | 必须处理的分支 | 退出与保存 |
| --- | --- | --- | --- |
| 明细/统计 | Loading→Content/Empty | 查询失败、筛选无结果、归档范围、负净支出、数据更新 | 切页保留上下文；首次无数据不伪造金额 |
| 录入/编辑 | Editing→Validating→Saving→Saved | 字段错误、退款约束、revision冲突、磁盘满 | 未提交返回询问放弃；失败保留输入；提交后按回执返回 |
| 退款 | 展示原支出与可退余额→填写→确认 | 原支出已变、日期早于消费、超额、归档 | 不允许输入页绕过领域验证 |
| 移动 | 选目标账本→目标分类→显示关联数量→提交 | 目标归档/分类停用/整组版本变化 | 全组成功后两个账本刷新 |
| 回收站 | 删除组列表→影响预览→恢复/永久清理 | 原支出失效、超退、账本归档、级联范围 | 恢复失败显示具体原因，不恢复一半 |
| 账本/分类/预算 | 编辑→校验→提交 | 重名、最后一本、引用中分类、非正预算 | 归档有显式确认；无预算与零预算不同 |
| 文件导入 | Selecting→Inspecting→Mapping→Preview→Committing→Result | 无适配器、文件变更、重复、冲突、未知状态、取消、超限 | 预览不改正式库；commit后只显示一次回执 |
| 完整恢复 | Selecting→AuthenticatingFile→Verifying→Preview→Reauth→Replacing→Result | 口令错、未来格式、缺空间、失去许可、中断恢复 | 显示将替换全部数据；切换前可取消，切换临界区等待明确结果 |
| 导出/备份 | Scope→Reauth?→Destination→Writing→VerifyingOutput→Result | 许可丢失、空间不足、provider写入失败 | 不完整文件标为失败并尽力删除，不能提示导出成功 |
| OCR | Selecting/Receiving→Gate→Recognizing→ReviewDraft→Saving→Saved | 错格式/超大图/多金额/不支持/低置信度/疑似重复 | 任何结果均需确认，取消不入账 |
| 应用锁 | Disabled/Locked/Authenticating/Unlocked | 取消、失败、锁定、凭据移除、外部入口 | 失败保持遮盖；敏感操作使用新的操作许可 |

上述矩阵补齐接口层行为，不替代尚未完成的 Figma B/C 详细页面；相关页面实现前应补关键正常/失败/取消交互稿。现有批次 A 已交付状态保持不变，本轮不修改 Figma。

### 6.3 防重复与进程恢复

表单金额和备注可保存在当前 ViewModel 内存以承受旋转；不写 SavedStateHandle/系统 Bundle，避免敏感草稿进入系统快照。普通后台锁定先清 UI 可见内容，内存草稿留在进程内但不可显示；进程死亡草稿不恢复，重新进入说明未保存。保存前 creationOperationId 先写入最小非敏感 pending 标记；事务完成生成回执，重启解锁后按 ID 查询结果而非重新构造账单。若没有回执即未保存，用户重新录入。截图草稿也使用相同提交机制。

导入预览可保留至本次前台会话；后台/进程死亡清理源文件缓存和映射暂存，重新选择，不自动重试外部文件。已提交批次由正式表显示；导入撤销必须重新计算资格，不能缓存一个永远有效的“可撤销”按钮。

读屏用文字说明收入/支出/退款与金额，不靠颜色；错误与字段关联；图表提供表格；数值键盘仅辅助，粘贴仍执行完整金额校验。密码输入禁止日志和明文持久化；应用不承诺控制第三方输入法自己的云能力，也不以输入法语音作为离线功能。

## 7. 模板入口与通用导入导出

### 7.1 本轮只冻结协议，不冻结模板

注册表内声明 `WECHAT_HISTORY`、`ALIPAY_HISTORY`、`DAWNYEW_TABLE` 三类能力，但在适配器未登记前状态是 `AwaitingTemplate`。产品入口显示“模板确认后开放”，不会选文件后才静默失败，更不会显示已经支持历史账单。通用框架可以用合成测试适配器验收，该适配器不进入生产注册表。

适配器描述符包含 `adapterId`、version、允许容器/MIME、来源范围规则版本、probe/parse 接口、支持能力及金样例集。`probe` 返回 ExactMatch、Unsupported 或 Ambiguous，不用“猜中概率最高”自动导入。多个精确匹配同样要求选择或视为冲突。适配器只输出候选行与来源证据，无权直接写 DAO、联网、执行工作簿公式或绕过应用锁。

| 契约 | 输出内容 |
| --- | --- |
| `inspect(DocumentFacts, boundedInput)` | 支持的适配器候选、容器事实、编码/日期精度是否明确；不猜列名 |
| `parse(RowStream)` | `Candidate`、`Excluded(reason)`、`NeedsDecision`、`Invalid(reason)` 四种逐行结果，保留原行定位 |
| `normalize(Candidate, MappingPlan)` | 规范化收支/退款事件、SourceIdentity?、依赖、字段确认状态 |
| `preview(SelectedRows, DbSnapshot)` | 确定新增/重复/疑似重复/冲突/排除/错误计数、合计、依赖图与 previewVersion |
| `commit(PreviewToken, decisions, commandId)` | 全部再次验证后原子提交，或返回需重新预览 |
| `write(ExchangeRecordStream, TemplateDescriptor)` | 真正 XLSX；模板 descriptor 未定则该业务导出入口也不宣称就绪 |

微信/支付宝的文件容器可能不同，不能把平台文件强行送进 XLSX codec。后续提交模板时必须包含脱敏样本、版本、编码、列语义、状态集合、来源唯一性范围、金额/日期/退款解释和预期结果；不自动要求平台账号权限。

### 7.2 身份和去重

去重按下面顺序进行，摘要只作快速定位，最终比较规范化字段；不能将哈希相等当作无需校验的授权。

1. 有 DawnYew ID：与库中记录及同批候选比较。业务内容和保留来源信息一致则重复；相同 ID 不同内容为冲突，不覆盖。createdAt、updatedAt、内部 revision 不作为用户表格的业务内容判据。
2. 无匹配本地 ID、有经模板验证的强来源身份：比较 `(provider,scope,eventKind,externalId)`。相同 identity 且 canonicalSource 未变，跳过并保留本地改名、改分类、金额修正或移动；source 金额/时间/状态等变更为冲突，不生成新记录。normalizationVersion 变化须能无损迁移旧规范化值，否则要求重新评审，不能自动换身份。
3. 同批也执行前两种比较，重复行只保留一个候选；同身份不同内容整组标冲突。若本地 ID 指向 A、来源身份指向 B，标身份矛盾，不自动合并。
4. 身份无法证明唯一或已有手动/截图同日同额记录：仅为疑似重复，由用户选保留/跳过。图片摘要相同也不能自动删除候选。
5. 回收站记录占用同样的身份；显示“已在回收站”，不重复新增、不自动复活。永久清理/撤销移除身份后再导入可以新增，确认页说明这一行为。

scope 是可备份的来源命名空间，如何从来源文件得到由未来适配器定义；不能拿设备 ID、导入文件路径、用途账本 ID 或本次随机批次号作为 scope。来源信息随 JSON 和自身表格的版本化元数据保留；模板删掉元数据或来源无法验证时降为疑似重复，不承诺确定去重。

### 7.3 预览和提交

原文件经授权后复制到私有临时区并计算 SHA-256；后续解析这份固定快照，避免 provider 在预览与提交之间换内容。预览库是可删除 staging，不污染正式账目。统一映射账本/分类，缺失容器的“新建”只是计划，正式提交事务中才创建。

预览覆盖每个解析出的物理行/说明行，显示分类计数之和及影响金额；不能只展示成功行。无法确定是真实收付款还是资金移动的行进入待处理，不将平台“转账”统一收入或统一排除。退款必须关联同批选中的支出或现有有效支出；用户剔除父行后重新校验子行。仅累计退款状态不生成虚构日期的退款流水。

提交前检查文件快照摘要、适配器版本、mappingDigest、selectedRowsDigest、databaseEpoch/contentVersion 与认证许可；任何影响预览的数据变化均重新预览。事务内再次校验强身份、UUID、退款上限与 revision，然后写容器、流水、来源、批次、成员、回执；commit后才报告成功。取消在commit前回滚，commit后通过结果回执展示已完成，不假装撤销。

撤销只允许整个批次所有仍存成员的 revision/contentDigest 等于提交值，且没有批次外退款引用。任一编辑、移动、软删、恢复、永久清理即拒绝整批撤销。成功只物理移除本批新流水及其来源，保留创建过的账本分类，标记 UNDONE。重复撤销读取原结果，不重复减少合计。

### 7.4 XLSX 编解码选择

采用 **Android 平台内的受限 OOXML codec**：JDK ZIP + Android XmlPullParser/XmlSerializer，处理工作簿关系、工作表、sharedStrings/inline strings、数值及明确文本。业务模板映射与 codec 分开。该选择是本项目为可控输入和离线环境作出的工程决策，承担格式测试成本，不声称支持任意 Excel 文件。

没有选择直接引入完整 Apache POI 或把 Java fastexcel 当成已验证 Android/KMP 库：fastexcel 是 Java8+ 方案，reader 使用 StAX，InputStream 构造路径还会把压缩文件整体载入内存；Android可用性与依赖需要额外验证。这里需要有限的流水交换，不需要图表、计算引擎和复杂排版。[fastexcel 官方源码](https://github.com/dhatim/fastexcel/blob/master/fastexcel-reader/src/main/java/org/dhatim/fastexcel/reader/ReadableWorkbook.java)

文件是实际 ZIP/OPC 工作簿，写入必需的 content types、relationships、workbook、worksheet 和 styles；文本使用显式字符串类型，金额用精确十进制数值单元格，日期建议明确文本且由模板最终确认。`=、+、-、@` 开头的备注仍写成文本，不附公式。导出应由 Excel 与 LibreOffice 打开并逐项核对，不能仅用自己的 parser 自证兼容。[ECMA-376](https://ecma-international.org/publications-and-standards/standards/ecma-376/)、[SpreadsheetML 结构](https://learn.microsoft.com/en-us/office/open-xml/spreadsheet/structure-of-a-spreadsheetml-document)

导入拒绝公式 `<f>`、外部关系、宏、嵌入对象、加密工作簿、DTD/实体扩展、路径穿越、重复 ZIP entry 和非法/含糊单元格类型；不计算或使用公式缓存值。数值先保留十进制字面量，用精确十进制转换到分；科学计数法仅在可无损还原且符合字段规则时接受，不接受隐式四舍五入。日期样式/1900与1904系统只作为事实传给适配器；未确认语义不猜日期。自身模板格式限制不提前限制未来平台原始文件格式。

交换文件导入默认限制：原文件≤20MiB、总解压≤100MiB、流水≤10,000、ZIP entries≤256、XML深度≤32、单格文本≤32Ki字符、列≤128；字节数按实际读出的字节累计而非相信ZIP头。每块可取消，错误行和摘要有界分页。未来真实模板若不符合限额须先评审限额与性能，不静默截断；自身导出如超过单文件可再导入范围，提示分批或用完整JSON备份，不生成“成功导出却无法完整导入”的文件。模板和 codec 使用恶意文件与跨工具金样例独立验收。

输入预算的完整权威表为安全专题第10节：同时执行单文本UTF-8≤64KiB、单XML成员≤32MiB、压缩比及对象字段数量限额；这里的概述不放宽其限制。输出codec执行相同可再导入约束，不产生超限成功文件。

## 8. 完整数据协议、备份和恢复

### 8.1 JSON 业务协议 v1

`.json` 为 UTF-8，根对象包含 `format="dawnyew-backup"`、`formatVersion=1`、`datasetId`、`createdAt`、`entities` 和 `manifest`。formatVersion 与数据库 schemaVersion 独立，外部文件不能指定执行SQL或数据库路径。entities 是按实体类型分组的数组：ledgers、categories、entries、deletionGroups、budgets、sourceIdentities、importBatches、importMembers、preferences；实体完整字段映射 3.2，必要的 creationOperationId 和版本也保留。设备临时回执、会话、图片和安全设置不导出。

金额统一为十进制整数字符串 `"3250"` 表示32.50元；非金额 Long、计数和 revision 也使用规范整数字符串避免大数精度损失。固定版本号、布尔用JSON原生类型，时间/UUID为字符串。无浮点金额、NaN、Infinity、重复对象字段/ID、无效UTF-8、未知枚举、悬空引用。输出稳定排序；输入字段顺序不影响解释，数组可任意排列，先写候选staging再核验外键。

manifest 包含 `canonicalizationVersion=1`、各类型 `counts` 和 `sha256`。摘要输入先是 `JCS({format,formatVersion,datasetId,createdAt,canonicalizationVersion,counts}) + LF`，随后为每条 `JCS({entityType,record}) + LF` 的 UTF-8 字节，流式计算 SHA-256；sha256字段自身不参与。实体类型按上面固定次序，各类型按主键规范字符串排序，复合键按各字段依次比较。所有业务字段和关键元数据均参与，Money/Long为字符串，因此不受JCS浮点限制。字符串校验先于摘要，不静默改写非规范名称；记录级缓冲有界，排序由候选数据库完成。commonMain提供受限schema的canonical writer和金向量，遵守JCS的UTF-16属性排序、转义及孤立代理项拒绝规则，不能直接把Jackson默认输出当JCS。[RFC 8785](https://www.rfc-editor.org/rfc/rfc8785)

明文摘要用于发现损坏和核对一致性，**不是防恶意篡改的数字签名**；攻击者可重算摘要。保密与认证完整性由加密容器提供。导出前取得稳定只读快照或内部只读副本，多个数组必须来自同一 contentVersion；不跨几次异步查询拼备份。

旧版本通过共享迁移器转当前DTO，并保留原文件；未来formatVersion、未知实体或未知字段在v1严格拒绝，给“需要支持此格式的版本”提示，不跳过信息再覆盖当前库。v1没有真实历史数据库版本，后续每次发布必须补上一版到本版的金样例。未知UI偏好值可以拒绝整包或显式版本迁移处理，不随机采用部分恢复。

Android token层选 **Jackson-core 2.21.5**，仅streaming，不引入反射databind；每个record交给共享DTO/规则验证。配置严格重复字段检测、深度≤32、属性名≤128字符、字符串≤32Ki字符、数字token≤32字符，领域字段再收紧；禁日志回显原文。总记录数/文件字节无交换文件式硬上限，按空间和实际流式处理能力控制，取消不影响当前库。[Jackson-core 兼容与约束](https://github.com/FasterXML/jackson-core)

同时执行安全专题的UTF-8字节/对象字段配额。备份校验区分有效数据与历史状态：有效退款严格检查父支出有效、同归属、日期和累计上限；回收站退款可保留父支出移动或编辑前的旧归属/日期，恢复时再重验。已移除ImportMember.entryId与DeletionGroup.rootEntryId为审计标识可悬挂，退款parentId等强业务引用必须存在；不能把合法回收站或已清理批次备份误判为坏文件。

### 8.2 加密备份容器

默认推荐 `.dwybak`，解密后是完全相同的JSON协议，明文JSON仍独立可用。采用 **libsodium 1.0.22、Argon2id13（64MiB、opslimit=3、32字节派生密钥）和 secretstream XChaCha20-Poly1305**。每份备份新随机salt和stream header，64KiB明文块；严格校验协议头/参数白名单、认证标签、最终FINAL标记和EOF。不能只成功解密前几个块就认定整份备份有效。

容器字节布局、AAD、口令编码、随机数、零化、错误与恶意参数处理以[安全专题的容器协议](../../research/2026-10-18-local-data-security-design.md)为唯一详细定义，避免两份相似但不兼容的二进制格式。采用成熟AEAD原语，不自行实现密码算法。口令不保存，也没有找回服务；不使用当前设备Keystore密钥加密用户导出备份，因此未来设备可恢复。[libsodium secretstream](https://doc.libsodium.org/secret-key_cryptography/secretstream)

导出先生成并核验私有临时完整文件，再写用户选择的**新文件**；不原地覆盖用户唯一备份。SAF不能保证所有provider原子写入，故关闭输出流后重读核对摘要/长度或完成解密验证；不能重读验证则报告未核验，不记成功。失败尽力删除本次新建的不完整文件并说明外部残留；取消清理私有临时文件，不删除用户原始输入。

### 8.3 恢复和数据库代际切换

恢复不是表格追加。流程为：授权输入→流式解析/完整解密→候选库→结构/关联/金额/计数/摘要校验→预览→敏感操作重新认证→排他维护许可→当前库保护副本→核验候选→切换active指针→重开核验→完成。每一步均不读取备份里的应用锁状态。

物理目录为 app-private CE/noBackup 下的 `generations/<random-id>/`、`active` 指针、`maintenance-journal`、`staging/`、`protection/`。文件名由应用生成，不使用导入字符串作为路径。active指针和journal以写临时文件、fsync、同文件系统原子rename及目录fsync持久化；运行库连接关闭且WAL处理完成后才能切换，不能靠几次独立rename冒充数据库事务。

| 崩溃点 | 启动恢复决策 |
| --- | --- |
| 候选生成中/未通过校验 | 保持原代，清理未完成候选；无半份恢复 |
| 保护副本完成、active未切换 | 原代有效，保留保护副本；本次恢复未成功 |
| active已切换、完成标志未落盘 | 根据journal/active核验目标代；目标完整则完成既定切换，不能因用户没看到提示就重复导入 |
| 本次切换临界区目标代重开失败 | 仅在仍持维护屏障、尚未接收新代写入且未报告成功时，回退已核验旧代并报告失败 |
| 已COMMITTED的新代在以后启动时损坏 | 保持锁定并进入恢复维护页，让用户明确确认保护副本恢复；不能静默回退丢失成功切换后新增的账目 |
| journal/active均不可信 | 进入恢复错误界面，不自动选修改时间最新文件，不删现有数据 |

本机 SecurityState 保持原值；新安装不从备份启用旧设备锁，重新设置。切换后databaseEpoch更新，取消旧查询与旧回调并重建页面状态。当前库空间不足以建立保护副本或候选则拒绝切换；流中持续检查空间及捕获ENOSPC，没有用20MB/1万条交换上限拒绝完整备份。

保护副本保留期限、成功后的清理及清空策略详见安全专题。内部副本不等同于设备外备份，卸载和设备损坏仍可能丢失；逻辑删除也不承诺闪存物理安全擦除。

## 9. 应用锁与离线安全

### 9.1 锁状态和认证许可

SecurityState 独立保存于私有CE/noBackup，记录 `enabled`、配置版本及设备本地保护标记，不随业务JSON导入。Android11起使用 `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` 可用性检查与系统提示；启用/关闭应用锁都必须系统认证，不获取指纹/面部模板。没有能力、认证取消/失败/lockout保持锁定。[Android 生物识别指南](https://developer.android.com/identity/sign-in/biometric-auth)

冷启动先显示中性遮罩，再加载安全设置；若锁启用则业务内容尚不渲染。异常或安全配置损坏不当作默认disabled。成功解锁生成仅内存中的sessionId；普通离开应用立即失效并清可见内容，回前台重新认证。设备凭据被移除/配置变化需重新验证，不能用备份口令、重置按钮或内部深链绕过。

由应用主动开启的系统认证和文件选择登记为 `ExternalOperation(operationId,sessionId,kind,startedElapsedTime)`，返回时仅匹配同一操作的结果，保持遮盖直到协调器批准；旋转不等于离开应用。超过120秒、进程死亡、设备锁屏、可观察到的无关应用切换或来源不匹配均重新认证。系统picker遮挡时无法可靠观察每一次跨应用切换，因此用有界token、锁屏与返回身份检查保守判定，无法确认则重新认证；不声称能完整追踪其他应用。文件选择操作可以继续，但敏感导出/恢复/清空在最终执行前还需新的**一次性操作许可**；许可绑定operationId、databaseEpoch、内容/范围摘要，60秒有效，普通后台即失效，不能跨操作复用。文件picker返回不会无限触发普通解锁循环。

未启用应用锁时仍有操作范围确认；启用时明文导出、加密备份、替换恢复、清空、关闭锁要求新认证。应用锁设置与清空顺序见安全专题；不能在清理未完成时先关闭锁暴露残留数据。

### 9.2 外部入口

只有最小分享/文件接收Activity可导出，内部服务/provider默认不导出。验证Intent类型、单图数量、content URI、MIME和权限，不接受file/http/任意路径；不信任displayName/size声明。锁前只保留受限URI句柄和必要元数据，**不读取图片像素、不显示预览、不执行OCR**；解锁后失效权限提示重新选择。非persistable分享授权不强行持久化。

系统文件界面通过SAF授权，不申请MANAGE_EXTERNAL_STORAGE、读取全部相册、无障碍服务、通知监听、麦克风或持续截图权限。选图使用限制本地的文档选择；分享只处理用户主动选择的单张PNG/JPEG。原文件不删除。[SAF](https://developer.android.com/guide/topics/providers/document-provider)

### 9.3 零网络与数据排除

release合并清单无INTERNET、ACCESS_NETWORK_STATE，无广告/遥测/崩溃上传/更新检查/模型下载依赖；文本、图标、字体、模型、词典和帮助随包。依赖锁定、SBOM、模型校验和许可证清单进入构建产物。不要认为无INTERNET能替代SDK内部遥测代码审计。

账务库、WAL/SHM、偏好、密钥、临时图片、候选代和保护副本排除系统云备份和设备迁移备份。使用allowBackup=false并同时覆盖旧fullBackupContent和Android12+ dataExtractionRules；OEM差异需真机验证。敏感窗口启用FLAG_SECURE并在后台立即遮罩，Release不可debug；系统及root能力的边界在隐私说明写清。

SAF的`EXTRA_LOCAL_ONLY`只是请求，第三方provider可能自行联网；DawnYew不能保证其他应用实现。验收使用本机系统provider、至少一台无GMS设备；选择器返回远程/虚拟文档则拒绝。生产只接受按authority、provider包身份及本地root能力登记并经过目标设备验证的本地provider，名单随包；未知provider在读取内容前拒绝并引导先保存到已支持的系统本地位置。截图分享遵守同一边界，需在机型验收覆盖实际系统相册/截图来源。不能把云盘提供者发出的请求隐瞒为“全链路零网络”。

日志只记录错误码、操作阶段、schema/模型版本和非业务性能耗时；不写金额、备注、原文件名、原始流水号、完整OCR文字或口令。测试样本与生产数据隔离；调试导出日志需要用户主动操作并默认脱敏。

## 10. 离线 OCR 选择与处理管线

### 10.1 选择原则与主路线

广泛比较模型、运行时、包装层三个层次；**离线OCR不等于SDK零网络，也不等于账单语义正确**。详细候选、制品和许可来源见[专项研究](../../research/2026-10-18-offline-ocr-selection.md)。

主路线为 **PaddleOCR 官方 Android ONNX 路径 + 随包移动端模型 + 编译关闭遥测的 ONNX Runtime**。PP-OCRv5 mobile 是初始对照基准，PP-OCRv6 tiny 是较小包体挑战者；不在无实测时宣称v6更准确或直接替代。发布只打包胜出的一个引擎/模型组合，不为自动fallback同时装多套模型。

ML Kit bundled Chinese 的官方披露包含指标采集及服务器联系，因此不进入当前生产依赖；如作文本质量参考，仅限独立隔离评估工程，不能仅因模型bundled就通过离线门槛。RapidOCR可以作为替代封装对照，其ONNX运行时的遥测风险相同；Paddle Lite、Tesseract、ncnn及系统能力分别比较维护、移动性能和可控性。云OCR和服务器VLM不符合本项目；无障碍/通知监听不作为主记账路线，离线语音留P1。

ONNX Runtime v1.31.0 的官方隐私说明涵盖Android默认1DS遥测，运行时普通API关闭还可能晚于初始化事件。主路线要求以固定tag构建关闭遥测的制品，审计构建flags/依赖符号，并在创建任何environment之前设置允许的禁用开关作为纵深防护；不能直接使用默认Maven制品后宣称无网络。选型研究给出版本证据及替代条件。[ORT Privacy](https://github.com/microsoft/onnxruntime/blob/v1.31.0/docs/Privacy.md)

当前结论是**推荐工程路线和候选模型已选，最终发布模型需通过G2实测**。若主路线未过许可、构建、零请求或准确性门槛，则在同一测试集验证备选并重新记录ADR；不能偷偷切换在线识别或以手动录入代替OCR验收。

首个PoC组合已具体固定：官方SDK提交 `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`、ORT v1.31.0源码CPU构建并显式 `--no_telemetry`、OpenCV 4.14.0源码构建、专题4.1列出的v5 mobile模型revision；挑战组只按匹配配置更换v6 tiny。该组是可复现的验证起点，不是已构建生产锁版，详细依据及原生依赖约束见OCR研究第7节。

### 10.2 输入与识别

处理管线：外部/选图授权→应用锁→读头预检→私有临时副本→受限解码/旋转→文字检测/识别→文本块→纯共享字段解析→规则与候选→草稿→用户确认→SaveEntry。

输入单图≤20MiB、原始像素≤24MP、长边≤12,000像素，PNG/JPEG；超过提示裁剪/另选而非直接分配Bitmap。解码按目标尺寸降采样，基准最长边1920，保留坐标映射；是否额外局部裁剪重识别由样本验证决定，不能无限重试。最大输入规格与OCR性能测试分别覆盖，不能只测1080p便声称所有边界都达3秒。优先CPU，默认最多2线程，避免不同厂商GPU/NPU引入多套不可控结果。

引擎仅返回文本块、位置、可用置信度及方向；共享解析器识别单笔消费成功页/详情页，收支、状态与金额各自有证据。按版面位置和语义字段区分支付额、余额、优惠、订单总额。多金额无法确定时返回候选；模糊、退款、转账、失败支付、多笔列表或长图不自动填成确认的消费。不得以最大数字/第一个数字默认提交。

日期缺失可建议今天但标为待确认；分类/账本来自本地规则与用户当前选择，必须可修改，不按支付渠道决定用途。结果含 `Unconfirmed`、`Suggested`、`UserConfirmed` 来源状态。即使金额置信度很高，用户仍需点击明确保存；有未解决歧义时不得提交。OCR文字留内存，保存或取消清图片/识别结果，进程死亡残留在下次安全启动清理。

### 10.3 公平验证与选择规则

按需求固定240张：160张支持范围成功消费（微信/支付宝各80，深浅色/字号/分辨率），80张危险/不支持样本（多金额、退款、失败、转账、列表、模糊）。这240张全部是冻结验收集，开发调参另设独立样本；人工双标注、版本化脱敏清单。候选用相同输入、等价且符合各模型要求的预处理、同一parser和真机条件，不能各自挑最佳样本。

- 至少三厂商设备，每台160张金额完全匹配≥98%（至少157张）、类型≥99%（至少159张）；不以平均字符正确率替代。
- 80张风险样本必须全部进入标注的歧义/不支持/手动处理状态；将失败、退款、转账等高风险页无提示地显示为普通成功消费的次数为0。全部240张未经确认入账数为0，不能靠确认按钮掩盖危险分流错误。
- 全新安装首次断网可识别；联网时包括SDK初始化在内请求数为0；无GMS机同样通过。
- 1080p解锁后到可编辑草稿P95≤3秒，冷/热分别过门槛；包体与资源预算采用专项研究值：arm64 APK增量≤50MiB、安装增量≤100MiB、峰值PSS增量≤180MiB且总PSS≤300MiB、100轮释放后稳定增长≤20MiB。记录版本、ABI与16KB页，任何预算调整须以实测重新评审。
- 5人×20笔任务，含截图、分享、解锁、纠错的完整耗时相对手动中位数至少减少20%，最终账单全部正确。

任一候选未过硬门槛即淘汰。多个候选通过时优先完整流程耗时与错误纠正成本，其次稳定性、包体和维护负担；官方服务器benchmark仅作筛选参考。

## 11. 验证、交付与技术决策记录

### 11.1 验证层次和通过门槛

| 门槛 | 内容与证据 | 影响范围 |
| --- | --- | --- |
| G1 Android工程/存储 | 候选版本Release构建、Room/KSP/schema约束、所有writer连接PRAGMA、16KB页；共享业务JVM测试、迁移/事务故障注入 | 未过不能冻结工程依赖或数据实现 |
| G2 OCR | 10.3完整样本、三厂商、许可/制品SHA、无网络、资源、完整耗时 | 未过不能交付OCR或宣称最终最稳模型 |
| G3 数据协议与安全 | JSON>1万条且>20MB往返、加密固定向量/截断/篡改、恢复各崩溃点、清空中断、锁与外部入口 | 未过不能交付备份/恢复/应用锁 |
| G4 模板适配 | 用户确认样本/规则、三类模板金样例、退款/来源去重、真实XLSX互操作/恶意输入 | 当前按用户要求延期，阻止对应功能完成，不阻止其他模块设计 |
| G5 UI与性能 | B/C关键状态稿、实际360宽/200%字号/横屏/键盘/读屏；1万条冷启动P95≤2s、保存≤300ms、切月≤500ms各100次；可用性4/5用户成功 | 未过不能发布完整P0；不以Figma原型代替 |

共享规则重点用例为金额边界/溢出、退款合计并发、先删退款再删父单、移动后恢复旧退款、超额恢复拒绝、批次撤销与后续编辑、名称与Unicode、时区稳定。数据库测试使用真正选定的SQLite driver，不用Mock DAO代替事务测试。属性测试验证“移动前后全部账本总额恒定”“删除恢复在条件不变时可逆”“JSON往返业务投影一致”。

文件与安全测试覆盖每个提交/指针切换持久化点故障注入，ZIP/XML/JSON畸形输入、授权撤回、系统强杀、磁盘满、设备锁屏、认证失败/取消/配置改变。性能用Release，记录分位数原始样本与测量区间；当前未执行这些测试。

### 11.2 需求到设计追踪

| 需求 | 用例/交互 | 实现归属 | 验收 |
| --- | --- | --- | --- |
| FR-01 | 首次离线初始化/锁前中性页 | core、androidApp | AC-01、02 |
| FR-02 | 账本创建/切换/归档/最后一本 | core、storage、LedgerUiState | AC-03、04 |
| FR-03 | 分类维护、历史保留 | core、storage、CategoryUiState | AC-05 |
| FR-04 | 手动新增/编辑/重试回执 | core、EntryUiState | AC-06、07 |
| FR-05 | AddRefund/父单编辑 | core、storage写事务 | AC-08、09、22 |
| FR-06 | MoveExpenseGroup | core、MoveUiState | AC-10 |
| FR-07 | FilterScope/快照分页 | storage、presentation | AC-11、12 |
| FR-08 | StatisticsSnapshot/数据表/下钻 | core、storage、shared:ui图表 | AC-12、13、24 |
| FR-09 | SetBudget/月口径 | core、BudgetUiState | AC-14 |
| FR-10 | 加密备份/整体恢复 | protocol、crypto/generation ports | AC-15、16、36 |
| FR-11 | 适配注册/预览/原子新增/撤销/来源身份 | protocol、ImportUiState、storage | AC-17、25–27、35、37–41；适配器延期 |
| FR-12 | 删除组/恢复/清理/清空 | core、storage、maintenance | AC-18、19、22、23 |
| FR-13 | JSON协议/迁移/替换 | protocol、JsonStreamPort | AC-15、16、28、36 |
| FR-14 | Gate/强认证/外部入口/敏感操作 | AuthPort、SecuritySessionPort | AC-29–31 |
| FR-15 | 本地OCR/候选核对/唯一提交 | OcrEnginePort、core parser、DraftUiState | AC-32–34 |
| NFR-01、02、08 | 零请求/备份排除/隐私日志 | release清单、SDK审计、SecurityState | AC-01、02、29–34；G1–3 |
| NFR-03、04 | 事务、迁移、故障恢复 | storage、generation ports | AC-07、16、18、20、22、27 |
| NFR-05、06 | 性能/完整任务耗时 | benchmark、可用性试验 | AC-21；G5 |
| NFR-07 | 语义、字号、焦点、图表表格 | shared:ui、Android系统交互 | AC-24；G5 |
| NFR-09 | 无Android类型的领域/协议契约 | commonMain、共享金样例 | AC-12、15、28；本轮无iOS开发 |
| NFR-10 | 同集OCR与完整任务价值 | OCR评测资产和报告 | AC-32–34；G2 |
| NFR-11 | 有界流式文件处理/取消 | platform codec、protocol、staging | AC-35、36、41；G3–4 |

### 11.3 ADR 决策汇总

| 编号 | 决策 | 重新评审触发条件 |
| --- | --- | --- |
| ADR-01 | KMP共享规则/状态/主要UI；Android平台端口 | 平台专属交互无法满足体验或依赖无共享能力 |
| ADR-02 | Room2 KMP + bundled SQLite，WAL+FULL，单事务入口 | 兼容验证失败、迁移/约束实现不可靠 |
| ADR-03 | CE沙箱+应用锁；本版不做独立数据库加密 | 用户要求独立密钥保护或威胁边界变化 |
| ADR-04 | 整数分、本地发生时间、退款当期抵扣 | 多币种/预约记账/资金账户进入范围 |
| ADR-05 | 来源与本地身份分离；表格仅新增；模板注册但暂不实现 | 模板样本确认或需要合并更新已有账单 |
| ADR-06 | 公开JSON+libsodium口令加密；代际整体替换 | 协议兼容、密码库/文件系统实测失败 |
| ADR-07 | 用户主动单图OCR，官方Paddle ONNX+无遥测运行时为主路线 | 任一硬门槛失败；其他候选通过同集更优 |
| ADR-08 | API30起、arm64首发；iOS只保留接口 | 扩大设备覆盖或开始iOS产品开发 |
| ADR-09 | 共享有限图表；Android受限OOXML codec | 图表交互超出当前范围或模板要求不受支持的格式 |

### 11.4 后续模块交付顺序

先做Android依赖与存储/OCR/备份三个风险验证，再形成分模块实现计划：领域与数据库→手动记账/退款/回收站→统计与预算→JSON备份/应用锁→OCR闭环→模板确认后的适配器。可并行的前提是接口和金样例冻结；安全入口必须在接入外部分享/恢复之前落地。每个模块以FR/AC闭环验收，不以“页面画好”或“能编译”替代。

本轮交付是技术文档及交叉审查，不提交可安装产品。尚未执行的构建、设备与样本验证保留为明确G1–G5门槛；iOS开发和实际模板适配不被悄悄纳入本轮工作。
