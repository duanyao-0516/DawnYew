# 数据与共享层架构审阅（2026-10-25）

> 历史记录：小游戏接入决定已于 2026-10-26 被独立产品方案取代。DawnYew 已移除 2048 运行依赖、保留猜拳；本文当时的代码路径和游戏验收结果不代表当前工程状态。见[拆除记录](2026-10-26-games-detachment.md)。

## 结论与范围

本次审阅覆盖 `shared/core`、`shared/storage`、`shared/protocol`、`shared/presentation`，并沿调用链检查 Android 数据库代际、授权和悬浮提交的组合边界。审阅对象为分支 `p/duanyao.0516/codex/dawnyew-android-app` 的当前工作区，包含本轮尚未提交的悬浮记账改动。

现有账务写入链有明确的领域校验、单写事务、操作回执和恢复机制；外部数据也有真实的 schema、类型与关联检查。没有因为某个类较大、文件较长就必须先做整体重构的证据。本次未发现需要在开始独立小游戏前追加的账务阻断缺陷。后续应保持小游戏数据独立，不能为了复用现成偏好表把游戏状态并入账务数据集。

本报告是源码审阅和测试资产核对，本阶段没有运行 Gradle、ADB 或修改产品代码。悬浮链路的最新设备结果由主任务记录于 [悬浮验收报告](2026-10-25-floating-capture-verification.md)，不以静态结论代替该报告。本文“已修”表示本轮改动已落地且源码复核成立；技术债表示有明确维护或容量风险，但不等同于已复现故障。

## 责任边界

