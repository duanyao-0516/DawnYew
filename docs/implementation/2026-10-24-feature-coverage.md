# DawnYew 离线改版实际功能覆盖矩阵

> 首轮历史记录：登录/会员空入口和此轮视觉验收已被用户后续纠正要求替代。当前实现与验证见 [纠正版本验证](2026-10-24-correction-verification.md)，本页不证明最新入口、密度或交互已完成。

日期：2026-10-24。对象为本次共享工作树的实际源码；本报告只读业务/UI/平台实现并写文档，没有运行构建或设备操作。

## 口径与依据

- 参考：[薄荷记账完整功能与 UI 分析](../../.local/research/bohe-20261024/薄荷记账-完整功能与UI分析.md)，其中 542 组是采集状态，不是 542 个独立页面；参考自身也有仅目录、表单预览和未验证分支。
- 本次交付范围：[已批准技术方案](../superpowers/specs/2026-10-24-offline-finance-redesign.md) 第 2～5 节。它取代开发前差距审计中尚未决定的扩展建议；不会把参考 43 项设置、十种金额组件或全套统计编辑器都宣称为已实现。
- 实装：真实可达路由连接状态、查询或命令/平台动作；不表示屏幕已由本报告在真机操作。空页：入口可达，只有通用标题与返回，正文为空，没有假保存或假业务数据。保留：已有本地能力仍有可达入口，本轮继续消费。
- UI 总路由：`shared/ui/.../DawnYewApp.kt:122`；空页实现：`FinanceComponents.kt:137`。`else -> RestrictedPlaceholderPage()` 只为已存在入口提供目标，不能把未显示的入口算成覆盖。
- 数据链：`FinanceController` → `LedgerRepository` → `DomainEngine` / Room；十四类新财务事实和旧账单进入同一 state/事务/备份，UI 不另存余额。精确声明见 [API 合同](2026-10-24-finance-api-contract.md)。

## 五个一级页

| 参考页 | 实际路由与源码 | 当前范围 |
| --- | --- | --- |
| 主页 | `首页` → `FinanceHomePage`，`HomeScreen.kt:289` | 实装。账本切换、选月、净支出/收入/结余/趋势、最近三笔、条件未入账、预算、日历日期下钻、存钱计划；搜索和金额入口可达。AI 为可达空页。 |
| 账户 | `账户` → `AccountsPage` | 实装。净资产/资产/负债、隐藏金额、八类分组、隐藏账户、创建/编辑/归档/受引用保护的删除、详情和账户流水。资产趋势入口为空页。 |
| 统计 | `统计` → `FinanceStatisticsPage`，`StatisticsScreen.kt:154` | 实装三默认面板，独立月/年请求、详情与流水下钻；多账本选择保留。17 项配置目录可达，配置目标均为空页。 |
| 更多 | `更多` → `MorePage` | 实装两组八卡与真实本地汇总，分期为空页；既有五个工具移到工具箱。 |
| 我的 | `我的` → `ProfilePage` | 实装本地记账天数、活动笔数、净资产，参考 20 项入口均保留；另加工具箱、回收站、设置，长按底栏“我的”保留 Debug。登录、会员、消息为空页。 |

五 Tab 底栏只在一级态显示；账户等对象态使用 `FinanceNavigation.id` 和来源栈返回。普通编辑、财务弹窗、金额算式有未保存确认与 busy 门。暗色、IME 避让和触控语义存在于共享组件；200% 字体及全部屏幕的实际设备表现须以主任务设备记录为准。

## 首页 11 项目录

`ProfileScreen.kt` 的 `homePanelTitles` 与参考顺序一致。目录行统一打开 `配置{标题}` 空页；下表“实装”指首页本身的默认数据面板，不指可编辑配置。

