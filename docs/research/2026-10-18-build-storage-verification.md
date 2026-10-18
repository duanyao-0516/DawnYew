# DawnYew 构建基线、存储与通用导入验证

日期：2026-10-18。此记录只包含本地构建与共享模块验证；安装、真机交互、Native OCR 和加密的最终验收由各自记录负责。

## 实际锁定的版本和环境

| 项目 | 已核验值 |
| --- | --- |
| Gradle | 9.5.0，官方 ZIP SHA-256 `553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746` |
| AGP | 9.3.3 |
| Kotlin / Compose Compiler | 2.4.21 |
| Compose Multiplatform / Material 3 | 1.12.2 / 显式稳定版 1.9.0 |
| KSP / Room | 2.3.12 / 2.8.5 |
| androidx.sqlite bundled | 2.7.1；实际执行 `sqlite_version()` 为 **3.50.1** |
| kotlinx.coroutines / serialization / datetime | 1.10.2 / 1.9.0 / 0.7.1 |
| Jackson-core | 2.21.5，与技术设计 8.1 一致 |
| JDK | `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`，17.0.20.1 |
| Android SDK | `/Users/bytedance/Library/Android/sdk`，compileSdk/targetSdk 37，minSdk 30，Build Tools 36.0.0 |
| NDK | `.local/android-ndk-r28c`，28.2.13676358 |
| 项目缓存 | `.local/gradle-home`；不修改全局 Gradle 配置或共享 SDK |