| 层 | 当前职责和证据 | 应继续保持的边界 |
| --- | --- | --- |
| core | `DomainEngine.apply` 负责命令、幂等、跨实体不变量和新状态；[Domain.kt:76](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/Domain.kt#L76)。`CaptureDomain` 将默认账本、分类和截图去重纳入同一个领域变更。 | 不读 Android 权限、通知、截图像素或 Activity 状态；不增加游戏命令。 |
| storage | Room 事务、SQL 查询、索引投影、迁移和候选库校验；[RoomLedgerRepository.kt:51](../../shared/storage/src/commonMain/kotlin/com/dawnyew/storage/RoomLedgerRepository.kt#L51)。 | 实现领域原子性，不自行决定截图金额、分类策略或 UI 确认。 |
| protocol | 版本化备份、规范序列化和摘要、导入预览与封印；[BackupProtocol.kt:14](../../shared/protocol/src/commonMain/kotlin/com/dawnyew/protocol/BackupProtocol.kt#L14)。 | 负责不可信文件边界，不以 Kotlin 默认值代替旧 schema 兼容策略。 |
| presentation | 手工草稿、提交与恢复、查询取消及结果状态；[AppController.kt:323](../../shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/AppController.kt#L323)、[CaptureBookingCoordinator.kt:43](../../shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/CaptureBookingCoordinator.kt#L43)。 | 不持有 Bitmap、Android Context；依赖 repository 的实际授权，不自行授权。 |
| Android 组合层 | 数据库代际与应用锁、截图/通知生命周期、系统权限；[GenerationRepository.kt:225](../../platform/android/src/main/kotlin/com/dawnyew/platform/storage/GenerationRepository.kt#L225)。 | 平台授权和代际保护必须在实际写入的互斥区生效；不能只在点击入口判断。 |

共享业务与平台代码的划分在当前实现中成立。项目目前只有可交付 Android 工程；这些共享代码不构成 iOS/Harmony 已集成或已验收的证据。

## 已核实的数据契约

### 金额、命令和事务

- 金额使用正整数分与方向/账目类型表达，`Money` 对范围及加法溢出作检查；[Domain.kt:11](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/Domain.kt#L11)。不需要把 OCR 符号或展示小数传播进数据库金额字段。
- 同一 `operationId` 的 payload 摘要必须相同；相同请求返回原结果，不同请求冲突。新截图命令在回执重放前也校验数据集；[Domain.kt:76](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/Domain.kt#L76)。截图的图像指纹去重与操作幂等是两层不同约束，不能互相替代。
- 实体变更、receipt 和 `contentVersion` 在领域结果中一起生成；[Domain.kt:231](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/Domain.kt#L231)。Room 在同一个 `immediateTransaction` 中读取版本、应用命令、差量写表，提交后才发布 StateFlow；[RoomLedgerRepository.kt:51](../../shared/storage/src/commonMain/kotlin/com/dawnyew/storage/RoomLedgerRepository.kt#L51)。这不是“先存账单，随后另存回执”的两次提交。
- 存储层已有回执插入失败时实体及版本一并回滚的测试，以及重试不重复增加版本的测试；[RoomLedgerRepositoryTest.kt:272](../../shared/storage/src/jvmTest/kotlin/com/dawnyew/storage/RoomLedgerRepositoryTest.kt#L272)、[RoomLedgerRepositoryTest.kt:154](../../shared/storage/src/jvmTest/kotlin/com/dawnyew/storage/RoomLedgerRepositoryTest.kt#L154)。本阶段只核对测试源码，没有重跑这些测试。
- 数据库既保存 JSON payload 又保存检索/约束列，读全量状态后会进行领域验证，再重建 `Rows` 与原始行严格比较；[RoomLedgerRepository.kt:118](../../shared/storage/src/commonMain/kotlin/com/dawnyew/storage/RoomLedgerRepository.kt#L118)。因此“字段声明非空”不是唯一保障；payload 与 SQL 投影不一致会拒绝加载。
- `creationOperationId` 有独立查询/恢复路径，receipt 不存在时部分创建命令仍可识别已提交结果；[EntryQueries.kt:88](../../shared/storage/src/commonMain/kotlin/com/dawnyew/storage/EntryQueries.kt#L88)。这一回退不能推导为所有命令的 receipt 都可随意删除。

### 备份、恢复与导入

- 备份明确支持格式 1、2；v1 保留原九类实体集合，v2 才包含扩展财务实体；[BackupProtocol.kt:16](../../shared/protocol/src/commonMain/kotlin/com/dawnyew/protocol/BackupProtocol.kt#L16)。恢复要求实体集合和计数集合精确匹配版本，不会把 v2 缺字段静默当成空集合；[BackupProtocol.kt:124](../../shared/protocol/src/commonMain/kotlin/com/dawnyew/protocol/BackupProtocol.kt#L124)。
- 真正的反序列化配置是 `encodeDefaults=true`、显式 null、拒绝未知字段、拒绝宽松解析和类型强转；[BackupProtocol.kt:23](../../shared/protocol/src/commonMain/kotlin/com/dawnyew/protocol/BackupProtocol.kt#L23)。此前还经过 `RecordSchema` 的字段集合、标量类型及 nullable 白名单检查；[RecordSchema.kt:42](../../shared/protocol/src/commonMain/kotlin/com/dawnyew/protocol/RecordSchema.kt#L42)。金额、revision 等宽整数使用规范十进制字符串，避免浮点交换损失。
- 文件级 JSON 读取还需要防重复键和长度越界；实际 Android 文件入口在 [JsonStreamCodec.kt:157](../../platform/android/src/main/kotlin/com/dawnyew/platform/files/JsonStreamCodec.kt#L157) 处理这些情况。不能仅凭已经转换为 Map 的 `BackupDocument` 宣称能识别源文件重复键。
- 候选库写入有单事务及分批校验，导出在一个 reader transaction 中读取元数据、计数和批次；[Streaming.kt:21](../../shared/storage/src/commonMain/kotlin/com/dawnyew/storage/Streaming.kt#L21)、[Streaming.kt:77](../../shared/storage/src/commonMain/kotlin/com/dawnyew/storage/Streaming.kt#L77)。候选校验检查完整性、关联、payload/列一致性、计数和摘要；[CandidateVerification.kt:19](../../shared/storage/src/commonMain/kotlin/com/dawnyew/storage/CandidateVerification.kt#L19)。
- 备份有意排除设备临时 operation receipts；候选恢复明确拒绝携带该表数据；[CandidateVerification.kt:27](../../shared/storage/src/commonMain/kotlin/com/dawnyew/storage/CandidateVerification.kt#L27)。这与原库未完成提交的恢复 marker 是不同生命周期，不能把 marker 当作跨设备备份内容。
- 导入计划包含 dataset、contentVersion、databaseEpoch 及选择/预览摘要，提交重新验证封印与版本；[Import.kt:8](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/Import.kt#L8)、[Import.kt:56](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/Import.kt#L56)。其目的在于避免用户预览后数据变化仍提交旧计划，不应在页面为“顺畅”跳过这些检查。
- 已有固定旧格式字节、未知/缺失字段、标量错误类型、Unicode、整数溢出和实体集合兼容测试；[BackupVersionCompatibilityTest.kt:17](../../shared/protocol/src/commonTest/kotlin/com/dawnyew/protocol/BackupVersionCompatibilityTest.kt#L17)、[BackupStrictTest.kt:20](../../shared/protocol/src/commonTest/kotlin/com/dawnyew/protocol/BackupStrictTest.kt#L20)。这些测试资产说明边界被明确建模，不表示此次重新执行全部验证。

### 查询、缓存和呈现状态

- 分页和统计在 reader transaction 内同时读取数据集/版本及查询结果，游标带作用域和版本；[EntryQueries.kt:29](../../shared/storage/src/commonMain/kotlin/com/dawnyew/storage/EntryQueries.kt#L29)、[FinancialActivityQueries.kt:59](../../shared/storage/src/commonMain/kotlin/com/dawnyew/storage/FinancialActivityQueries.kt#L59)。筛选通过绑定参数进入 SQL，未将用户输入拼为 SQL 语法。
- presentation 的分页/统计协调器使用取消和请求序号拒绝迟到结果，缓存还比较查询相关数据内容；[EntryPagingCoordinator.kt:25](../../shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/EntryPagingCoordinator.kt#L25)、[StatisticsCoordinator.kt:16](../../shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/StatisticsCoordinator.kt#L16)。不能只看到有缓存就判断“初始化值不会更新”。
- `sameQueryContent` 排除了不影响账目查询的偏好和回执，dashboard 再补充预算相关内容比较；[QueryContent.kt:5](../../shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/QueryContent.kt#L5)、[DashboardController.kt:22](../../shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/DashboardController.kt#L22)。后续增加查询维度时必须同时检查这一依赖集合。
- 手工 `EditorDraft` 的确认规则与新的直接截图命令各有入口；[EditorDraft.kt:55](../../shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/EditorDraft.kt#L55)。默认直接入账不应通过伪造 `confirmed=true` 绕过旧草稿契约，而应继续使用已独立实现的 `RecordCapture`。

## 本轮已修问题

| 问题 | 当前修复与源码证据 | 需要保留的回归点 |
| --- | --- | --- |
| 页面外缺少共享截图入账事务，默认选择与去重容易分成多个写操作 | [CaptureDomain.kt](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/CaptureDomain.kt#L1) 在领域事务内选择有效账本/同方向分类，必要时创建分类，缺日期使用 capturedAt，写入简短默认决策说明；精确指纹查重包含回收站，不复活旧记录。 | 无默认分类、指定分类失效、收入方向、相同图片已删除、不同图片同金额。 |
| 仅收到 execute 返回值就可能显示保存成功，撤销可能误删编辑后的账单 | [CaptureBookingCoordinator.kt:75](../../shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/CaptureBookingCoordinator.kt#L75) 核对操作回执、结果实体及数据集再 acknowledge；领域 UndoCapture 校验 OCR 来源、creationOperationId、revision 和关联退款。 | 保存不确定、ack 失败、幂等重试、编辑后撤销、退款后撤销。 |
| 同 datasetId 的备份替换无法仅靠 datasetId 区分旧请求 | [GenerationRepository.kt:225](../../platform/android/src/main/kotlin/com/dawnyew/platform/storage/GenerationRepository.kt#L225) 增加通用 expectedEpoch 写入能力，在与发布同一 mutex 内校验；[GenerationRepository.kt:283](../../platform/android/src/main/kotlin/com/dawnyew/platform/storage/GenerationRepository.kt#L283) 提供受限代际视图，snapshot/lookup/ack 也在相同互斥规则下执行。 | 旧 scope 不得写新代际，也不得读取或清除新代际 marker。新增测试见 [GenerationRepositoryTest.kt:148](../../platform/android/src/test/kotlin/com/dawnyew/platform/storage/GenerationRepositoryTest.kt#L148)、[GenerationRepositoryTest.kt:167](../../platform/android/src/test/kotlin/com/dawnyew/platform/storage/GenerationRepositoryTest.kt#L167)。 |
| 捕获流程可能绕过应用启动恢复状态 | `DawnYewApplication.awaitReady` 统一检查 staging 清理/启动错误再开放业务；workflow 使用应用入口，并为每次保存绑定当前授权数据库 epoch。 | 启动恢复未完成、应用锁开启、恢复同一备份、锁定后续接。见 [DawnYewApplication.kt](../../androidApp/src/main/kotlin/com/dawnyew/app/DawnYewApplication.kt)、[FloatingCaptureWorkflow.kt](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureWorkflow.kt)。 |
| 确认浮层填入默认值后，领域层无法知道这些值来自默认决策；解锁后可能再弹一次确认 | Review 保留 defaultedTime/defaultLedgerId/defaultCategoryId；保存时仅对仍未修改的默认项补充说明；已确认的 Pending.Review 恢复后直接保存。 | 用户改过默认值不再标记默认；解锁恢复只提交一次。见 [FloatingCaptureReview.kt](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureReview.kt)、[FloatingCaptureWorkflow.kt](../../androidApp/src/main/kotlin/com/dawnyew/app/capture/FloatingCaptureWorkflow.kt)。 |
| 新命令回执类型依赖运行时类名，压缩后可能与共享协调器的固定类型不同 | `RecordCapture` / `UndoCapture` 的序列化名与 receipt 名称显式稳定；[Models.kt:52](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/Models.kt#L52)、[Domain.kt:239](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/Domain.kt#L239)。 | 保留 Release 保存与撤销路径；旧命令命名问题单列为后续债务。 |

本轮更早阶段已执行的验证包括 core 88 项、presentation 140 项，以及代际修复后的 `GenerationRepositoryTest` 28 项和 Android 平台主机测试 130 项。它们分别对应当时的工作区检查点；本文不把这些数字表述为本报告写入时重新验证了所有并行改动。最终设备结果以悬浮验收报告为准。

## 仍存在的技术债

### D1：写路径依赖全量状态，回执没有常规保留期限

**证据：** `LedgerState` 持有全实体列表和 receipts；[Models.kt:27](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/Models.kt#L27)。初始化和 snapshot 会加载所有表；[RoomLedgerRepository.kt:153](../../shared/storage/src/commonMain/kotlin/com/dawnyew/storage/RoomLedgerRepository.kt#L153)。每次新命令将回执追加到列表并校验整个新状态；[Domain.kt:231](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/Domain.kt#L231)。Room 已有差量持久化和行缓存，因此不能描述成“每次操作删除重建整库”。

**影响：** 账目、导入历史和操作数量持续增长时，内存、校验、状态比较与 snapshot 成本会增长。新的截图协调器会多次 snapshot 核验，增加这一成本。当前没有容量基准，不能据此断言已经卡顿或 OOM。

**建议：** 先用 1 千、1 万、10 万账目及长期 receipt 数据量测量冷启动、普通写入、截图提交、备份/恢复的耗时与峰值内存，再决定是否改为有界读模型、缩小验证范围或引入安全的回执保留策略。任何回执裁剪都先列明哪些命令有持久创建身份、哪些需要原始 payload 摘要，以及 pending marker 和跨版本恢复如何处理，不能简单按天数删除。

**级别：** P2 容量与长期运行债务；没有测量依据时不作为小游戏前置重构。

### D2：旧命令的持久回执类型仍依赖 `simpleName`

**证据：** 新截图命令被显式映射，但其余分支仍取 `command::class.simpleName`；[Domain.kt:239](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/Domain.kt#L239)。通用提交侧也有运行时类型名核对；[AppController.kt:323](../../shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/AppController.kt#L323)。receipt 会写入数据库，且不止用于当次函数调用。

**影响：** 稳定业务标识与实现类名耦合，在压缩映射、重命名、应用版本升级和遗留 pending 恢复时需要额外保证。当前没有在本次审阅复现旧命令跨版本失败，不把它升级成现存数据损坏问题。

**建议：** 在下一次修改 receipt 协议时统一列出稳定命令类型，明确兼容已保存的值，补 Debug/Release 及旧版 pending 的恢复测试。不能只替换一端类型字符串，也不能直接假设所有历史值等于源码类名。

**级别：** P2 协议稳定性债务；本轮新截图类型已处理。

### D3：一个财务字段的契约需要手工同步多处

**证据：** 领域实体、Room 索引列/payload 映射、备份 RecordSchema、实体类型表及流式候选投影各自明确维护；[RoomLedgerRepository.kt:227](../../shared/storage/src/commonMain/kotlin/com/dawnyew/storage/RoomLedgerRepository.kt#L227)、[RecordSchema.kt:15](../../shared/protocol/src/commonMain/kotlin/com/dawnyew/protocol/RecordSchema.kt#L15)、[Streaming.kt:17](../../shared/storage/src/commonMain/kotlin/com/dawnyew/storage/Streaming.kt#L17)、[CandidateVerification.kt:84](../../shared/storage/src/commonMain/kotlin/com/dawnyew/storage/CandidateVerification.kt#L84)。

**影响：** 未来增加实体/字段时漏改一个边界，可能表现为新数据无法恢复、候选验证失败或查询投影不一致。现有严格交叉校验会暴露不少此类错误，不能把“多处维护”直接认定为当前 schema 错误。

**建议：** 每个字段变更附最小检查表：旧数据迁移、缺失/null/错误类型、payload 与索引列一致、备份版本与固定旧样本、完整导出/恢复、流式候选、相关查询依赖。先保持显式 schema，不为减少文件数立即引入生成框架。

**级别：** P3 演进维护债务。

### D4：`LedgerRepository` 同时承载操作视图和资源所有者能力

**证据：** 同一接口包含查询/execute、状态流以及 initialize/replace/close；[Models.kt:84](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/Models.kt#L84)。新的代际视图必须将 replace 标为不支持、close 设为空操作；状态流明确仍是 owner 的 live signal，受限读取要用 snapshot；[GenerationRepository.kt:283](../../platform/android/src/main/kotlin/com/dawnyew/platform/storage/GenerationRepository.kt#L283)。

**影响：** 接口类型不能完整表达“借用某一代数据库”与“拥有数据库生命周期”的差别，后续调用方可能误用 owner 状态或生命周期方法。当前截图调用使用了受保护方法，没有证据表明其已跨代际读写。

**建议：** 当出现更多后台消费者时，再把只读/命令端口与 owner 管理端口做最窄拆分；在此前保持 scope 文档和 stale-scope 测试。不要为游戏引入一个假账务 repository，也不要以关闭游戏页面为由 close 财务 repository。

**级别：** P3 类型边界债务。

### D5：提交恢复规则有多条编排路径，需要持续对齐契约

**证据：** 手工编辑由 `AppController` 驱动，周期记账由 `RecurringCoordinator` 配合自动提交，截图由 `CaptureBookingCoordinator` 提交；[AppController.kt:323](../../shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/AppController.kt#L323)、[RecurringCoordinator.kt:136](../../shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/RecurringCoordinator.kt#L136)、[CaptureBookingCoordinator.kt:75](../../shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/CaptureBookingCoordinator.kt#L75)。三者 UI、授权时机和取消语义并不相同。

**影响：** 未来新增自动入口可能漏掉 outcome 核对、pending 恢复或 acknowledge 时机。当前不应只因代码有相似步骤就把所有工作流合进一个父类。

**建议：** 先冻结“执行 → 查验确切 outcome → 处理 UI/调用者确认 → acknowledge → 故障保留恢复”的契约，并为各入口覆盖取消和不确定提交。只有确实出现相同变化原因时，再提取不含 UI、平台授权或入口策略的窄 helper。

**级别：** P3 协调维护债务。

### D6：core 中有尚未接入的早期平台端口

**证据：** `ImageHandle`、`OcrEnginePort`、`DocumentHandle`、`FileAccessPort`、`AuthPort` 位于 [Models.kt:114](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/Models.kt#L114)。仓库检索未找到这些接口/handle 的实际实现或调用；实际 OCR 结果模型 `OcrResult` 有使用，不能一并视为无用代码。

**影响：** 读者可能误以为现有 Android OCR/授权已经经这些端口实现跨平台适配，实际调用链不是如此。

**建议：** 文档明确它们未接入；下一次触及这一边界时根据真实第二实现决定接线或删除未使用端口，避免先构建泛化平台框架。

**级别：** P3 可读性与边界表达债务。

## 小游戏平台与 2048 的明确边界

`UserPreference` 名字看起来通用，但实际上属于 `LedgerState`，经过财务命令、内容版本和账务备份。其校验只有明确键名白名单，未知字段直接失败；[Validation.kt:90](../../shared/core/src/commonMain/kotlin/com/dawnyew/core/Validation.kt#L90)。因此把 `game2048State` 写进财务偏好并放宽白名单，会让游戏操作触发财务事务、receipt、状态通知和恢复/清空规则，不是零成本复用。

建议下一阶段采用以下边界，不要求先建设大型游戏框架：

| 项目 | 边界要求 |
| --- | --- |
| 纯规则 | 独立 games 包或模块保存棋盘、方向、合并/计分/胜负规则；注入随机源，可用确定输入验证。不得依赖 `LedgerState`、`Command`、`DomainEngine`、金额类型或财务环境。 |
| 会话 | 独立 game session/controller 处理新局、继续、移动和撤销（若产品要求），生命周期随游戏会话。不得借用财务 `pendingOperation`、草稿或 recurring busy 状态。 |
| 持久化 | 独立版本化 codec + 游戏 store，单独 namespace/文件/偏好文件；记录棋盘、分数、最高分和必要状态即可。文件损坏只影响游戏恢复；不触发账务恢复、不更换账务代际、不清空账本。 |
| 数据库 | 不向现有 Room 财务数据库添加游戏表，不扩展 `UserPreference` 白名单存游戏状态，不通过 `SetPreference` 保存每次移动。后续游戏确需数据库时，由游戏自己的 store 决定。 |
| 备份与清空 | 现有财务备份保持财务含义，不带游戏状态。未来用户明确要求游戏备份时，另立导出/版本契约。财务 ClearAll 与游戏重开不得互相调用。 |
| 授权与组合 | App 可以统一提供进入游戏的导航和应用级遮盖；游戏 store 不获得账务 repository 的访问权，也不通过修改 `session.authorized` 换取访问。 |
| 验收 | 同一输入/随机序列结果一致；无效移动不生成新块；合并规则和终局正确；codec 拒绝非法棋盘/版本；恢复不会污染财务数据；游戏移动不增加财务 contentVersion/receipt。 |

已有小工具平台将尺子校准说明为“不进入账本备份”，可沿用这种能力归属思路；[UtilityPlatform.kt:5](../../shared/presentation/src/commonMain/kotlin/com/dawnyew/presentation/UtilityPlatform.kt#L5)。是否创建独立 Gradle 模块取决于依赖隔离是否需要，不以“游戏平台”名称为理由提前增加模块和注册框架。

## 验证边界与后续顺序

1. 本报告没有运行新构建、测试或设备操作，没有修改已有产品源文件。已有验证数字与最新悬浮设备证据分别记录，不能混成一次完整回归。
2. 当前优先完成主报告汇总与跨层交叉复核；对 D1–D6 不做顺手重构。
3. 下一阶段小游戏只使用独立领域/会话/store 边界，先验证规则与持久化契约，再接 UI 和平台组合。
4. 将来增加财务实体或扩大数据量时，再按 D1/D2/D3 的触发条件安排容量和跨版本验证；保留全部现有工作区改动，不把本次审阅当作清理或提交授权。