| 序号 | 标题 | 实际能力 / 空页理由 |
| --- | --- | --- |
| 1 | 本月支出 | 实装净支出、收入/结余和月内趋势，图点/卡头到真实范围流水；配置空页。 |
| 2 | 最近交易 | 实装独立三条 Activity 查询，Entry/Transfer 均可详情；配置空页。 |
| 3 | 预算概览 | 实装单账本月预算、剩余/超出与进度，跳预算管理；配置空页。 |
| 4 | 预算追踪 | 仅目录/配置空页；批准范围不实现额外受限面板配置。 |
| 5 | 账户追踪 | 仅目录/配置空页；参考开关有 VIP 阻断。 |
| 6 | 分类涨跌 | 仅目录/配置空页；没有实现其跨月图表。 |
| 7 | 日历视图 | 实装 SQL 按日汇总、月份切换、单日流水下钻；配置空页。 |
| 8 | 收支对比 | 仅目录/配置空页；普通收支总额另在默认面板展示。 |
| 9 | 账单概览 | 仅目录/配置空页；未实现信用卡账单周期模型。 |
| 10 | 分类倾向 | 仅目录/配置空页；未实现雷达图。 |
| 11 | 存钱计划 | 实装非归档计划的手动进度/目标，可到存钱页；达到目标后由“完成并归档”移出。配置空页。 |

额外条件面板“未入账”不计入 11 项：同月 `recorded=false` ActivitySummary 有记录时显示，列支出/收入/转账并可下钻。它不影响普通收支和预算口径，只影响账户余额。

## 统计 17 项目录

`statisticsPanelTitles` 与参考顺序一致；三默认卡点击标题走真实详情，目录中的配置动作全部为空页。

| 序号 | 标题 | 实际能力 / 空页理由 |
| --- | --- | --- |
| 1 | 分类统计 | 实装原始支出环图、金额/占比、退款/净支出/总笔数、分类与退款下钻；不绘制负扇区。 |
| 2 | 日趋势 | 实装月内日收支趋势及数据行/日期下钻。 |
| 3 | 月度收支 | 实装独立年度范围、十二月收支图和月份下钻；不覆盖当前月结果。 |
| 4 | 多人统计 | 仅目录/配置空页；登录/共享服务不实现。 |
| 5 | 分类收支 | 仅目录/配置空页；不冒称实现额外逐月分类图。 |
| 6 | 年度统计 | 仅目录/配置空页；默认月度收支的选年不等价于跨年图。 |
| 7 | 一周统计 | 仅目录/配置空页。 |
| 8 | 账户统计 | 仅目录/配置空页；实际账户余额/流水在账户页。 |
| 9 | 单笔最高 | 仅目录/配置空页。 |
| 10 | 分类均价 | 仅目录/配置空页。 |
| 11 | 笔数统计 | 仅目录/配置空页；其他页面真实笔数不等价于此图。 |
| 12 | 周末统计 | 仅目录/配置空页。 |
| 13 | 金额分布 | 仅目录/配置空页。 |
| 14 | 项目统计 | 仅目录/配置空页；项目管理和条件流水实装。 |
| 15 | 商家统计 | 仅目录/配置空页；商家管理和条件流水实装。 |
| 16 | 标签统计 | 仅目录/配置空页；标签管理和条件流水实装。 |
| 17 | 季度统计 | 仅目录/配置空页；参考开关有 VIP 阻断。 |

批准范围只要求三默认数据面板，不包含收费统计结构编辑器、任意统计对象/数值/过滤配置或面板排序持久化。前期差距审计中的这些建议不能作为本次已交付能力。

## 更多八入口

| 入口 | 实际路由/命令链 | 当前能力 |
| --- | --- | --- |
| 借入 | `ObligationsPage(BORROW)` → SaveObligation / RecordObligation | 新单原子建立专属应付账户和首笔本金；追加借款、部分/全部还款、修改/回收/恢复资金、资料编辑/归档、单据流水。 |
| 借出 | `ObligationsPage(LEND)` | 同一链路，应收账户，追加借出和收款。 |
| 报销 | `ObligationsPage(REIMBURSEMENT)` | 同一链路，应收账户，垫支与报销收款；不伪装支付或银行操作。 |
| 往来 | `ContactsPage` → SaveContact / DeleteContact | 人员新增/更名/归档/受引用保护的删除、汇集本人所有单据资金流水。 |
| 周期 | `RecurringPage` → SaveRecurringPlan / ExecuteRecurring | 八频率、有限/持续、首期到期生成、编辑未来模板、暂停/继续、执行/跳过、归档/无历史删除、凭证与关联流水。前台协调器有 100 次上限、显式继续和逐计划失败原因。 |
| 分期 | 通用空页 | 参考最终保存走 VIP，批准范围不实现试算/分期创建，不产生虚假的待还金额。 |
| 预算 | `BudgetPage` → SetBudget / SaveBudgetAllocation / ReallocateBudget | 月总预算、分类额度、独立预算消费、流水下钻、同月额度转移及反向撤回、整月删除配置。 |
| 存钱 | `SavingsPage` → SavingsPlan/Movement/Wish 命令 | 四场景、目标/无目标、手动存取、可选资金转账、愿望完成/取消、记录修改/恢复、完成并归档。 |

