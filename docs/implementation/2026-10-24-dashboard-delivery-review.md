# 面板数据与配置实现交付检查

## 实现责任与契约

本轮按已确认的 `2026-10-24-dashboard-api-contract.md` 实施。配置只保存稳定 ID 的开关与顺序，金额始终来自既有财务事实，不引入账号、会员或网络能力。可用目录为首页10项/统计16项，不支持的信用卡周期账单和多人作者统计不进入目录。

- core：`PanelCatalog.kt` 定义目录、严格版本化布局编解码与缺键默认；`Validation.kt` 两个偏好键走命令、完整状态、批次恢复共有校验。
- core：`PanelQueries.kt` 计算16统计与新增首页投影。`FinanceQueries.kt` 增加星期/无账户/无项目/无商家/无标签过滤并冻结游标筛选。`Models.kt` 增加两条 repository 查询契约及可测试参考实现。
- core：`AccountHistoryQueries.kt` 计算范围内每日当地日末余额。起始日之前的记录用于期初，开户当天起计入初始余额，已删除/未入账事实排除，内部转账相消；金额相消不依赖数据库读取次序。
- storage：`DashboardQueries.kt` 使用同一 reader transaction 的 metadata + 日期/账本/维度 SQL 过滤；只读目标范围交易投影与被引用的标签、分类名称，不读取 `StatisticsSnapshot.entries` 或全量 snapshot，不用分页50条推算总计。历史余额只读所选账户、截止日以前的金额事件。`FinancialActivityQueries.kt` 与 core 同步新筛选；`RoomLedgerRepository.kt` 显式代理两个新查询。
- presentation：`DashboardController.kt` 每个 panelId 独立请求槽、query/dataset/version 匹配、取消与序号/代际守卫。内容改变刷新；仅偏好改变升级版本，不重复读取；预算变化明确使缓存失效。刷新失败保留旧快照但普通重试仍可重新读取。
- root负责：AppController 生命周期与 `dashboard` 暴露、GenerationRepository 授权代理、UI接线与设备/Gradle验证。

## 兼容性与边界

已有 Room preference 表和 backup preference 实体满足新增配置，不改数据库版本、备份版本或旧源摘要。旧数据缺键只在读取时得到默认布局，不向旧数据集注入偏好。全关闭是有效配置，与缺键不同。恢复遇未知/重复/缺失ID或错误版本必须拒绝。

`PanelRow.value` 默认普通支出，显式收入/退款筛选跟随所选类型；收入、支出、退款原值各自保留。分类涨跌为净支出，可负；预算追踪已用值与既有预算总览一致，为 max(0,支出−退款)。预算退款沿用父支出的当前预算资格。转账不混入普通收支。单笔最高直接回到对应账单；均价四舍五入到分；笔数单位为COUNT。标签可能重叠，不跨标签求总额。已存在标签过滤时只呈现被选标签的分组，保证下钻不会扩大原筛选。星期与周末的空交集不提供下钻。

跨分类/日期金额合计在 query 层先校验，不能在 Compose 汇总时才抛出溢出。历史余额有正负相消，采用相反符号优先且将期初一起计算的方式，避免可表示结果因中间值溢出而失败。

## 新测试与验证记录

- `DashboardTest`：10/16目录；全关/顺序往返；错误配置；weekday/unassigned；16项真实结果；项目下钻；标签多归属；均价/单笔最高；退款预算父资格；分类负差额；开户/未入账/内部转账历史；源筛选不扩大；有符号极值相消。
- `DashboardQueryTest`：16统计及首页投影与core逐值一致；星期/未分配SQL与core一致；范围外损坏entry payload不影响查询；历史余额SQL与core一致；真实Room重开与v2 streaming candidate备份恢复保留布局；读取缺键默认不改变旧数据。
- `DashboardControllerTest`：多面板并行；旧查询、reset结果失效；偏好不重复查、预算必须重新查；成功→刷新失败→普通重试成功，覆盖面板和历史。

root记录：非作者数据审阅已处理分类预算负值与总览口径不一致；补跨月退款大于本月支出时已用预算为零的对照测试。