候选 Kotlin、Compose、KSP、Room、SQLite、Jackson 版本均用官方 Maven POM/对应制品实际 HTTP 200 核验。AGP 原候选 9.3.1 确实存在；改用同小版本的 9.3.3，因为官方发布说明列出了 9.3.2 的 JDK 17 lint 修复和 9.3.3 的 D8/R8 修复。Kotlin 文档兼容上限列到 9.3.1，因此对 9.3.3 的接受还依据本工程的实际配置、Android 与 JVM 编译成功，而不是把文档矩阵改写为精确声明。[AGP 9.3 发布说明](https://developer.android.com/build/releases/agp-9-3-0-release-notes)

NDK 官方包 `android-ndk-r28c-darwin.zip` 为 952,495,160 字节，已比对官方 repository XML 的 SHA-1 `fc20a6bf15a30fb3428c9b60a7308793a362dc6d`。Gradle 包为 140,319,124 字节，校验上述 SHA-256 后提取；wrapper 由该 distribution 的官方 wrapper task 生成，`distributionSha256Sum` 同样固定。[Android repository XML](https://dl.google.com/android/repository/repository2-3.xml)、[Gradle checksum](https://services.gradle.org/distributions/gradle-9.5.0-bin.zip.sha256)

统一入口：

```sh
scripts/project-gradle.sh <tasks>
```

脚本设置本项目 Gradle 缓存和 JDK；可用 `DAWNYEW_JAVA_HOME` 指定另一个 JDK 17。Android app 默认只打 arm64-v8a；开发验证可传 `-PdawnyewAbis=arm64-v8a,x86_64`。NDK strip 路径明确指向项目目录。开发签名固定在被忽略的 `.local/signing/debug.keystore`：优先复制已有开发证书以保持设备安装身份，无已有证书时仅在项目目录生成；目录权限 0700、文件权限 0600。正式 release 保持 unsigned，不把开发证书当发布证书。

七模块是 `shared:core/storage/protocol/presentation/ui`、`platform:android`、`androidApp`。共享模块采用新 Android KMP DSL 和 JVM target；Android application/library 使用 AGP 内建 Kotlin。Room 的 KSP 和 schema 导出覆盖 Android/JVM。

## 实际存储实现

11 张 Room 关系表覆盖所有实体、元数据与操作回执；每条实体的 JSON payload 保留完整字段，SQL 列独立承载关联和唯一性约束。存在账本/分类归属外键、退款父引用、删除组、来源身份复合唯一键、submissionId 唯一键、成员实时引用、creationOperationId 唯一键及 22 个 INSERT/UPDATE 约束触发器。

仓库在单写者互斥下开启 Room immediate transaction，读取 metadata 核对已提交缓存版本；版本一致时重用不可变领域状态及未变化的行编码，版本变化则重新读取验证。以行差异执行删除/更新，账单、来源、批次、回执和 contentVersion 一起提交。普通编辑不清表重写；恢复时的唯一名称或 creationOperationId 交换先在同一事务中临时移开旧键。提交成功后才发布 StateFlow。

每个连接启用 foreign_keys、secure_delete、busy_timeout=5000 和 synchronous=FULL。Room 2.8.5 在 WAL 模式下会把连接重新配置为 NORMAL，因此仅在 driver.open 设置 FULL 不足；连接包装器将 Room 的 synchronous 赋值固定为 FULL，其余接口完整委托。实际 Room writer/reader 连接测试确认 journal_mode=wal、foreign_keys=1、synchronous=2。

schema 1、2、3 均保留导出文件。schema 2 增加 `entries.creationOperationId` SQL 列和唯一索引；显式 1→2 migration 先验证旧 payload 内的规范 UUID，再提取值、创建索引。schema 3 增加 `ledgers.archived` 和 `entries.noteSearch`，2→3 从 payload 读取归档值并用共享 Unicode 规则计算备注搜索键。重复操作 ID 使整个迁移失败，没有合并或清空兜底。真实 schema 1 建库后的迁移测试证明：成功路径保留流水及回执并升级至 schema 3，失败路径保持 user_version=1、原流水和原列集合。

Android 工厂为 `createDatabase(context,path)`，JVM 工厂为 `createDatabase(path)`；共同构造 `RoomLedgerRepository(database,environment,createIfEmpty)`。已存在活动库使用 `createIfEmpty=false`，metadata 丢失或空表不会静默创建初始账本。代际文件、认证许可、WAL 截断和保护副本由平台协调器管理。

`queryEntryPage` 在真实 SQL 中执行 QueryScope WHERE、`occurrence,id DESC` keyset 和 LIMIT，游标绑定 datasetId/contentVersion/scope。备注搜索按字面转义 `%`、`_`、反斜线，并使用同一个 Unicode 搜索键。`statistics` 在同一 SQLite read transaction 中执行 SUM/COUNT/GROUP BY，返回 entryCount 和含退款的分类/账本分组，生产路径不返回全部 entries。截图疑似重复及操作回执查询分别使用 EXISTS/LIMIT 和主键/唯一索引，不扫描并解码全量流水。

完整备份候选通过 `writeCandidate` 的单事务分批 INSERT 写入关系表，允许子项先于父项，末尾检查 FK、退款、删除组和批次成员等关系；`exportSnapshot` 的 metadata、counts 和按协议主键排序的 128 行访客共享同一个稳定读事务。没有为完整备份添加 20MiB 或 10,000 条限制。发布前 `verifyCandidate(expected)` 重新执行 integrity_check、128 行分批字段/编码/SQL 列一致性、关系及计数检查，并以内容 SHA-256 拒绝预览后被更改的候选。候选验证过程不物化整库 LedgerState。

## 规格审查与补齐

依据技术设计 3/7/8 和安全专题 8/9，增加或修复：

- 全实体/引用/操作 UUID、UTC 时间、SHA-256、来源 scope/规范化版本/内容摘要、偏好值、导入批次成员状态的语义校验；规范名称默认使用 Android/JVM 的 NFC 实现。
- revision/contentVersion 使用有检查的递增；年月和查询日期拒绝超出 0001–9999。
- 时钟/时区变化后，既存账单保持原发生时间的备注或金额编辑仍允许；新发生时间和新导入记录仍不能在未来。
- 备份 v1 字段集合完整且严格，默认字段不可省略，字符串/布尔/整数不隐式转换；所有计数和 Long 使用十进制字符串，原生 Int/版本与布尔保持对应类型。
- canonical writer 校验 key/value 中的孤立代理项，UTF-16 属性排序、控制字符转义，整数 -0 规范为 0；导出数组按稳定主键排序。
- `Command.CommitImport(ImportPlan)` 绑定 datasetId/contentVersion/databaseEpoch、文件/映射/选中行/预览摘要。`.sealed()` 生成选中内容与预览摘要。平台另行核对 epoch 和认证许可。
- 导入按本地 ID、强来源身份和显式疑似重复决定处理；同批冲突、ID/来源指向不同记录、源内容变化均拒绝；相同来源跳过而保留本地修改和回收站状态。重复父候选的引用映射到存活父记录，剔除父行则退款无法提交。
- 新容器必须在计划中明确列出；容器、流水、来源、批次、成员和回执同一事务提交。submissionId 是随备份保留的持久幂等键，旧回执不在也能返回原结果。
- `Command.UndoImport(batchId)` 检查全部成员 PRESENT、revision/contentDigest、删除状态及批次外依赖；修改后改回、软删、恢复、清理都不能绕过。成功移除本批流水/来源，保留容器并记录 UNDONE；重复撤销返回原结果。
- 非空删除组必须包含根成员，其余成员只能是根支出的退款；空审计组允许历史根标识悬挂。
- 通用模板检测区分 ExactMatch/Unsupported/Ambiguous；逐行结果为 Valid/Excluded/NeedsDecision/Invalid，预览保留全部物理行计数、显式映射、选择依赖、强身份/疑似重复以及实际新增财务影响。只有可提交计划才生成冻结摘要。

真实微信/支付宝/自身 Excel 业务模板仍未提供，因此生产注册表保留 AwaitingSpecification；测试里的合成候选不对外注册。通用事务已经具备，不能据此宣称平台模板可直接导入。

## 最后一次共享模块验证

```sh
scripts/project-gradle.sh \
  :shared:core:jvmTest :shared:protocol:jvmTest :shared:storage:jvmTest \
  :shared:core:compileAndroidMain :shared:protocol:compileAndroidMain \
  :shared:storage:compileAndroidMain --console=plain
```

新增候选重验证后再次运行上述命令，结果 `BUILD SUCCESSFUL in 6s`；JUnit XML 为 core 30、protocol 16、storage 35，共 **81 项，failure/error/skipped 均为 0**。日志在忽略目录 `.local/shared-candidate-final.log`。期间曾遇 Kotlin 增量 classpath 的 DirtyData 内部异常，以单次 `-Pkotlin.incremental=false` 重建缓存后，普通增量命令已再次通过；没有永久关闭增量编译。覆盖内容包括：

- 实际 bundled SQLite 初始化、关闭重开、并发退款、外键/金额约束、WAL/FULL、普通编辑无 DELETE、损坏触发器拒绝重开。
- 注入回执 INSERT 失败后的账本/流水/来源/批次/成员/version/StateFlow 完整回滚；导入提交与撤销均覆盖。
- 并发 submission 重试、清理后成员历史、SQLite 外键无悬挂、实际 v1→v3 数据迁移及冲突回滚。
- 核心退款/回收/归档/幂等/算术边界、导入身份和依赖、修改后再改回仍拒绝撤销；备份正常回环与严格字段反例。
- 同秒 keyset 分页、游标过期拒绝、字面 LIKE、Unicode 搜索、并发统计版本一致性、退款独立分组、截图回收站重复提示、已存在空库拒绝重建。
- 流式候选乱序关系、末尾验证失败及取消整事务回滚、完整备份超过交换文件限额的真实 SQLite 往返；候选截断、字段漂移、关系破坏和预览后内容变化均拒绝。

本机 JVM 单次样本（不是 P95 或目标 Android 真机性能）：10,000 条实际 SQLite 流水，50 条分页 3ms、SQL 汇总 10ms、缓存单笔修改 79ms；15,001 条流水的协议记录共 29,957,296 字节，最大 chunk 128。加入两端候选检查及最终重新验证后，完整 SQL→协议 chunk→SQL 往返单次为 2,907ms。不能据此替代指定 Android 12/6GB 基准机验收。

仍有 Kotlin expect/actual 类 Beta 提示，未作为失败；没有用静态检查替代真实 JVM/SQLite 执行。此子任务未运行 adb、修改全局配置或提交 Git。