无到期日的单据只显示未结清；设置到期日后才判逾期。待入账本金不增加可结算余额，待入账收付不预占额度，转入账时复核超额。普通转账列表不能绕过单据/存钱命令修改来源关系。

## 八种账户、八种周期、四种存钱场景

| 账户 UI | 领域类型 | 当前范围 |
| --- | --- | --- |
| 现金 | CASH | 手工账户，期初余额/日期、备注、隐藏/计入资产/归档、真实流水。 |
| 储蓄卡 | BANK | 同上；不含定期存款、银行绑定或自动支取。 |
| 信用卡 | CREDIT | 同上；负余额计负债，不含账单日/还款日自动账单。 |
| 网络支付 | E_WALLET | 同上；不连接支付账户。 |
| 投资 | INVESTMENT | 同上；无行情、证券绑定、收益同步。 |
| 充值卡 | PREPAID | 同上；仅手工余额和交易。 |
| 应收账户 | RECEIVABLE | 可手工建普通应收；单据专属应收另有一对一所有权，不能直接写入普通交易。 |
| 应付账户 | PAYABLE | 可手工建普通应付；借入专属应付同样受单据所有权保护。 |

| 周期 UI | 领域频率 | 日期行为 |
| --- | --- | --- |
| 每天 | DAILY | 按日递增。 |
| 每周 | WEEKLY | 每 7 日。 |
| 每两周 | BIWEEKLY | 每 14 日。 |
| 每月 | MONTHLY | 原始日锚点，月末夹至当月末；后续仍回原锚点。 |
| 每年 | YEARLY | 原始月/日锚点，处理闰年。 |
| 每季 | QUARTERLY | 每三个月，保留原始日锚点。 |
| 工作日 | WEEKDAYS | 仅周一至周五；不声称有在线节假日/调休表。 |
| 周末 | WEEKENDS | 仅周六、周日。 |

所有频率只生成到期项，每期有唯一 planId/index 凭证；已处理后 anchor/frequency 冻结，删除交易不重新生成；后台不唤醒、不联网。实际入口 `RecurringEditor`/`RecurringPage`，算法 `recurringDate`，前台门 `MainActivity.updateRecurringAccess` → `RecurringCoordinator` → `AppController.performAutomatic`。

| 存钱场景 | 领域值 | 当前规则 |
| --- | --- | --- |
| 存钱挑战 | CHALLENGE | 正目标必填，手动存取。不是 52 周或 365 天自动算法。 |
| 梦想计划 | DREAM | 正目标必填，手动存取。 |
| 目标随心 | GOAL | 正目标必填，手动存取。 |
| 随手攒钱 | FLEXIBLE | 目标可空，手动积累。 |

进度只由 movement 得出且不可负；双空账户只改进度，单端明确外部资金，两端按用户所选 from→to。愿望以 fulfilledMovementId 为唯一完成事实，完成/取消同时处理 movement 与可选 transfer。已有记录的计划保留历史并归档。

## 我的 20 个参考入口

