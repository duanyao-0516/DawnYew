# DawnYew 离线财务改版实施计划

> 执行方式：当前会话使用 subagent-driven-development；用户已授权自主决策，技术方案和交叉审阅结论是实施依据，不等待额外问答。

**目标：**完整实现已审阅方案中的五页 UI、本地账户/资金往来/计划/预算/维度与文件链路，保持离线及旧数据兼容，通过编译、业务验证、最终独立 review。

**架构：**不改变旧 Entry/Category 线格式；新增实体纳入同一 LedgerState、DomainEngine、Room、代际恢复和备份 v2；统一 FinancialActivity 查询。技术栈仍为现有 Kotlin Multiplatform、Compose、Room、Bundled SQLite 和 Android 本地平台，无新联网依赖。

**方案：**`docs/superpowers/specs/2026-10-24-offline-finance-redesign.md`。详细决策与 review 关闭项写 `docs/implementation/2026-10-24-finance-review-resolution.md`。本计划执行时更新实际结果，不以打勾代替证据。

## 工作树与所有权

不切换当前分支，不覆盖既有三个本地变更，不重建 OCR/crypto 原生资产，不操作薄荷数据。主 agent 唯一运行 Gradle、设备、签名及集成命令。

| 责任区 | 允许修改 | 依赖/交接 |
| --- | --- | --- |
| 领域 agent | `shared/core/**`、finance API contract、自己的测试/说明 | 先固定模型、命令、ActivityScope/API；领域实现后 review 存储协议 |
| 存储协议 agent | `shared/storage/**`、`shared/protocol/**` | 依赖已固定 core API；对 platform codec 提供准确变更要求，由主 agent 接线 |
| UI agent | `shared/ui/**` | 初期布局可先做；接线仅使用约定 AppController/FinanceController API；不自己写 DAO/平台 |
| 主 agent | `shared/presentation/**`、`platform/android/**`、`androidApp/**`、文档/脚本整合 | 维护稳定 API、离线与生命周期、文件/组件、统一构建和 review 关闭 |

共享文件或类型需改签名时先通知依赖方；不得自行覆盖其他 agent 修改。可以读取所有目录。提交/推送不是本次必要动作，先保留可审查 diff。

## Gate 0：方案与基线

- [x] 读取适用规则、旧实现与实采资料；记录既有改动。
- [x] 完成领域、UI、构建三个审计文档。
- [x] 基线 `./dev.sh test --offline --console=plain` 成功（本轮执行但多项 UP-TO-DATE，不能当改版后的新测试）。日志 `.local/dawnyew-baseline-20261024.log`。
- [x] 三 agent 独立交叉审阅完整方案与计划，提出18个条目（含重复根因）。
- [x] 主 agent 修订并记录关闭依据；三位原审查者复核全部关闭，无设计P0/P1/P2后进入实现。

## Task 1：领域 API 与资金不变量

文件：`shared/core/.../Models.kt`、`Domain.kt`、`Validation.kt`、`LedgerEntityBatch.kt`，新增 `FinanceModels.kt`、`FinanceCommands.kt`、`FinanceDomain.kt`、`FinanceQueries.kt` 及针对性测试。

1. 先定义新实体/命令与 QueryScope 分离的 ActivityScope（含 categoryIds 和完整旧 scope 映射）；每种实体可序列化，明确 scalar 字段、键、缺省值与期次唯一键。把完整签名写 API contract，冻结后通知所有人。必须落实修订 spec 的 openingAt/archived、虚拟账户所有权、dueDate、Wish 唯一完成事实与预算历史权威规则。
2. 写失败测试：参考金额金样例、未入账/排除预算分离、转账守恒、借还部分收付/超额拒绝、无账户存钱、账户归档/删除引用。
3. 实现共用内部 mutation helper，复合命令不得递归 DomainEngine.apply 造成多次 contentVersion/receipt；命令外层一次 validate+commit。
4. 原 SaveEntry/AddRefund/MoveEntry/SoftDelete/Restore/Purge/UndoImport/ClearAll/DeleteLedger 接入关系清理；Details 编辑递增 Entry.revision。写旧导入用户编辑后撤销拒绝测试。
5. 实现预算分类额度及转移、维度/人员管理、SavingsWish 与记录修改/删除原子联动；所有金额溢出/负额/跨账本拒绝。
6. 主 agent 运行 `:shared:core:jvmTest`，记录新增测试通过后继续。

## Task 2：周期与统一查询

文件：core 新 `RecurringSchedule.kt`、`FinancialActivity.kt`；查询接口在 `Models.kt` 或现有 repository 文件。

