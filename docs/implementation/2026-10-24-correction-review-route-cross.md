# 纠正轮独立交叉审阅：路由、详情与面板数据

审阅人：route/detail agent。审阅范围为其他作者的 `DawnYewApp.kt`、`DebugNavigationItem.kt`、`AppController.kt`、`GenerationRepository.kt`、`HomeScreen.kt`、`StatisticsScreen.kt`、`PanelManagement.kt`，并交叉检查 core 的 PanelCatalog/PanelQueries/AccountHistoryQueries、storage DashboardQueries 和 presentation DashboardController。仅源码审阅；本 agent 未运行 Gradle 或 ADB。业务详情是本人实现，下面对本人的修改只称自审，不替代其他 agent 审阅。

## 发现及处理

| 编号 | 优先级 | 真实触发与影响 | 修复责任/状态 |
| --- | --- | --- | --- |
| R1 | P2 | 选中资金账户/往来单据/周期/存钱后，根顶栏只写“账户详情”等固定名，详情首卡也只写当前余额/每期金额/场景，用户无法辨认当前对象 | 已修并读回：根 objectTitle 解析真实对象名并省略超长顶栏；四类详情首卡完整显示名称，可换行 |
| R2 | P2 | 统计详情选择“收入”或具体分类后点数据行到流水，返回时 composable 重新进入，`remember` 的 kind/categoryId/scroll 丢失；分类收支甚至退回空选择。账户详情选择历史月后查看流水返回也重置当月 | 已修并读回：根 financePages 按 account:id / statistics:page 建 SaveableStateProvider；统计 kindName/categoryId 与列表状态、账户历史月份/展开状态使用 saveable；还需真机下钻返回验证 |
| R3 | P2 | Home 的未入账条件卡放在 visible.forEachIndexed 的 index==0 后；10 个普通面板全部关闭时，有待入账交易也不显示，而它不属于这10项开关 | 已修并读回：visible.isEmpty 且 pendingSummary.activityCount>0 时单独渲染条件卡 |
| R4 | P2 | budget_tracking 用 expense-refunds 原值作“已用”，当月跨月退款大于支出时出现负已用/剩余大于预算；总预算既有 budgetUsed clamp>=0，两个面板口径不同 | 已修并读回：分类已用 clamp>=0；原始退款保留；DashboardTest 覆盖跨月退款并对照 budgetUsed |

| R5 | P2 | count 详情只有全部/收入/退款，缺支出笔数；分类收支从支出切收入后保留不兼容的旧分类，得到必然空结果 | 已修并读回：count 具备全部/支出/收入/退款，切换类型清空分类 |
| R6 | P2 | 新 FinanceHomePage 已无 onRange 调用，日历月份变静态文本；实采第61/73行明确月份可选，缩布局时丢掉既有切月功能 | 已修并读回：紧凑 HomeMonthPicker 由本月支出标题和日历月份打开，调用真实 onRange |

本轮未发现 P1。R1–R6 的源码修复已读回关闭；设备行为仍由 root 的真机验证记录确认。

## 已确认的正确链路

- 原49个空目标已从可见回调删除；根 fallback 不再是空 Box，而是明确失效页及返回。面板目录现在是真实开关/排序，不跳“配置XX”路由。
- Profile 不显示登录、会员、共享、同步、在线日志/活动/客服/推广等入口。金额表单不显示拆分/手续费，More 不再显示无模型的分期，组件页只保留真正 requestWidget 的功能。资金账户保留。
- 根已传入 AccountsPage 的 history/loadHistory/accountActivities/loadAccountActivities/onOpenActivity，并将资产趋势路由接到 AssetHistoryPage；Obligations 的原转账记录导航和 EntryDetail 原支出/退款双向导航已接线。
- `FinanceController.openRoute` 保留原 navigation+finance frame；对象选择、资金记录详情和 Activities 下钻用同一栈。sourceLedgerIds 来自原范围或实际 drill scope，返回恢复对象 id。新增两种财务读接口经 GenerationRepository IO/mutex/授权代理，不绕过应用锁。
- DashboardController 各 panel id 独立槽位，history 单独槽位；请求复制集合，sequence/generation 取消旧读；数据变化 refreshLoaded，恢复/清空/失去数据时 reset。UI 只有 request/dataset/contentVersion 和非loading/error全部匹配才展示，错误可重试。
- PanelCatalog 配置使用稳定 id、完整顺序、启用集合，未知/重复/缺项被拒绝；缺键只在读取端返回默认，未写旧源数据。PanelManagement 的开关和顺序来自已提交偏好，busy 时不可反复写入。
- 16统计的原始行来自 repository/storage 范围查询，展示层未扫描首页3条或流水50条做总量。普通统计排除转账；未入账仍属于普通交易收支，资金历史只计已入账有效交易。标签允许多归属，UI没有把标签合计宣称总支出。
- 最大值导航保留实际 entryId，星期/周末/金额区间/无账户/项目/商家/标签等下钻有真实过滤条件；不会点开无关流水。
- AccountHistory 的日末序列来自期初+已入账端点，净资产内部转账先抵消，不拿净支出当余额。账户页按月请求满足366天上限。
- 底栏每项只有一个 combinedClickable/Role.Tab 语义节点；“我的”的长按 Debug 与普通单击同属一个手势拥有者。

