# 离线财务改版技术方案交叉审阅：平台、文件与验证

日期：2026-10-24。初审版本：技术方案 107 行、实施计划 110 行；复核版本：技术方案 123 行、实施计划 110 行及 `finance-review-resolution.md`。问题正文中的“方案位置”为首版行号，各项关闭证据为修订版行号。仅审阅方案、现有调用链与脚本，没有运行 Gradle/ADB，也没有修改业务代码。

审阅对象：

- `docs/superpowers/specs/2026-10-24-offline-finance-redesign.md`
- `docs/superpowers/plans/2026-10-24-offline-finance-redesign.md`
- 当前 Android 会话/文件入口、生产 JSON 流式链、generation 发布、现有 import API 和 crypto 容器实现。

**复核结论：PLATFORM-01、02、03 均已在设计层关闭，平台审阅无剩余 P0/P1/P2。**初审的 1 项 P1、2 项 P2 及其证据保留在下文，实际修订已逐项读回；此结论不等于实现或运行验证通过，也不代替其他审查者的 Gate 0 结论。基线主机测试由主 agent 执行成功，其中多项 UP-TO-DATE；本审阅不将其视为新方案正确性的证明。

## PLATFORM-01 · P1：周期只接认证成功会遗漏默认无锁用户，授权值也不能代表前台

**方案位置：**计划第 87 行；方案第 25、67、75、91 行。

**现有证据：**

- `androidApp/src/main/kotlin/com/dawnyew/app/MainActivity.kt:153` 的 `onResume` 使无锁用户直接进入可用状态并初始化仓库；`completeAuthentication():226` 没有认证 attempt 时直接返回。
- `platform/android/src/main/kotlin/com/dawnyew/platform/security/SessionCoordinator.kt:17` 在关闭锁时设置 `authorized=true`，`:48`、`:58` 退后台/撤销仍将其置为 `!lockEnabled`。
- `androidApp/src/main/kotlin/com/dawnyew/app/DawnYewApplication.kt:25` 的 controller 使用 Application scope；`:50` 给 repository 的授权谓词仅为 `session.authorized`。

**触发及影响：**新安装默认未启用应用锁，保存一个未来到期的周期计划后退出，过期再打开，若按计划“前台认证成功后触发”接线就不会生成到期项。若为补偿而在 Application scope 启动循环、只用 `authorized` 作为继续条件，无锁用户 Home/锁屏以后仍能提交，违反本轮“仅可用前台执行”的承诺。恢复 generation 或等待旧回执期间也必须停止旧批次，而不是靠唯一期次键事后去重。

**关闭要求：**

1. 统一 foreground-ready 信号，条件至少为 `resumed && !covered && authorized && !deviceLocked && repositoryReady`；排除未解决 pending operation 和未结束外部 picker lease。
2. 无锁冷启动、普通前台、认证完成、合法 picker 返回及恢复完成都进入同一 coordinator；先等待 repository ready，不能仅在 `onResume` 当时取一次空状态。
3. 只保留一个有限批次 job；每次新提交前复核 foreground-ready、session epoch、database epoch；pause/revoke/restore/clear 取消，已进入原子事务的提交按既有回执处理，不能误报回滚。
4. 旋转/重复 ready 通知不重复执行；已达 100 期上限必须等待显式继续，不通过状态流重新发射形成无限追赶。正常编辑/文件预览的忙碌期间不要自动竞争写入。
5. 测试覆盖无锁冷启动与重新前台、锁启用后认证/取消、picker 往返、后台中止、restore/clear epoch 切换、待确认回执和旋转重复通知。

**状态：设计层已关闭（原审查者复核）。**修订方案第 71 行明确统一前台就绪条件、repositoryReady、用户 busy/pending/picker 阻塞、每次提交前双 epoch 复核、后台取消、旋转单飞与 100 期后显式继续；自动处理共用单写门，只核验并确认自身回执，未知结果回到既有恢复提示。计划第 48、76、87 行对应领域身份、controller 测试及平台事件接线，覆盖默认无锁用户和仓库异步就绪。实现阶段仍须按上列场景核对真实 coordinator 与测试，不能仅在认证回调调用一次。

## PLATFORM-02 · P2：CSV 的逻辑记录、物理行与 10,000 行提交上限尚未形成兼容契约

**方案位置：**方案第 30 行；计划第 84–85 行，亦涉及 Task 1 的 API 冻结和 Task 4 的协议所有权。