首轮核心 RED 因 PanelSurface 等未实现失败；core5个有效断言随后通过；presentation/storage 的缺失新实现 RED 已验证。生产链齐备后的 core/storage/presentation/platform 主机套通过（`.local/correction-domain-check.log`）。随后新增上述scope、重试、备份恢复边界，最终运行由root统一进行；本文不把源码阅读当作设备验收。

## 非作者消费审阅

已检查 root GenerationRepository 的 mutex/authorization 代理、AppController refresh/reset/dataset更新、资产趋势路由与UI request/dataset/version严格匹配，无接口阻断。已向视觉作者反馈并由其处理：预算/分类差额使用安全金额运算；COUNT默认类型文案与实际口径一致；账户追踪提供真实资产趋势入口；月/季度坐标不能截断为相同的年份前缀。界面截图与真实点击仍由root验收。

## 最终只读交叉审阅（拖拽、配置消费与跨来源导航）

数据作者审阅非本人编写的 LongPressReorderableList、PanelManagement、HomeScreen/StatisticsScreen 消费及 root 导航接线；本次没有修改生产源码，没有运行 Gradle/ADB。root提供 full check round4 PASS，后续 UI 行为验证仍由 root 执行。

发现2项P2，无P1：

1. **关闭两个带月份控件的首页面板会丢失月份入口。** 审阅时 `HomeScreen.kt` 的月份动作仅存在 monthly_expense（约324行）及 calendar（约371行）分支。当两者都关闭而保留预算追踪/分类涨跌/收支对比时，其余面板仍使用旧 homeScope 的月份，用户不能再换月。root顶部搜索只进入流水，不更新homeScope。已交视觉作者补“两者均关闭时的紧凑公共月份入口”；应覆盖关闭两者后换月仍能更新其余面板的UI状态。
2. **统计详情保存状态跨首页/统计Tab复用，导致支出卡进入收入详情。** 原 `SaveableStateProvider("statistics:$page")` 与 `rememberSaveable(panel)` 仅按页面名保存kind/滚动。统计Tab分类详情选收入后，再从首页支出分类倾向进入同详情，会恢复收入选择，虽然月份/账本已经正确保留。root已按本次审阅建议窄修为home/tab两组固定key，并只在首页新点击入口前移除该home key；已只读复核 `DawnYewApp.kt` 86–91、172行。下钻返回不经过该清理路径，因此仍恢复原frame的kind/分类/滚动。尚需root行为验证“Tab收入→首页新开支出”及“首页详情退款→流水→返回保持退款”。

其余未发现有源码证据的P1/P2：

- 拖拽开始冻结onCommit与sourceVersion；落位再次比较版本和busy。新增内容、外部状态变化、取消及组件离开不会提交旧preview。成功写入由committed排列接管；失败的busy→idle或未发出写入的超时会移除pending预览。核心命令仍独立验证完整成员、版本与原子receipt，界面保护不替代写边界。
- 拖拽分组只移动相同group的键，header/null组不参与；账户组是ledger+type，隐藏/归档项按原槽合并。跨组不能被拖动重排。边缘滚动和条目平移动画已读源码，触摸取消、连续拖拽和实际滚动手感必须以root设备行为结果为准，源码审阅不作设备通过声明。
- 管理页和首页/统计页均读取同一PanelCatalog与已提交preferences；展示为layout.order按enabled过滤，不再维护第二份可漂移顺序。配置写入后直接改变主面板项序列，root的对应偏好变化还会将主列表回到新首项。全关闭保留配置入口，未入账条件面板不会因全关闭而不可达。
- openStatistics保存来源QueryScope与sourceLedgerIds，root优先用该范围构造PanelRequest；showActivities和back恢复frame，正常选Tab清空来源临时状态。显示前核对request/dataset/contentVersion/loading/error，未把旧账本/旧月份结果当成当前结果。

P2-1后续源码复核：视觉作者已在 `HomeScreen.kt` 318–330行加入两项均未启用时的公共月份行，点击复用chooseHomeMonth → HomeMonthPicker(month,onRange)，根回调仍为setHomeRange，因此修改的是首页作用域；任一原月份入口开启时不显示该行。两项P2均已完成源码修正和非作者复核，当前没有剩余的源码级P1/P2；最终新增行为与设备验证以root实际结果为准。