1. 先测 31 日锚点、闰年、季度/工作日/周末、暂停、重复到期调用、跳过、100 期批上限、已删除结果不再生成。
2. 实现八种周期、凭证唯一、保存首期到期执行、一次/批量到期/跳过/暂停/归档，未来操作明确拒绝。已有期次后冻结锚点/频率，跳过消耗期次，批内每笔账单 creationOperationId 唯一。自动命令共用 AppController 单写门且只确认自己的回执。
3. 统一 Activity 投影及内存参考实现，用于验证 SQL 结果；固定复合排序、类型与金额范围、维度/账户/状态过滤语义。
4. 交易计数/日期/账户余额/单据余款/预算可用额/存钱进度全部由同一状态推导；不得保存派生余额第二份。

## Task 3：真实数据库迁移与候选验证

文件：`shared/storage/.../{Entities,LedgerDao,DawnYewDatabase,Migrations,SchemaUpgrade,RoomLedgerRepository,Streaming,CandidateVerification,EntryQueries}.kt` 及新 Finance SQL 文件/测试。

1. 在代码修改前保存现有 v3 fixture 创建/备份数据的稳定测试方法，新增从 1/2/3 升 4 的失败测试。
2. 新表/索引/触发器/外键，版本 4 显式迁移；旧 payload 字节不改。开库验证触发器和 FK；所有实体 rows→domain→rows 一致。
3. 实现差量事务持久化、子父顺序、状态发布；Snapshot/Streaming 批量集和 candidate counts/digest 全覆盖。
4. 统一活动 SQL keyset 分页、条件过滤、日历范围；预算 SQL JOIN Details/退款父项；与 core 参考查询对比，测试 10k 记录和旧游标失效。
5. CandidateVerification 校验同账本、关系/金额/期次、列与 payload，恶意改 payload/索引不得发布。

## Task 4：备份 v2 与平台生产流式链

文件：`shared/protocol/.../{BackupProtocol,RecordSchema}.kt`；`platform/android` 中 JSON codec、canonical store、GenerationRepository/预览相关文件由主 agent 修改。

1. v1 实体集/字段/摘要常量冻结，v2 实体集显式新增，新增 signed decimal string 仅用于规定字段；未知字段/类型仍拒绝。
2. 测试 v1 原始字节恢复、v2 新旧数据往返、manifest 缺/多实体、乱序根字段（entities 先于 version）、负数/超范围/摘要不匹配、重复键。
3. Android codec 仅逐记录私有磁盘暂存并按版本校验，不依赖 JSON 字段顺序，不构建全文件 DOM。完整 v1 校验保留 source metadata 原样，另建 expectedCandidateCounts 新实体补0交给当前库；验证20MiB以上/10k条实际生产链。
4. 加密备份同协议入口；generation owner/token/epoch 检查不省略。测试候选恢复失败不发布、旧预览遇新编辑失效、新实体更改影响候选摘要。

## Task 5：Presentation 与五页 UI

文件：`shared/presentation/.../{AppController,EditorDraft,StatisticsNavigation}.kt`、新增 FinanceController/FinanceDraft；`shared/ui` 的 Theme/AppGlyph/App/Home/Entry/Statistics/More/Management 与新增 Account/Plan/Obligation/Dimension/Placeholder 页面。

1. 固定界面所需 facade：当前 dataset/scope、navigate/back、打开/更新/保存草稿、执行领域命令、Activity 分页、周期到期结果；UI agent 不编造第二份状态。
2. 先写 controller 测试：重复保存同 operationId、错误保留草稿、返回原筛选、切账本/恢复/锁定清草稿、Details 与账单一次保存、周期前台幂等。
3. 落地五页布局与统一金额/空态/加载/错误组件，先真实数据后润色；默认首页六类面板、月历/最近/预算/存钱均可下钻；金额计算器及完整表单。统计月分类/日趋势与年月度收支分开请求身份，环图用原始支出非负份额，退款/净额单列。
4. 实现八类账户、往来/周期/存钱/预算/维度列表及表单/详情、归档/隐藏/回收站；实时汇总可核对金样例。
5. 我的二十入口有真实目标或受限空页，保留原工具箱/Debug/OCR/备份/锁定；11/17目录可达；受限目标只有标题/返回和空内容，限制原因在开发矩阵记录。无假成功或未使用开关。
6. 输入法/大字体/小屏/Back/屏障与 TalkBack 描述；图表保留非颜色区分与数值详情。原页面状态测试应修正范围而非删除失败测试。

## Task 6：本地文件、快捷组件与平台整合

