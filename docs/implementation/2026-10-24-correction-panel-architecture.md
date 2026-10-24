# 首页/统计面板纠正方案：真实开关与离线数据

2026-10-24。此文为只读设计审计，未运行 Gradle/ADB、未修改生产代码。优先级来自用户本轮纠正，原文档把大量入口留空的决定不再作为实现免责范围。

## 1. 确认的错位

实际查看参考 `round2/p025-home-11-panels-config.png`、`p026-home-panels-config-bottom.png`：每行是标题、功能说明和 Switch；底部明确“点击并按住列表项，可以拖动排序”。参考统计配置也是预置面板启用目录，不是十七个空详情页。

当前 `ProfileScreen.kt` 的 `PanelDirectoryPage` 为 `SettingRow(title) { onOpen("配置$title") }`，`DawnYewApp` 通用 else 进入空正文；`HomeScreen` 和 `StatisticsScreen` 依然固定画五类/三类卡片，没有开关消费者。此处同时缺交互、持久化和真实渲染，不是简单文案差异。

必须拆清两个动作：

1. “主页面板/统计面板”打开带 Switch 的管理页，切换立刻保存并决定原页可见卡片；行本身不再导航到空页。
2. 首页/统计中的已启用数据卡标题或图点打开真实详情：统计汇总、图表、数值列表、带原筛选的流水下钻。无数据是明确空态，不能是空正文。

“账户”在此方案指资金账户，不是在线账号。登录/会员/用户账号入口删除；不能连带删除现有账户余额和转账关系。

## 2. 可离线面板范围

原始目录共11首页/17统计。不照搬虚假数量：去掉本机没有业务基础的1项首页和1项统计后，展示“10个主页面板/16个统计面板”或直接“管理面板”，由 catalog 真实数量生成。用户没有要求替代在线账户或信用卡账单模型。

### 首页

| key | 参考名 | 实际离线消费者与详情 | 默认 |
| --- | --- | --- | --- |
| monthly_expense | 本月支出 | 月普通收支、退款/净额与日趋势；标题到该范围统计详情，日点到当天流水 | 开 |
| recent_transactions | 最近交易 | 当前账本最近记录，保留3笔查询；全部到同范围流水 | 开 |
| budget_overview | 预算概览 | 月总预算/预算使用/剩余和剩余日均；到预算详情 | 开 |
| budget_tracking | 预算追踪 | 同账本本月分类额度和真实已用/剩余；到分类预算流水或预算管理 | 关 |
| account_tracking | 账户追踪 | 非隐藏资金账户余额/资产汇总与所选月余额变化；点击账户到详情 | 关 |
| category_change | 分类涨跌 | 本月与上月同分类净支出、差值；上月0且本月>0显示“新增”而非无穷百分比；到分类两月对比/流水 | 关 |
| calendar | 日历视图 | 当前账本月每日金额；不以笔数替代金额；点日期到当天流水 | 开 |
| income_expense | 收支对比 | 同范围收入/支出双曲线或条形，退款独列；到对比详情/日期流水 | 关 |
| category_radar | 分类倾向 | 当前月前6支出分类雷达，全部分类在详情列表；类别不足3时用明确列表，不能画误导的多边形 | 关 |
| savings | 存钱计划 | 真实未归档计划进度，点计划到完整详情 | 开 |
| — | 账单概览 | 隐藏。当前 Account 仅类型与余额，没有信用卡账单日、账单期/到期日、已出/未出账单事实，不能用负余额冒充已出账单 | — |

未入账仍是条件卡，跟随本月支出后，不冒称原始11项之一。全部关掉时保留紧凑“管理面板”入口，不自动恢复默认或显示一屏空白。

### 统计

`PanelRequest.scope` 是实际统计范围；下表默认范围只是页面选月/选年到请求的映射，不应在查询层偷偷改写调用者范围。

