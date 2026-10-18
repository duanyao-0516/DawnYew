# DawnYew KMP、存储与运行基线选型

日期：2026 年 10 月 18 日。状态：技术设计建议，官方资料核验完成；未创建工程、安装依赖、执行 PoC、编译或真机测试。

输入：[需求 v0.3](../superpowers/specs/2026-10-18-dawnyew-requirements-design.md)、[技术设计准入](../superpowers/specs/2026-10-18-dawnyew-technical-design-readiness.md)、[UI 规范](../superpowers/specs/2026-10-18-dawnyew-ui-interaction-standard.md)。最新用户约束优先：Android 是唯一当前开发与验收平台；iOS 只保留平台接口，不创建 target、工程、适配实现或编译验证。旧输入中要求当前做 iOS 最小编译验证的条款不再适用。微信、支付宝及 DawnYew 自身模板本轮仅设计接入契约，不定义具体列名、文件布局或适配器。

## 结论

建议 **KMP 共享领域规则、数据访问与主要 Compose UI，Android 独立应用壳；存储采用 Room 2.8.5 KMP + AndroidX bundled SQLite 2.7.1**。领域层只依赖仓储接口，不暴露 Room 注解、数据库连接或 Android 类型。当前只配置 Android 和用于逻辑/存储测试的 JVM 目标；JVM 不代表桌面产品。

本地数据库采用 Android 私有、凭据加密存储目录与系统沙箱保护，首版不加 SQLCipher。应用锁、数据库保护和口令加密备份是三个独立机制；这项选择不允许宣传“数据库已经单独加密”。如后续明确要求数据库文件即使从已解锁设备取得也不可读，优先重新评审 **Android Room + SQLCipher**，领域和 UI 仍保留 KMP 边界；不把未经验证的加密驱动拼接成既成方案。

SQLDelight 是 SQL 主导的数据层备选，适合团队明确希望以 `.sq/.sqm` 为唯一模式与查询来源时采用。当前业务规模并不需要同时引入 Room 和 SQLDelight；两套生产数据库层会增加迁移与一致性风险。

## 版本与 Android 基线

下表是可以交给后续工程阶段使用的**精确候选组合**，不是“这些单件稳定所以整套已经通过”的声明。所有版本固定，不用动态版本；工程建立后须保存依赖锁与实际解析树。