**现有证据：**

- `shared/protocol/src/commonMain/kotlin/com/dawnyew/protocol/ImportPreview.kt:9` 定义 `DocumentFacts.physicalRowCount`。
- 同文件 `:57` 要求 parsed 数量严格等于 physicalRowCount，行号必须覆盖 `1..physicalRowCount`；`:58–59` 映射和选中行也使用该单位。
- `shared/core/src/commonMain/kotlin/com/dawnyew/core/Import.kt:56` 拒绝单批超过 10,000 条选中记录。
- 现有 `Entry.note` 可包含换行；计划要求 CSV 支持引号、换行和 roundtrip，但未定义 header、空行、被引号包住的换行与预览行号之间的关系，也未明确超大 CSV 的导出/导入策略。

**触发及影响：**一个备注含两行的 CSV 交易是一个逻辑记录、两条物理文本行；如果 parser 返回逻辑记录而把文件文本行数传给现有 facts，预览必然拒绝。若为了凑行数插入虚假空行，会污染错误定位/选择行号。另一方面，用户导出 10,001 条普通流水后导回，若产品未声明限制且 UI 默认全选，将在最后提交时才被现有 domain 上限拒绝；不能把这种文件称为无条件可往返。

**关闭要求：**

1. 在业务实现前冻结 DawnYew CSV v1 的记录单位：建议业务逻辑记录序号用于 preview/mapping/commit，header 不计入，物理 start/end line 只用于错误展示。若调整通用 `DocumentFacts` 的含义/名字，必须同时确认既有 XLSX adapter 协议和测试，不偷换字段语义。
2. 明确一次可导入最多 10,000 条逻辑交易以及文件/字段资源上限；超限在读取/预览阶段明确拒绝或提供有定义的分批流程，不能偷偷截断。导出超过可导入范围时应给出准确边界或可选分页，保持 CSV 不替代完整备份的说明。
3. 固定来源身份、重复导入、转义与反转义规则。公式安全不能以不可逆改写备注冒充 roundtrip；header/version/日期/金额格式是公开交换协议，不能由 parser 的容错行为隐式决定。
4. 最少增加真实文本 fixture：逗号/双引号/CRLF/引号内换行/BOM/空行/公式前缀/含前导单引号；9,999、10,000、10,001 逻辑记录边界；同一文件两次预览提交不重复入账。

**状态：设计层已关闭（原审查者复核）。**修订方案第 95–101 行固定格式记录/表头/类型/金额日期、可逆自由文本前缀、来源身份和重复/冲突语义；preview 使用逻辑交易号，物理行只定位错误，通过新增可空 logicalRecordCount/previewRecordCount 保留旧 XLSX 调用语义。导入和导出均最多 10,000 条，10,001 提前拒绝且不截断，读取 32MiB/字段 8KiB 上限明确；quoted newline、原始单引号、公式前缀及数量边界 fixture 已列入验收。计划第 84 行与该规范一致。实现后必须检验真实文本往返及 parser→preview→commit，设计关闭不预判 CSV/表格工具的实际互操作结果。

## PLATFORM-03 · P2：乱序 version 的暂存方案需明确仍为磁盘流式，且旧摘要元信息不能被候选计数覆盖

**方案位置：**方案第 82–83 行；计划第 67–69 行，尤其第 68 行“从根对象暂存并按版本校验”“补新实体零计数”。

**现有证据：**

- `platform/android/src/main/kotlin/com/dawnyew/platform/files/JsonStreamCodec.kt:10–15` 的 `PreparedBackup` 仅保留已验证 metadata、文件 handle，明确不保留整份 dataset tree。
- 同文件 `:44` 定义生产流式路径没有 backup-wide 记录数/字节天花板；`:52–73` 使用私有磁盘 CanonicalRecordStore、空间检查、取消与异常清理。
- 同文件 `:32–35` 的摘要依赖 metadata 和实体序列；`GenerationRepository.kt:343–359` 的候选摘要计数必须等于 `prepared.metadata.counts`。

**触发及影响：**合法大备份可以把 `formatVersion` 放在 `entities` 后面。当前计划中的“根对象暂存”尚未确定是整个 JSON 树还是有限 record 的磁盘 spool；若实现为 `parseToJsonElement/readText`，将丢失既有大备份的内存边界。v1 验证后直接给同一 metadata 补新实体零计数，又会把“原文件摘要覆盖的计数/版本”与“当前数据库要求的实体计数”混成一个对象；后续再次摘要或导出时容易得到与原 checksum 不一致的所谓已校验对象。