| 序号 | 参考入口 | 实际路由与能力 | 状态 / 取舍 |
| --- | --- | --- | --- |
| 1 | 分类 | `分类管理` → CategoryManagement；支出/收入分类新增、更名、启停、置顶、受引用保护删除和下钻 | 保留并扩展；无独立转账分类，因为转账不沿用旧 Category 交易种类。 |
| 2 | 项目 | `项目` → DimensionsPage(PROJECT) | 实装新增/更名/置顶/归档/受引用保护删除、流水下钻和记账选择。 |
| 3 | 商家 | `商家` → DimensionsPage(MERCHANT) | 实装同上，不调用在线商家库。 |
| 4 | 标签 | `标签` → DimensionsPage(TAG) | 实装同上，普通账单可多标签；筛选 UI 当前选一个标签，查询 API 支持集合 ANY。 |
| 5 | 导入 | `导入` → FileAndWidgetPage → PlatformActions.importCsv | 实装 DawnYew CSV v1、预览选择/重复与冲突、分类新增摘要、CommitImport 和批次撤销；不冒称微信/支付宝/xls/xlsx。 |
| 6 | 导出 | `导出` → FileAndWidgetPage → PlatformActions.exportCsv | 实装账本/日期选择、普通收支 CSV、排除项/关联丢失计数和 SAF 创建。完整迁移另走备份。 |
| 7 | 共享 | `共享` → 空页 | 在线账号/成员服务不实现，无邀请表单或发送。 |
| 8 | 账本 | `账本管理` → LedgerManagement | 保留新增、更名、置顶、归档、空账本删除；不复制参考多人权限/周期起始配置。 |
| 9 | 自动 | `自动` → 本地截图识别说明/选择截图 | 保留本地 OCR 闭环，新增聚合入口；不是无障碍自动读取、云识别或截屏常驻服务。 |
| 10 | 组件 | `组件` → requestWidget / QuickEntryWidget | 新增真实静态快捷组件及系统预览：支出、收入、截图识别；预算进度/日历组件为空页。 |
| 11 | 日志 | `日志` → 空页 | 参考同步日志需要登录；本地 receipt/ImportBatch 仍真实存在，但不伪装成云日志页。 |
| 12 | 备份 | `备份` → BackupPage / Android 备份链 | 保留 JSON/加密导出、恢复预览/确认、保护副本恢复/清理；全量 v2 覆盖新财务图，兼容 v1；WebDAV 空页。 |
| 13 | 货币 | `货币` → 固定人民币 CNY 说明 | 实装本地说明；“在线汇率”为空页。无多币种/股票/商品行情。 |
| 14 | 安全 | `安全` → SecurityPage / setAppLock | 保留强生物识别或设备凭据应用锁；数字/手势密码目标为空页。 |
| 15 | 活动 | `活动` → 空页 | 线上运营活动/兑换服务不实现。 |
| 16 | 提醒 | `提醒` → ReminderPage | 实装前台本地到期周期和未结清往来列表，带到期日或未设置到期日文案与对象下钻；没有每日通知调度。 |
| 17 | 客服 | `客服` → 空页 | 外部联系/网络服务不实现，不发送消息。 |
| 18 | 手册 | `手册` → LocalHelpPage | 实装本地记账、账户/计划、保存/备份、截图识别说明；不是参考 12 篇文章复制。 |
| 19 | 评分 | `评分` → 空页 | 不跳商店或发布评分。 |
| 20 | 分享 | `分享` → 空页 | 不生成对外推广内容或调用发送目标。 |

顶部登录、会员、消息均可达空页；设置为真实本地页。额外保留工具箱、回收站与 Debug：工具箱的调色盘/尺子/噪音测量/做个决定/石头剪刀布继续使用既有实现；Debug 可生成/撤销测试批次。噪音工具的麦克风本机能力与记账联网无关。

## 关键业务与平台闭环