## 本人实现的自审及限制

账户、往来、周期、存钱详情已分离为完整内容页，失效对象显示业务空态及返回；新增/编辑仍用原 FinanceFormDialog，财务写入继续使用原 FinanceAction/operationId/revision。周期记录保留跳过/结果已清理状态，存钱保留愿望完成/撤销、关联转账、删除/恢复，EntryDetail 保留退款与原支出关系。

目前账户详情提供真实每日历史余额和最近交易，未声称提供参考的每笔交易后余额字段。自定义统计定义编辑、信用卡账单周期/还款日、分期仍无模型，本轮无可见假入口，也不能在最终报告声称这些功能全部完成。

构建、真机截图、实际点开/返回和最终安装包证据由 root 独立记录。本审阅不能替代这些验证。

## 新增长按拖动及原子命令交叉审阅

已读回非本人代码 `LongPressReorderableList.kt`、`Reordering.kt`、Domain/FinanceDomain 的4条Reorder分支。拖起冻结回调和sourceVersion，版本/busy/成员变化取消；null标题不可拖、只能同group交换；邻项animateItem，拖起浮起，落位spring；一次提交完整顺序，失败busy→idle或超时回滚到外部已提交状态。领域完整校验成员/版本后先创建不可变替换，再一次更新，保留不在scope对象原槽，业务专属资金账户只改sortOrder/revision，未发现阻断问题。

账本/分类/维度/账户拖拽消费为本人实现，visual已独立检查：账户group为ledgerId+type、固定标题不可拖，hidden/archived合并原槽，其他group不被改变。补 `VisibleOrderTest` 验证隐藏/归档槽与跨scope/重复ID拒绝；具体执行结果由root统一构建记录。

## Round 2 实图复核后的局部改正（本轮源码已完成，等待统一编译/新截图）

已实际查看原版 `p235-savings-deposit-saved.png`、`p100-cash-account-detail.png`、`p256-borrow-detail.png`、`p262-borrow-partial-repaid.png`，并与 DawnYew round 2 的 `08-存钱详情.png`、`06-账户详情.png`、`17-借入详情.png` 对照；本节不将代码阅读等同于实机验收。

- 账户列表的照片资产卡接入最近 30 天 `AccountHistoryRequest`，按所选账本过滤；只有 request、datasetId、contentVersion 完全对应且不在 loading/error 时才提供真实日末余额点。无有效结果不画旧线或示意线。该请求仅存在于账户列表分支，进入详情由其原月度请求使用同一槽，返回列表重新请求。
- 账户详情首卡只保留“当前余额”和金额，说明移至卡外；完整账户名称和类型移入后方事实区，编辑/隐藏/计入资产等操作仍在。
- 我的列表图标遵循原布局的 28 dp 盒、4 dp 内距、20 dp 图形；右箭头 16 dp。图形采用明确的 `onSurface`/`onSurfaceVariant`，避免 TextButton 默认主色污染单色图。
- 存钱详情取消 136 dp 圆环及重复名称，按“已存余额/目标 + 右侧存入 + 三项小指标”排列。模型没有预计完成日期，显示实际剩余目标、当前进度、有效存入次数；不推测日期。记录移到愿望之前，手动取出、愿望、记录编辑/删除/恢复、资金流水、计划编辑/归档等动作全部保留，完整名称/备注放后方。
- 往来详情摘要改为应收/应付金额与收付按钮、两项金额，分别显示本金与收付记录后再显示单据事实。追加本金/支出、追加收付、全部资金流水、编辑单据、归档及未入账状态仍保留。

本代理运行四个修改文件的 `git diff --check`，无格式错误；未运行 Gradle/ADB。最终编译、状态切换和新图验收由 root 统一执行。

非作者 acceptance 追加复审：指出并已修复 `ObligationScreens.kt` 缺 `Alignment` 导入；其余检查确认存钱存入/取出/愿望/记录恢复/关联流水、往来本金/收付/全流水/编辑/归档均可达，资产 30 天查询绑定未发现新竞态，无新增 P1/P2。视觉结论仍待 root 最终新图。