| 项目 | 首个锁版候选 | 选择与证据 |
| --- | --- | --- |
| Kotlin / KGP / Compose Compiler plugin | 2.4.21，三者同版 | [Kotlin 发布记录](https://kotlinlang.org/docs/releases.html)列出 2026-10-08 发布；Compiler 与 Kotlin 同版是[官方约束](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html) |
| Compose Multiplatform plugin、runtime、UI、foundation | 1.12.2 | [固定版本发行记录](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.2)；Android 基于 Jetpack 1.12.1 |
| Material 3 | `org.jetbrains.compose.material3:material3:1.9.0` | 显式固定稳定版，基于 Jetpack M3 1.4.0，见[官方 1.9.1 发行说明](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.9.1)；不跟随 CMP 1.12.2 组件表里的 M3 1.12.0-alpha03 |
| Material 3 Adaptive | `org.jetbrains.compose.material3.adaptive:adaptive:1.3.0` | CMP 1.12.2 官方组件表列为稳定版本；仅选择实际用到的 artifact |
| Lifecycle / Navigation | 2.11.0 / 2.9.2 | Lifecycle 来自 CMP 1.12.2 表；导航固定[已发布的 2.9.2](https://kotlinlang.org/docs/multiplatform/whats-new-compose-110.html)，不隐式纳入最新组件表中的 Navigation RC |
| Room runtime / compiler / Gradle plugin | 2.8.5，三者同版 | [Room 发布记录](https://developer.android.com/jetpack/androidx/releases/room)列为稳定版；保持 `androidx.room` 命名空间 |
| bundled SQLite | `androidx.sqlite:sqlite-bundled:2.7.1` | [SQLite 发布记录](https://developer.android.com/jetpack/androidx/releases/sqlite)列为稳定版；发布前另记录实际 `sqlite_version()` 和编译选项，不从 artifact 版本猜 SQLite 内核版本 |
| KSP | 2.3.12，使用 KSP2 | [该版说明](https://github.com/google/ksp/releases/tag/2.3.12)要求 AGP 至少 8.12.0；编号不需要与 Kotlin 2.4.21 相同 |
| AGP / Gradle / 构建 JDK | 9.3.1 / 9.5.0 / JDK 17 | 落在[KGP 2.4.21 支持范围](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)；AGP 9.3 的[Gradle/JDK 下限](https://developer.android.com/build/releases/agp-9-3-0-release-notes)分别为 9.5.0 / 17 |
| `minSdk` | 30，Android 11 | 产品支持范围建议，减少旧系统认证组合与生命周期兼容分支；明确不覆盖 Android 10 及以下。不是 Room 的强制下限 |
| `compileSdk` / `targetSdk` | 37 / 37 | [Android 17 SDK 配置](https://developer.android.com/about/versions/17/setup-sdk)；目标行为变化必须纳入后续设备验收 |
| 测试平台 | Android + JVM | 不声明 iOS target，不安装或验证 Xcode，不生成 iOS 工程 |

Room 2.8 与 AndroidX SQLite 2.6 起的 Android 最低版本为 API 23；不能只看 Compose 概览的 API 21 就把应用下限设为 21。DawnYew 选 API 30 是产品取舍，依赖最低版本和目标验收设备必须分开记录。建议性能基准仍使用需求中的 Android 12 及以上 6GB 中端真机，实际机型尚未指定。

当前组合的 Material3 1.9.0 与 CMP 1.12.2、KSP 2.3.12 与 Room 2.8.5、AGP 新 KMP 插件与 Room schema 导出均**未做组合验证**。只使用稳定 M3 API；后续 Android smoke 须覆盖主题、金额输入、DatePicker、Dialog、导航返回、宽屏重排、R8 和 Room 生成代码。遇到不兼容时形成单独锁版修订，不直接引入 alpha 组件。

官方网页存在版本漂移：当前 Room KMP 指南示例已使用 `androidx.room3`，而本方案锁定稳定的 Room 2.8.5。指南可用于理解驱动和事务模型，具体签名以 [`androidx.room` API](https://developer.android.com/reference/kotlin/androidx/room/RoomDatabase)及锁定源码为准。GitHub releases 聚合页还会混入 1.13 alpha 的 `compileSdk 37.1` 迁移项；应读固定 tag，而非复制搜索摘要。

## 共享层组织与平台接口

采用 Android 应用模块与共享库分开组织的方式，符合[JetBrains 新默认结构](https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/)和[Android KMP library plugin](https://developer.android.com/kotlin/multiplatform/plugin)的职责边界。模块数量可以在实现计划中收敛，但依赖方向不能反转。

| 层 | 应承担的职责 | 不应进入这一层的内容 |
| --- | --- | --- |
| 共享领域 | CNY 整数分、发生日期、账本/分类归属、退款上限、组删除恢复、导入去重、统计口径 | Context、URI、Bitmap、数据库 Entity、系统认证结果类 |
| 共享应用用例 | 一次命令的事务边界、幂等键、预览计划及重校验、统一错误、页面状态 | 平台 UI 回调里拼接账务 SQL |
| 共享数据实现 | Room Entity/DAO/迁移、SQL 查询、行到领域对象映射、只读快照 | 生物识别、文件选择器、OCR SDK、文件系统重命名 |
| 共享 UI | 金额表单、流水、图表和等价数据表、筛选、空态/失败态 | Android Activity 生命周期本身、平台权限处理 |
| Android 壳与适配层 | Activity、生命周期锁、SAF、分享 URI、图片、OCR、流式文件 I/O、数据库路径和恢复切换 | 重写一套退款或统计规则 |

优先使用构造注入的普通接口表达能力，避免为未开发的 iOS 编写空 `actual` 或假成功实现。边界包括 `LedgerRepository`、`SnapshotReader`、`FileAccess`、`JsonStreamCodec`、`SpreadsheetCodec`、`OcrEngine`、`LocalAuthenticator`、`DatabaseLifecycle`、`Clock` 与 `IdGenerator`。接口参数使用稳定 ID、领域 DTO、受控流或文件令牌；平台 URI 只在 Android 适配器内部解析。

JSON 的版本化对象、字段含义、整数校验、重复字段策略和迁移语义放共享层；Android 使用 Jackson core 做 token 流读取、资源约束和输出编码是可行边界。共享层接收逐对象的受校验事件/DTO，不要求把完整文件加载成一个对象树。Android codec 不自行决定金额舍入、退款关联或未来版本兼容；未来 iOS 只需实现相同 token/DTO 契约并通过同一组协议样例。

## 数据库比较

| 方案 | 对本项目的适配 | 主要代价与风险 | 决策 |
| --- | --- | --- | --- |
| Room KMP + bundled SQLite | 共享 DAO、Entity 与迁移；SQL 编译期检查；suspend/Flow；统一 SQLite 内核来源 | KSP/Gradle 组合需验证；复杂 CHECK/触发器需显式管理；随包 native 库增加体积并承担更新责任 | 主方案 |
| SQLDelight + 平台 SQLite driver | SQL 文件直接表达表、查询与约束；生成类型；事务和 `.sqm` 迁移清楚，适合统计 SQL 较复杂的团队 | 平台驱动配置和约束启用需自己审查；迁移检查不证明业务数据正确；编译插件与 dialect 需配套锁版 | 备选，当前不引入 |
| Android Room + 系统 SQLite | Android 路径成熟，工程与故障定位简单；共享领域接口仍成立 | SQLite 行为随 Android 版本变化；数据实现留在 Android，后续平台需重做 | KMP 数据层遇到明确阻塞时的备选 |
| Android Room + SQLCipher | Android 有官方 Room 集成，满足单独数据库文件加密的候选 | 数据库密钥、失效、轮换、恢复、native ABI/页大小及升级兼容成本增加 | 只有保护目标提高时重评 |
| 直接 SQLite / JSON 文件作为主库 | 直接 SQLite 控制力高；JSON 可读、适合交换 | 前者需自行维护查询/迁移/观察机制；后者缺少关系约束、多记录事务和有效索引 | 不作为主路线；JSON 只做协议 |

[Room KMP 指南](https://developer.android.com/kotlin/multiplatform/room)推荐 bundled driver，以减少平台 SQLite 版本差异；这不表示 bundled driver 自动加密。SQLDelight 的[事务](https://sqldelight.github.io/sqldelight/latest/android_sqlite/transactions/)、[Flow 扩展](https://sqldelight.github.io/sqldelight/latest/android_sqlite/coroutines/)与[迁移验证](https://sqldelight.github.io/sqldelight/latest/android_sqlite/migrations/)都有官方接口。备选可从 [SQLDelight 2.4.1](https://github.com/sqldelight/sqldelight/releases/tag/2.4.1)开始评估；本轮没有验证其与上表工具链的组合，不建议混入主方案依赖。

## 必须落到存储契约的不变量

以下是 DawnYew 的设计要求，不是框架自动提供的业务能力。

1. 金额在 Kotlin 使用 `Long` 整数分，在 SQLite 使用 `INTEGER`；单笔范围 `1..99_999_999_999` 分。输入严格拒绝多于两位小数，禁止经 Double 转换。合计使用整数 `SUM`、`COALESCE(...,0)`，禁止 `TOTAL`；[SQLite 聚合规则](https://www.sqlite.org/lang_aggfunc.html)规定整数 SUM 溢出会报错，TOTAL 返回浮点。净支出减法、预算和 UI 格式化同样检查 Long 溢出，不能只校验单笔。
2. `occurredLocalDate` 与当地时间单独持久化，统计按保存的发生日期归属；创建/修改时间另存 UTC 时间戳。不得通过设备当前时区从 epoch 重新推导历史月份。日期编码按固定格式与历法校验，并对账本、有效状态、日期排序建立索引。
3. 本地稳定 ID 是主键；账本规范化名称唯一，分类 `(ledgerId,kind,normalizedName)` 唯一，预算 `(ledgerId,yearMonth)` 唯一。所有输入入口共用名称规范化规则，不把 SQLite 默认排序规则当中文业务比较规则。
4. 分类与流水的账本归属通过复合外键或等价的数据库约束检查。退款通过原支出稳定 ID 关联，不按名称关联；有效退款的同本/同分类、日期和合计上限必须在写事务内重新读取并校验。回收站退款保留原支出 ID；不能设置阻止原支出移动的“退款历史账本必须永远相同”复合外键。
5. 确定来源身份使用单独 `SourceIdentity` 表的全非空复合唯一键；基线为 `(provider,scope,eventKind,externalId)`，不以可空复合唯一列误以为所有重复都受约束。scope 与 eventKind 的实际含义由后续模板样本证明；eventKind 必须是来源定义的不可变身份分量，不能由可变状态或收支映射推导，否则来源变化会绕过冲突检查成为新增。本轮不猜账号或文件列。来源原始指纹与当前业务内容分开保存，移动/改分类后重导不覆盖本地修改。
6. 普通删除只写删除组 ID/删除状态，统计固定排除；原支出永久清理才级联全部退款。单独清理退款不得反向级联。此前单删的退款不被后来原支出的删除组吸收。恢复在事务内重算有效退款总额，整组满足条件后再写。
7. 导入预览不是提交授权后的唯一校验。提交时重验数据库世代/版本、目标归属、来源唯一键、原支出和已退款总额；批次、新分类/账本及所选流水一次原子提交。批次撤销校验新增记录的当前修订及新增关联，移除流水但不误删后来复用的账本/分类。
8. 相同保存命令使用持久化唯一幂等键；UI 按钮禁用只是体验措施。写成功、提交成功后才返回保存结果；进程在返回前后中断都能查询同一命令结果，避免重试生成第二笔。

SQLite 的[外键支持](https://www.sqlite.org/foreignkeys.html)要求对连接启用，并对父键唯一性、子键索引和删除动作作正确声明。Room 自动生成的外键、索引及模式校验不能代替业务校验。基线由 Room 生成 FK/UNIQUE，再显式建立 BEFORE INSERT/UPDATE 触发器对金额、kind、direction 等局部断言执行 RAISE(ABORT)；触发器在创建和每条升级路径中维护，并用实际数据库 fixture 校验其存在和拒绝行为。若改用 CHECK，须通过表重建/受控 DDL 提供，不能在已有表上假设可直接 ADD CHECK。不要写出“使用 Room 所以所有不变量均已保护”的结论。

## 事务、观察与统计快照

一个完整写用例使用同一 writer connection 的 immediate transaction；领域校验依赖的当前行读取与最终写入位于该事务中，不在预览阶段提前读完就直接写。UI、OCR、导入、恢复入口都经过同一仓储，不允许第二套直接修改库的代码。单进程内的操作门用于避免恢复与普通读写竞争，SQLite 事务才负责持久化原子性。

`Flow` 负责“数据需要重新读取”的信号，不是跨多个查询的一致快照。Room 的[异步查询说明](https://developer.android.com/training/data-storage/room/async-queries)明确表内任一行变化都可能触发重查。不能 `combine` 四个独立合计 Flow 后宣称四个值来自同一提交。

建议 `StatisticsQuery` 固化账本集合、闭合发生日期区间、类型/分类/备注和是否包含归档等条件；统计、等价数据表、下钻生成器共用它。接到失效信号后，在同一个只读事务中读出 `dataRevision`、卡片、趋势、分类、账本比较及可见明细摘要，发出单个不可变 `StatisticsSnapshot`。SQLite WAL 的[快照隔离](https://www.sqlite.org/isolation.html)支撑该读事务边界。

每次相关业务写事务一并增加 `dataRevision`；完整恢复改变数据库世代 `databaseGeneration`。用户点击图表时携带 query、世代和 revision；若已变化，先用当前快照重算对应图表与下钻摘要，再展示相同口径的明细，提示数据更新。分页出现修订变化则废弃旧分页并重新载入。不能为跨页面停留长期持有读事务，也不能仅带 revision 却假装数据库能查询任意历史版本。

原始支出占比只包含支出正数；退款、净支出另算。趋势净支出可负；预算只对账本完整自然月且无额外明细过滤显示，已用为 `max(netExpense,0)`。图表坐标可在最后绘制阶段转为浮点，金额文本、占比计算的输入、排序与下钻键保留整数及 ID。显示层不得再次遍历一套与 SQL 条件不同的“自算金额”。

## 持久性、升级与完整恢复

建议 WAL + `synchronous=FULL` 作为“成功提示后数据已提交”的基线；后续验证要读取每个实际 writer connection 的有效配置，不假定一次 callback 会覆盖连接池所有连接。[SQLite WAL 文档](https://www.sqlite.org/wal.html)说明 FULL 在每次提交同步 WAL，NORMAL 的断电持久性不同。正确配置仍不能消除存储硬件损坏或操作系统错误，不能承诺数据永不丢失。

数据库升级禁用 `fallbackToDestructiveMigration` 及任何遇错清库路径。导出 schema JSON 并随版本保存；每条迁移具备旧库 fixture、数据断言、整数金额、退款关联、回收站、来源身份与批次完整性检查。Room 的[迁移测试工具](https://developer.android.com/training/data-storage/room/migrating-db-versions)证明结构是否匹配，不会自动证明业务语义未改变。复杂数据变化使用可审查的手工迁移；升级前保存可验证保护副本，失败保留旧库供回退或恢复。

完整 JSON 恢复应先流式解析到独立临时库，完成协议迁移、唯一键、关系、金额、数量与汇总验证，再显示摘要。应用锁设备状态不从外部文件覆盖。提交前进入独占维护模式，停止新命令，取消/排空读事务，确认当前库保护副本已写完且可读，再关闭连接并切换库世代。保护副本、临时库、旧库和活动指针必须有可恢复的持久化状态记录；进程中断后只能选择完整有效的旧世代或新世代，不能把多个文件 rename 当成一个原子事务。

不能在活跃 WAL 库上只复制 `.db`，不能手工删除 `-wal` 以“确保干净”；WAL 是已提交数据的一部分。可采用已验证的逻辑快照导出，或在维护模式关闭连接并确认 checkpoint 后建立文件副本；选择 native SQLite backup API 时须先验证 Room/driver 是否暴露该能力，不能仅凭 [SQLite 有 backup API](https://www.sqlite.org/backup.html)就假定 KMP 可调用。

候选库与保护副本至少检查结构完整性、`foreign_key_check` 和领域不变量；[SQLite PRAGMA](https://www.sqlite.org/pragma.html#pragma_integrity_check)明确 `integrity_check` 不替代外键检查。文件校验和只能发现变化，不能代替 JSON 协议或业务校验。损坏旧库不得直接打开成空账本；需要保留原文件、明确失败并走用户可见恢复流程。

外部备份以公开 JSON 业务协议为长期迁移载体，不把 Room 私有 schema 或 SQLite 文件设为唯一恢复格式。导出中断、外部文件提供者断连和空间不足均可能产生不完整文件，必须只在完整写入并关闭成功后提示完成；保留版本、数量及完整性信息供恢复拒绝不完整数据。

## 加密与平台组合边界

Android [FBE 文档](https://source.android.com/docs/security/features/encryption/file-based)区分默认的凭据加密存储与设备加密存储。DawnYew 数据库和内部保护副本位于前者，排除系统云备份及设备迁移备份；不使用 Direct Boot 存储账目。屏幕锁定和应用锁解锁不是同一事件：系统首次解锁后，应用锁不能让已经可访问的数据库文件自动重新加密。

本方案不生成数据库密钥，因此没有假定存在的 SQLCipher 密钥保管或生物识别解密链路。Android Keystore 如用于内部保护文件，需另定义密钥丢失后的处理，不能使用户的跨设备完整备份只能由旧设备解开。便携口令备份的密钥派生、随机数、认证加密和容器版本由安全协议另定，和 Room 驱动无关。

[SQLCipher Android 官方项目](https://github.com/sqlcipher/sqlcipher-android)当前列出 Room 3 的 `SQLCipherDriver` 示例，以及 Room 2 的 `SupportOpenHelperFactory` 接入。这是 Android 平台支持证据；并未证明“Room 2.8.5 KMP + bundledSQLite + SQLCipher + iOS”能直接组合。bundled SQLite 和 SQLCipher 都是数据库引擎路线，不能在普通 bundled SQLite 上执行 `PRAGMA key` 就当作已加密；采用 SQLCipher 时必须换成实际加密实现并验证错误密钥拒绝、页内容、WAL、迁移及备份。

## 图表与 XLSX 的技术取舍

四类固定图表可先使用 Compose Canvas/布局完成，配同一快照生成的可访问数据表；[官方绘制 API](https://developer.android.com/develop/ui/compose/graphics/draw/overview)足以表达折线、柱、扇区与进度。代价是坐标轴、命中测试、零轴、标签避让、缩放文字和读屏语义由项目维护。图表库备选 [Vico](https://github.com/patrykandpatrick/vico)已有 Compose Multiplatform 路线，但其公开图表能力不能直接证明满足占比图与本项目读屏要求；不能为了采用库改动既定统计语义。本轮不以库引入替代验证。

XLSX 的解析/生成隔离为 Android `SpreadsheetCodec`；共享层只认识原始单元格类型、行诊断与来源适配契约。若主方案采用受限 OOXML ZIP + Android XML 解析器，明确只实现已声明的交换子集，不是完整 Excel 引擎：ZIP 路径/条目/解压限额，XML 深度/实体/文本限额，关系与命名空间，shared strings、inline strings、数字精度和日期编码均须有拒绝策略。公式、宏与外部关系不执行；未知结构不得猜测。具体来源适配器和 DawnYew 表头布局仍等待模板评审。

受限 codec 的优势是边界可控且不把 JVM 桌面依赖误当 Android 支持；代价是规范维护、Office/LibreOffice 互操作、畸形文件和资源消耗测试由项目承担。库的 Java/Kotlin 语言标记不等于 Android 或 KMP 兼容证据。文件 I/O、ZIP/XML 细节不进入领域层，不为本轮范围创建 iOS 替代实现。

## 持续维护、许可与后续验证清单

Room/AndroidX 与 SQLDelight 的主仓库使用 Apache 2.0，SQLite 核心发布为 public domain，依据分别为[AndroidX 许可](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt)、[SQLDelight 许可](https://github.com/sqldelight/sqldelight/blob/main/LICENSE.txt)、[SQLite 许可说明](https://www.sqlite.org/copyright.html)。这不是对最终 APK 所有传递依赖的许可审计；交付必须保留适用 LICENSE/NOTICE，并独立核对模型、字体、native 库和备份加密依赖。

bundled SQLite 减少系统版本差异，也使内核安全修复、native ABI 和 16KB 页设备兼容成为应用升级责任。Kotlin、Room 和 SQLDelight 的发布活动只能说明当前维护状态，不能保证未来 SLA；保持可审查 SQL、数据库 fixture 和公开 JSON 格式，降低框架更换成本。不能用一次开机成功证明长久升级与恢复安全。

| 后续验证 | 必须产出的证据；本轮均未执行 |
| --- | --- |
| Android 工具链 smoke | 上表固定版本解析树、无意外 alpha/RC、Room KSP 与 schema 导出、Debug/Release/R8 可构建 |
| 关系与事务 | 退款并发上限、整组移动/删除恢复、唯一来源碰撞、保存幂等、批次提交/撤销的实际 SQLite 测试 |
| 统计快照 | 写入与重查交错时合计/数据表/下钻一致；修订变化与整库切换时旧分页被废弃 |
| 升级与恢复 | 旧库逐版和跨版迁移；每个切换阶段杀进程、磁盘满、损坏 WAL、损坏输入、重复完整恢复；无清库兜底 |
| 持久性 | 有效 journal/synchronous/foreign_keys 配置；成功提示后进程结束/重启保留；不把普通进程终止冒称真实断电测试 |
| 安全与离线 | 私有目录及备份排除、生物识别入口、不含 INTERNET、依赖无运行时请求；明文数据库保护边界文案正确 |
| 文件与图表 | 超过交换导入上限的完整 JSON 往返；XLSX 互操作与畸形输入；负净支出、数据表、点击下钻及大字号读屏 |

这些验证项是后续 Android 实现准入/验收工作，不要求本轮补建 PoC；iOS 验证不在清单内。尚待产品确认的基线是 Android 11 最低覆盖范围与实际性能基准机；模板样本仍是对应适配器落地的前置输入。