| key | 参考名 | 真实离线聚合 / 详情下钻 | 默认范围 |
| --- | --- | --- | --- |
| category | 分类统计 | 分类原始支出环图、净支出/退款、分类行流水 | 选月；默认开 |
| daily | 日趋势 | 每日收支，完整日期零点填充；点日流水 | 选月；默认开 |
| monthly | 月度收支 | 12月收入/支出，年份不覆盖月查询槽；点月流水 | 选年；默认开 |
| category_monthly | 分类收支 | 选分类的逐月收入/支出；支持真实分类选择，默认有数据分类；月+分类下钻 | 选年 |
| yearly | 年度统计 | 已有年份收支；显示年度柱图/逐年数值，非当前年12个月的重复命名 | 全历史已观察年份 |
| weekday | 一周统计 | 周一至周日分布；详情为日期集合的实际记录，不直接把“星期一”当连续日期范围 | 选月 |
| account | 账户统计 | 普通收支按 EntryDetails.accountId 分组，含“无账户”；资金转账与资产余额不要混成消费占比 | 选月 |
| maximum | 单笔最高 | 每月最大普通收入/支出及对应交易ID，可直接到最高那笔详情；同额按时间/ID确定 | 选年 |
| average | 分类均价 | 分类原始支出/支出笔数，精确到分的舍入；无分母显示0/无记录，不把退款当新增消费笔数 | 选月 |
| count | 笔数统计 | 每月支出/收入/退款笔数，单位“笔”而非金额；点月/类型流水 | 选年 |
| weekend | 周末统计 | 工作日/周末收支与笔数，按本地日历星期六日；不声称含法定调休表 | 选月 |
| amount_distribution | 金额分布 | 固定分值桶 [0,1000)、[1000,5000)、[5000,10000)、[10000,50000)、[50000,+∞)，各桶金额与笔数；精确金额上下界下钻 | 选月 |
| project | 项目统计 | 普通收支按项目分组，含“未设置项目”，点项目带ID下钻 | 选月 |
| merchant | 商家统计 | 普通收支按商家分组，含“未设置商家” | 选月 |
| tag | 标签统计 | 每标签命中账单各算一次，可多标签交叠；必须标注“标签可交叠，合计不等于总支出”，不能画总和为100%的排他饼图 | 选月 |
| quarterly | 季度统计 | 选年四季收支/退款，真实季度范围下钻 | 选年 |
| — | 多人统计 | 隐藏。没有作者/共享成员数据，不创造虚假“本机用户”统计作为多人替身 | — |

关闭的面板可在管理页开启，并且开启后确实有数据卡及详情。付费与否不进入本机业务分支；纯本地聚合不能因参考App收费而空置。

## 3. 最小持久化改动

使用现有 `UserPreference(key,value)`，新键建议为 `homePanels` 和 `statisticsPanels`。不加 Room 表、不改旧 Entry/Category 载荷、不改23种实体集、不改备份版本。完整链路已经存在：

`Command.SetPreference` → `validatePreference` → `LedgerState.preferences` → `PreferenceRow` → Room 差量 upsert → Streaming 批量集 → BackupProtocol/preferences(key,value) → JsonStreamCodec → candidate校验。

新增名单必须在 core 定义，UI不能另维护一份不同的key列表。建议的稳定草案：

```kotlin
enum class PanelSurface { HOME, STATISTICS }
data class PanelDefinition(val id: String, val title: String, val description: String, val defaultEnabled: Boolean)
data class PanelLayout(val order: List<String>, val enabled: Set<String>)
object PanelCatalog {
    fun definitions(surface: PanelSurface): List<PanelDefinition>
    fun resolve(preferences: List<UserPreference>, surface: PanelSurface): PanelLayout
    fun encode(surface: PanelSurface, value: PanelLayout): String
    fun decode(surface: PanelSurface, value: String): PanelLayout
}
```

可选规范线格式为 `1|monthly_expense=1|recent_transactions=0|...`，ASCII稳定ID、顺序即order、值严格0/1，长度上限2KiB，禁止未知、重复、缺ID或非法版本。所有关闭仍保存全量order及0状态，不能等价于“未设置”。新增目录项未来需要明确布局版本兼容策略，不能直接让既有偏好变非法。

缺少这两个偏好的旧状态由 resolver 在**读取展示时**使用默认，绝不在备份校验前补写默认而改变源计数/摘要。当前生产只写v2备份；旧v1/v2无新键均原样恢复。新键由 `validatePreference` 一处维护，命令、全量`validateState`、`validateEntityBatch`已复用此函数。

开关与排序保存整个布局，走 AppController 单写门。busy时禁用开关/拖动；失败显示错误并回到已保存的可见状态，不留“开关亮了但实际没保存”。返回、进程重开、切账本均用同一偏好；本轮建议按本机全局布局，保留原账本业务范围，不虚构每账本各一套开关。

管理页可以支持拖动排序（参考有证据）；若直接复用稳定排序命令，不需要新增无消费者的layout属性。不存在已实现拖动时不得显示“长按排序”虚假提示。

## 4. 查询/API职责

### 必须规避的现有陷阱

`shared/storage/EntryQueries.kt/readStatistics()` 实际返回 `StatisticsSnapshot.entries = emptyList()`；只有 core 内存参考算法返回匹配实体。拿 snapshot.entries 在 Compose 中做年度/账户/标签统计，会出现“内存单测有值、真机全空”。同样，ActivityPage只有当前分页，不能求全范围均价、最大值、饼图或总计。

### 建议 API

新聚合职责在 core 定义、storage 实现、presentation持有；UI只画结果和发动作：