| 能力 | UI → 实际行为 | 边界 |
| --- | --- | --- |
| 金额与手工记账 | 金额页四则/括号计算器 → EntryForm 或 TransferEditor；普通 SaveEntry / AddRefund 原子保存 Details/Tag | Long 分值；账户可空；转账双端同账本且不同；保存后继续偏好只用于普通手动新增。 |
| 日期与资料 | 分类网格、账户、备注、日期/时间、项目/商家、多标签、入账/预算/对账 | 目前日期时间用校验后的 ISO 文本字段；不宣称已复刻参考日期/时间滚轮。 |
| 编辑与回收站 | EntryDetail 编辑/退款/移账/删除；FinanceRecyclePage 恢复删除组/普通转账、来源业务恢复、允许的永久删除 | 退款随原支出删除组；来源转账按业务命令处理；重复回执与旧导入修改保护保留。 |
| 流水与筛选 | ActivitiesPage 范围合计/日组合计、日期分组、SQL keyset 分页，keyword/kinds/date/minmax/category/account/project/merchant/tag/recorded/excluded/reconciled | 合计来自独立 activitySummary SQL 槽，绑定 scope/dataset/version，不合计当前已加载页。所有条件在库端统一处理，固定时间/家族/id 降序；当前无自定义金额排序、批量编辑/对账、图片/作者筛选。 |
| 预算 | 预算月/账本选择 → 独立预算统计槽；子额度、转移/撤回、消费下钻、删除整月 | 未入账仍计预算，退款依父项当前预算资格；分配总和不超过总预算，转移不改变合计。 |
| 设置消费 | LocalSettingsPage → SetPreference → consumer | theme；amountDisplay 只改摘要格式；lastLedgerId 默认账本；defaultAccountId 仅合法同账本非虚拟账户；continueEntry。自动同步为空页；不保存无消费者的假设置。 |
| CSV | 文件页 → MainActivity SAF → DawnYewCsvCodec → ImportPreviewer → CommitImport；导出先预览再创建文件 | 严格 v1 framing，32 MiB/10k 逻辑记录/8 KiB 字段，公式文本 apostrophe，来源幂等/冲突、疑似重复明确确认、只创建所选新增行引用分类。预览绑定 dataset/version/session/database epoch。 |
| OCR | 自动/记账/流水/Widget → chooseScreenshot/recognize → AndroidOfflineOcrEngine / PaymentScreenshotParser → EditorDraft | 本机模型，原图不持久化；金额/时间/账本/类型与疑似重复需确认；不自动提交。 |
| 桌面组件 | Manifest 注册 QuickEntryWidget；静态 RemoteViews/previewLayout → 显式 MainActivity Intent | 无金额/账本名/交易摘要，updatePeriodMillis=0，不周期刷新财务；进入后先过原授权/锁定门，只导航不直接写账。系统是否接受 pin 依桌面能力，失败给真实指引。 |
| JSON/加密备份 | BackupPage → MainActivity → JsonStreamCodec / GenerationRepository / 候选检查与发布 | v1 原九类摘要先验证再提升；v2 精确 23 类；加密 envelope/密码保护原样保留。保护副本与清空仍真实，应用锁不会因清空丢失。 |
| 安全/离线 | SessionCoordinator、敏感操作认证、外部 picker lease、epoch、FLAG_SECURE、后台遮盖 | 源 manifest 显式移除 INTERNET/ACCESS_NETWORK_STATE；最终合并 manifest/APK 权限以主任务审计为准，本报告不据源文件代替打包验证。 |

## 当前差异、发现与验证限制

1. 相比参考，固定默认面板之外的配置均为空页；账户历史资产趋势入口为空页，资产比例无独立入口；信用账单日/定存/投资收益、自定义账户分组/拖动、十种金额组件、联网货币、多语言/定时暗色、每日通知、43 项记账开关、统计结构编辑器均未实现。它们不属于批准的本地闭环范围，不用“实装”字样覆盖。
2. 审阅发现的流水范围/日组合计缺口已闭合：`FinanceController.activitySummaryRead` 独立请求 repository.activitySummary，ActivitiesPage 显示范围收入/支出/退款/转账及笔数、日组金额；scope/dataset/version 相符且非加载/错误才显示数值，不取分页内存和。已只读复核路由和刷新/清理链；主任务报告对应 facade 定向测试通过，设备表现另记。
3. 审阅发现的“拆分、手续费”入口缺口已闭合：EntryForm 附加区加入两项，onRestricted → finance.open → 标题/返回空页，草稿仍由 EditorDraft 持有，返回恢复原表单。两项业务均未实现，仍是参考 VIP 边界。此次接在普通账单表单，未宣称完整复制转账的参考附加字段布局。
4. 周期支出勾“不计入预算”再切转账的阻断已在源码关闭：类型切换到非支出清 excluded；组装模板时只有 EXPENSE 能带预算排除，TRANSFER 的项目/商家明确清空。领域约束保留；已只读复核 UI 作者改动，实际设备覆盖另记。
5. 当前表单用标准 Compose 对话框/文本日期等独立实现；没有逐像素复制参考截图、第三方图标、背景图片或 SDK。未执行的屏幕、全部筛选组合、辅助功能和旋转/大字体效果不能从 JVM 测试或编译成功推导。