这不是现有实现漏洞；是新增版本分派时必须关闭的设计缺口，不能等两个模块各自假定对方维护元信息。

**关闭要求：**

1. 明确生产路径仅逐 record/有界 chunk 解析，version 未知时将已知 v1/v2 联合实体记录临时落私有磁盘；根 scalar metadata 单独收集；禁止整份 root/entities DOM 或全文件字符串。
2. source version 确认后，对该版本精确实体/字段集、计数和原始规范摘要做完整校验；未知 version/未知实体/超长 scalar/重复键/空间不足/取消全部关闭并清理暂存。
3. `verifiedSourceMetadata` 保持原版本/原计数/原摘要不可变；另建 `candidateCounts/currentEntityView` 表示 v1 到当前 schema 的空实体提升。PreparedBackup 的 verified 身份不能由改写 metadata 伪造。
4. 测试实际 `JsonStreamCodec.prepare → GenerationRepository.prepareRestore → replaceCandidate` 链，含 entities-first 的 v1/v2、大记录数、磁盘满、取消以及 source metadata/当前 counts 不相互污染。仅内存 `BackupProtocol.restore` 通过不足。

**状态：设计层已关闭（原审查者复核）。**修订方案第 88–89 行明确保持 sourceFormatVersion/sourceCounts/sourceDigest，独立 expectedCandidateCounts 只承担旧版本空实体提升；生产按记录私有磁盘暂存、读完元信息按源版本完整校验，不创建全文件 DOM，并持续保留取消/空间/清理边界。计划第 67–69 行要求乱序字段、实际生产恢复链、大于 20MiB/10k 记录及候选失败/过期验证；resolution 的 D8/PLATFORM-03 同时列明大文件/乱序/空间/取消/加密生产链测试。方案第 101 行另外明确 DWYBAK v1 封装/profile/KDF/JNI/金向量不改。实现后的审查仍必须检查 source metadata 不被 candidate 视图改写、并读到失败测试证据。

## 已核对且未形成阻塞项的部分

- 离线边界合理：权限 merge remove、无云依赖、本地 provider 白名单、登录/会员/网络入口无副作用；无将参考 APK 资源或商业 SDK 带入构建的计划。
- Widget 在方案第 91 行及计划第 86 行已收敛为不保存金额的显式本地 Intent 快捷入口，避免桌面数据泄露。实现时仍需覆盖冷启动、已锁定启动、进程死亡后入口以及多个 widget 的 PendingIntent 身份；不能把外部传入 epoch 当作认证凭据，也不能从 Intent 直接提交交易。配置/预览只包含非敏感静态内容。
- schema 3→4 在候选 generation 上执行、保留旧保护副本、严格 v1 校验后提升、v2 新实体统一候选验证均可行。应继续保留升级故障/未确认回执的既有测试，而不是只测建新库。
- JSON `formatVersion=2` 与加密封装 `DWYBAK v1` 是不同版本：`BackupCrypto.kt:189–202` 的固定 header/profile 应保持不变，v2 JSON 作为原封装中的明文载荷即可；本轮不需要改变 crypto JNI、KDF 参数或金向量。建议在 API contract 明写这点，避免名字同为 version 的误用。
- 构建计划覆盖 `check`、独立 OCR 资产、Python 发布脚本、正式 release；知道 Debug instrumentation 不等于 Release native 验收。最终报告必须区分实际执行与 UP-TO-DATE/cache、编译与设备；不为截图移除安全窗口。

## 复核方式

本次已读回修订 spec、plan 和 resolution，确认三个问题都有具体设计决策及对应实施/验证要求；主 agent 可将 resolution 中 PLATFORM-01/02/03 的平台复核状态更新为“设计层已关闭”。共享 ID 中 D7/D8 的领域结论仍由领域审查者独立确认。平台 Gate 0 无剩余阻塞，但只有全部审查者完成复核后才进入实现。

上述平台审阅不替代领域审查者对账户资金、借还/存钱、预算和周期日历业务语义的独立判断。实现结束后还需按最终实际 diff 再次 review，并分别报告主机测试、编译、Release JNI 和实际设备验证；不把本次设计关闭记录作为功能已实现证据。