```kotlin
data class PanelRequest(val panelId: String, val scope: ActivityScope, val selectedCategoryIds: Set<String> = emptySet())
data class PanelValueRow(
    val id: String, val label: String,
    val incomeMinor: Long, val expenseMinor: Long, val refundMinor: Long,
    val incomeCount: Long, val expenseCount: Long, val refundCount: Long,
    val maxIncome: FinancialActivity? = null, val maxExpense: FinancialActivity? = null,
    val drill: PanelDrill,
)
data class PanelStatistics(val datasetId: String, val contentVersion: Long, val request: PanelRequest, val rows: List<PanelValueRow>)
sealed interface PanelDrill {
    data class Scope(val value: ActivityScope): PanelDrill
    data class Dates(val base: ActivityScope, val dates: Set<String>): PanelDrill
    data class Activity(val family: ActivityFamily, val id: String): PanelDrill
}
// repository.suspend fun panelStatistics(request: PanelRequest): PanelStatistics
```

字段可以按实现拆成专用类别，不必强行用一个万能图表对象；上述最重要的稳定契约是查询身份、精确分值/笔数分开、真实范围与完整下钻。星期/周末和“未设置维度”目前不能由 ActivityScope 精确表达，必须先扩展过滤契约或使用可验证的专用分组详情分页，不能点击后显示更大的无筛选集合。

推荐 ActivityScope 最小新增 `weekdays:Set<Int>`（ISO1..7）和未设置维度的明确布尔/枚举过滤，配合 shared/core参考过滤与storage同条件实现，cursor.scope自然携带身份；不要用空Set代替“未设置”。金额桶现有min/max是闭区间，向上开区间必须转 `max = upperExclusive - 1`（分值整数），不使用 Double 元金额比较。

存储可复用 FinancialActivityQueries 的统一投影/筛选片段，但应在本地storage里收敛受控共享内部helper，不把各类原始 SQL 字符串暴露到 UI。在 reader transaction 内取得 metadata 与聚合，核心聚合采用整数SUM/COUNT/MAX及确定性ID选择；未知SQL输入只绑定参数。预算专用消费沿用父退款预算资格，不同于普通支出；账户趋势沿用已入账余额事实及openingAt，不能用净支出代替资金余额。

`GenerationRepository` 必须显式代理新查询，放在既有授权和mutex边界中，不能漏override后直接走不正确的默认实现。core 保留 `PanelQueries.calculate(state,request)` 作为独立语义参考与fake repository默认实现；storage测试比较两者。

Presentation建议新增独立 `PanelCoordinator`，按(panelId,scope,categoryIds,datasetId)保持请求身份和有限cache，旧完成不能覆盖新账本；重置/恢复/授权撤销清空。配置偏好变化只改显示，不强迫所有统计重算；预算额度/账户状态变化需要使对应财务面板失效，不能机械沿用 `hasSameQueryContent` 忽略budget的策略。

性能避免每一张卡都做一次全量state扫描。首页月/上月/账户余额/分类额度可分别批量查询并共享同scope结果；统计多个同范围面板可在单次reader快照计算共用分组，再拆给卡片。所有聚合先保证真实范围正确，UI只渲染可见卡片，不预请求已关闭的全部16项。

## 5. 必需的验证（主任务统一执行）

1. Core布局：旧prefs默认、全关区别未设置、独立首页/统计、重排不丢enabled、非法/重复/未知id拒绝、domain/full/batch一致。
2. Room真实重开：toggle→保存→close/reopen→order/enabled一致；无业务账单/余额变化。
3. v1和v2旧备份继续通过源摘要；新布局v2完整往返；非法新pref候选拒绝且不发布、不抹旧数据。
4. 新聚合内存/SQL对比包含多账本、多分类、退款跨月、未入账、已删除、无账户/无维度、多标签交叠、空范围、月份/季度边界、周一/周末、相同最大金额、金额桶边界和溢出。
5. 故意让范围外旧payload损坏，受限SQL聚合仍只读实际范围，证明没有退化为全库UI扫描。
6. 新scope过滤+cursor：星期/未设置维度下钻精确，分页不重复/不漏，换过滤/数据版本拒绝旧cursor。
7. Controller：开关保存失败回显原值、双击busy、开关后退出重进、恢复/授权撤销、两个panel异步完成不会串图。
8. 真机点击：从首页/统计底部打开管理页，每个可见Switch实际开/关，返回主页面出现/消失，重开仍保持；全关后管理入口仍可用；每个可见数据卡详情有内容或业务空态，可按组下钻，返回保持原范围。
9. 截图需包含管理页上下段、默认页、开启账户/预算追踪、开启三种统计、分类详情、空数据/全关状态；同设备同字体与参考进行对照。只证明APK编译或View渲染不能签收这些交互。

## 6. 决策摘要

- 把真实启用/排序与数据详情分开，删 `配置$title` 空路由。
- 离线纯聚合全部实现，不沿用参考会员阻断；只隐藏缺业务事实的信用账单/多人统计。
- 新布局复用两条严格Preference，保持schema4/backup2；源备份验证前不补默认。
- 新聚合走repository→真实SQL、完整query身份和明确drill，禁止统计snapshot.entries或已加载分页假聚合。
- 入口数量由已实现catalog产生，不能写死11/17给用户制造已实现错觉。