本报告没有执行任何真机点击、文件选择、Widget pin、系统认证、OCR 或 Release 回归。上述“实装”只证明源码中存在可达的完整职责链；运行和交付证据由主任务最终构建、测试 XML、权限/JNI/Lint 审计与设备验收报告独立记录。

## 实施计划 Task 1～6 对照

| 计划 | 真实落点 | 对照结果 |
| --- | --- | --- |
| Task 1：领域 API/资金不变量 | FinanceModels、FinanceDomain、FinanceQueries、FinanceValidation、Domain/Validation/LedgerEntityBatch/Import/Statistics、FinanceTest、API 合同 | 声明/校验/复合命令/旧命令兼容/资金与预算闭环存在。计划列出的 FinanceCommands.kt 没有独立创建，FinanceAction 实际在 FinanceModels.kt；不是缺一个实现模块。 |
| Task 2：周期与统一查询 | FinanceQueries 中的 recurringDate/recurringNextOccurrence/Activity 参考查询；FinanceDomain 执行；Models 中 repository API；RecurringCoordinator | 八频率、凭证、批上限、派生余额/进度、统一投影实装。计划中的 RecurringSchedule.kt/FinancialActivity.kt 合并在现有财务文件。测试已见月末、闰年、工作日/周末、批上限、跳过、删结果不再生成；任务核对发现季度/暂停/未来拒绝缺直接断言，经主任务授权补齐 3 个真实领域命令回归，FinanceTest 共 19 项，等待主任务统一运行，未以旧 16 项通过替代。 |
| Task 3：迁移与候选 | FinanceEntities/Rows/Persistence/Migration/Constraints、FinancialCandidateRelations、FinancialActivityQueries、Streaming/CandidateVerification、Migration/FinancialStorage/FinancialActivityQuery tests | v4 新表、事务、快照/候选、完整关系校验、SQL 分页/日历/预算落点完整；独立审阅见 [存储审阅](2026-10-24-code-review-storage-by-core.md)。 |
| Task 4：备份与流式链 | BackupProtocol/RecordSchema、JsonStreamCodec、GenerationRepository、FinancialBackupCodecTest、BackupVersionCompatibilityTest | 旧摘要/新 23 类、source metadata、候选 counts、乱序流式入口落点完整；完整大文件/加密/故障与打包结果由最终验证报告逐项列证，不以源码表替代。 |
| Task 5：Presentation/UI | AppController/EditorDraft/FinanceController/AmountCalculator/RecurringCoordinator；DawnYewApp 和各业务 Screen/Form | 五页、默认面板、业务表单、来源返回、真实设置、20/11/17 目录与原能力均有落点。计划中的 FinanceDraft 是职责描述，具体金融弹窗草稿采用进程内 remember + 稳定 id，普通账单沿用 EditorDraft，未增加第二份财务 state。前述三项审阅缺口已在源码闭合。 |
| Task 6：文件/组件/平台 | DawnYewCsv/ImportPreview/TemplateRegistry、DawnYewCsvCodec、MainActivity SAF/授权、QuickEntryWidget/XML、LocalSettings/Help | CSV 9 项协议测试和 4 项 codec 测试已由主任务执行通过；Widget 注册/静态预览/明确 Intent、前台周期门、本地帮助/设置均有落点。此表不宣称 Widget pin 或每一条 SAF/认证设备路径已验。 |

计划所有权在执行中经主任务明确调整：领域 agent 追加负责 CSV protocol/Android codec，存储 agent 追加实现 RecurringCoordinator，主任务继续统一集成和全部构建/设备。没有因此改变业务层职责或并行运行 Gradle。Gate 1/Gate 2 状态由主任务最终更新，本报告不代打勾。