1. 按 spec 4.1 冻结的 DawnYew CSV v1 列/来源/转义规范实施。逻辑记录编号独立于物理错误行范围，扩展 DocumentFacts.logicalRecordCount 与 previewRecordCount 保持旧调用语义；一次最多10,000条/32MiB、超限提前拒绝不截断。先写 parser/roundtrip/公式前缀/换行/重复导入/边界测试，再 SAF 导出和预览→确认提交。
2. CSV 仅表示标准普通收支交换，完整财务关系由 JSON/v2 保存；导出对不能无损表示的数据明确说明，不能静默声称全量。未知第三方模板维持“待适配”。
3. 快捷记账 Widget 使用显式本地 Intent，不存金额、不绕过锁；配置/预览和会员占位留档。平台根入口验证 action/epoch 后转到草稿，不凭 Intent 提交交易。
4. 通过统一前台就绪协调器触发有限到期处理（包含无锁冷启动/返回、认证、picker返回、仓库ready及恢复后），每次新提交复核前台/sessionEpoch/databaseEpoch、无用户busy/pending/外部lease；后台/恢复/清空取消并重置。系统操作只核验确认自己的回执，不能代用户确认未知结果；旋转/重复事件不突破100期批上限。现有 OCR picker/session/native 生命周期回归。
5. 更新本地帮助、CSV说明、设置实际消费链；不会发消息或打开外部服务。

### 实际实现文件映射（2026-10-24）

本节只校准计划文件名与实际职责，不更新下方验证 Gate。逐入口实装/空页/保留边界见 `docs/implementation/2026-10-24-feature-coverage.md`。

- Task 1 的 FinanceCommands 声明与实体集中在 `FinanceModels.kt`，命令逻辑为 `FinanceDomain.kt`，校验另分 `FinanceValidation.kt`；没有再建同名空壳文件。
- Task 2 的 RecurringSchedule / FinancialActivity 职责集中在 `FinanceQueries.kt`；前台周期在 `shared/presentation/RecurringCoordinator.kt`，统一生产 SQL 在 `shared/storage/FinancialActivityQueries.kt`。
- Task 3 的迁移/实体/行/事务/约束分为 FinanceMigration / FinanceEntities / FinanceRows / FinancePersistence / FinanceConstraints / FinancialCandidateRelations；旧库升级仍从 DawnYewDatabase 显式注册。
- Task 5 普通账单继续使用 EditorDraft；金融弹窗使用进程内草稿与稳定实体/operationId，由 FinanceController facade 和同一命令门保存，没有单独创建 FinanceDraft.kt 或第二份财务状态。流水范围和按日合计使用独立 activitySummary 查询槽。
- Task 6 的 CSV 为 `shared/protocol/DawnYewCsv.kt` 与 `platform/android/tabular/DawnYewCsvCodec.kt`，SAF/会话/组件由 MainActivity 与 QuickEntryWidget 接线。经主任务追加授权，领域 agent 实施 CSV codec/protocol，存储 agent 实施 RecurringCoordinator；主任务始终独占构建/设备。
- 计划核对补齐 FinanceTest 的季度月末真实执行、暂停/恢复与重复执行、同日未来期生成/跳过拒绝三项断言；是否通过以主任务本轮日志为准。

## Gate 1：集成与交叉代码 review

- [x] 主 agent 跑针对性测试后执行完整 check；真实失败已修复，最终376项主机测试通过。
- [x] 领域 agent 审阅存储/备份/周期协调器，存储 agent 审阅 core/protocol，UI agent 审阅 presentation/platform；另有 fresh UI 和平台 reviewer 独立审阅，主 agent 复核跨模块边界。
- [x] 以集成实际 diff 为对象，给问题 severity、路径行号、可复现触发与修复建议；自己实现的模块另有独立人看。
- [x] 每项修复后原审阅者复查，新增有意义回归；审阅日志逐项关闭。无未解决 P0/P1/P2 才进入交付。

## Gate 2：验证与交付

按构建审计精确命令执行：

```sh
./dev.sh check --offline --console=plain
python3 scripts/build-ocr-verify.py
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/bytedance/Library/Android/sdk python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v
./dev.sh release --offline --console=plain
```

核对本轮 XML counts/failures/skipped、Release Lint/JNI、APK 权限/签名/SHA256/ZIP 16KB 对齐。有可用兼容设备时运行 instrumentation 和离线 UI 金样例；不能卸载覆盖用户数据。实际演示链路含创建→重启读取→编辑→删除/恢复→备份恢复及主导航。对未做的设备范围明确记录。

最终产物：本方案/计划、API contract、决策与 review 关闭记录、功能覆盖矩阵、验证报告、APK 路径。检查既有三处修改未被覆盖，无工具遗留临时脚本混入源码。只有目标实际实现和验证结束才把 goal 标记 complete。


## 实际完成记录

Task 1～6 已完成，逐项映射见 [功能覆盖矩阵](../../implementation/2026-10-24-feature-coverage.md)。最终交叉审阅无未关闭 P0/P1/P2，不能把这结论延伸为全部参考功能都已实现。

Gate 2 已执行全套离线 check、原生资产校验、13项发布脚本测试、签名 Release 和权限/签名/对齐核验；隔离真机包10项测试全部通过。截图、性能和未执行设备范围见 [最终验证报告](../../implementation/2026-10-24-finance-verification.md)。本轮不提交/推送，也不安装覆盖设备原有正式包。
