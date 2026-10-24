# Dashboard 面板接口（纠正轮已确认）

所有金额为 Long 分值；COUNT 的 value 单位为笔。配置不保存任何派生余额。旧备份/账单格式不改。2026-10-24 经 root 与跨职责审阅确认，按此契约实施。

## 目录与布局

```kotlin
enum class PanelSurface { HOME, STATISTICS }
data class PanelDefinition(val id:String,val title:String,val description:String,val defaultEnabled:Boolean)
data class PanelLayout(val order:List<String>,val enabled:Set<String>)
object PanelCatalog {
    fun definitions(surface:PanelSurface):List<PanelDefinition>
    fun preferenceKey(surface:PanelSurface):String // homePanels / statisticsPanels
    fun defaultLayout(surface:PanelSurface):PanelLayout
    fun resolve(preferences:List<UserPreference>,surface:PanelSurface):PanelLayout
    fun encode(surface:PanelSurface,layout:PanelLayout):String
    fun decode(surface:PanelSurface,value:String):PanelLayout
}
```

HOME IDs（固定顺序）：`monthly_expense,recent_transactions,budget_overview,budget_tracking,account_tracking,category_change,calendar,income_expense,category_radar,savings`。默认开 monthly_expense/recent_transactions/budget_overview/calendar/savings。

STATISTICS IDs（固定顺序）：`category,daily,monthly,category_monthly,yearly,weekday,account,maximum,average,count,weekend,amount_distribution,project,merchant,tag,quarterly`。默认开 category/daily/monthly。

布局必须完整包含当前surface目录ID，未知/重复/缺ID拒绝。格式`1|id=1|id=0...`，顺序就是order；enabled必须是order子集。resolve缺偏好时只返回默认，不写state。

## 统计与新增首页数据

```kotlin
enum class PanelUnit { MONEY, COUNT }
data class PanelRequest(val panelId:String,val scope:ActivityScope)
data class PanelRow(
    val id:String,val label:String,val value:Long,
    val income:Long=0,val expense:Long=0,val refunds:Long=0,val count:Long=0,
    val comparison:Long?=null,val drill:ActivityScope?=null,val entryId:String?=null,
)
data class PanelStatistics(
    val datasetId:String,val contentVersion:Long,val request:PanelRequest,
    val rows:List<PanelRow>,val unit:PanelUnit=PanelUnit.MONEY,
)
// LedgerRepository
suspend fun panelStatistics(request:PanelRequest):PanelStatistics
```

该查询覆盖16个STATISTICS及HOME `budget_tracking/category_change/category_radar/income_expense/monthly_expense`。首页其它已有真实数据继续用现有snapshot/recent/calendar/savings职责；不为了接口统一新造第二份账单/余额。

- category/radar/project/merchant/tag/account：value 默认普通支出，drill具体分组；标签允许重叠。income/expense/refunds为各组原始金额。
- daily/income_expense/monthly_expense：日期行；monthly/category_monthly/count/maximum：月份行；yearly：年份；quarterly：季度；weekday：ISO星期1..7；weekend：工作日/周末。
- average：value=分类普通支出算术均值，四舍五入至分；count=支出笔数。count面板value=所选类型普通交易数，unit=COUNT。
- maximum：value=该月所选单一类型最大金额（默认EXPENSE）；entryId是确定性实际交易；drill保留月和类型。
- category_change：value=本月分类净支出，comparison=上月同类净支出；income/expense/refunds/count仍是本月事实；drill当前月分类。
- budget_tracking：value=分类预算已用，comparison=分类预算额度；drill过滤category且excludedFromBudget=false。
- amount_distribution：分值桶 [0,1000)、[1000,5000)、[5000,10000)、[10000,50000)、[50000,+∞)；value=金额总额，count=笔数，drill金额上下界精确匹配。
- category_monthly：调用方须选择真实categoryIds；无选择时返回明确空数据供UI显示选择分类，不静默用全部分类冒充单分类。

调用方传真实范围：月面板当月、monthly/category_monthly/count/maximum/quarterly所选全年、yearly全历史观察年份。查询层不私改request的有效范围。category_change额外读上月作为comparison；预算追踪仍按月额度，非法跨月scope拒绝。

ActivityScope追加尾部字段（默认值保持旧调用方兼容）：

```kotlin
val weekdays:Set<Int> = emptySet(), // ISO Monday=1..Sunday=7
val accountUnassigned:Boolean=false,
val projectUnassigned:Boolean=false,
val merchantUnassigned:Boolean=false,
val tagUnassigned:Boolean=false,
```

未设置过滤只匹配普通Entry，禁止同时选对应ID和Unassigned。weekdays用于精确星期/周末下钻；不改变使用本地日期的原语义。core与SQL同规则，cursor.scope包含新增条件。

## 资产历史

```kotlin
data class AccountHistoryRequest(
    val ledgerIds:Set<String> = emptySet(),val accountIds:Set<String> = emptySet(),
    val startDate:String,val endDate:String,
)
data class AccountHistoryPoint(val date:String,val balanceMinor:Long)
data class AccountHistorySnapshot(
    val datasetId:String,val contentVersion:Long,val request:AccountHistoryRequest,
    val points:List<AccountHistoryPoint>,
)
suspend fun accountHistory(request:AccountHistoryRequest):AccountHistorySnapshot
```

有限日期范围最多366天。accountIds非空时取这些账户；为空时选scope内includedInAssets账户（隐藏仅影响列表，不改变资产统计）。每个点是当地日末余额：账户openingAt在该点前才计入期初；活跃且已入账的普通收支/退款和转账按端点加减。期间前的交易是期初累计必需数据，但无关账本/账户、日期在endDate之后的交易不能被读取/影响结果。净资产趋势不能拿净支出替代。

## Presentation

```kotlin
data class PanelUiState(val request:PanelRequest?=null,val snapshot:PanelStatistics?=null,val loading:Boolean=false,val error:String?=null)
data class AccountHistoryUiState(val request:AccountHistoryRequest?=null,val snapshot:AccountHistorySnapshot?=null,val loading:Boolean=false,val error:String?=null)
class DashboardController(repository:LedgerRepository,scope:CoroutineScope) {
    val panels:StateFlow<Map<String,PanelUiState>>
    val history:StateFlow<AccountHistoryUiState>
    fun loadPanel(request:PanelRequest,force:Boolean=false)
    fun loadHistory(request:AccountHistoryRequest,force:Boolean=false)
    fun refreshLoaded(force:Boolean=false)
    fun reset()
}
```

map按panelId各自独立槽；同panel切范围有sequence守卫。UI不把loading中的旧snapshot当当前scope。root增加 `app.dashboard`、数据变化refreshLoaded和resetTransientState→dashboard.reset。GenerationRepository显式代理两个repository接口并保留授权/epoch/mutex。

配置写入root既有`controller.perform(Command.SetPreference(...))`，UI busy时禁用，持久成功后的state驱动开关/排序。不另建乐观布尔状态模拟成功。

非作者审阅补充：budget_tracking.value 与既有 StatisticsSnapshot.budgetUsed 一致，采用 max(0,expense-refunds)，跨月退款不生成负的已用预算；income/expense/refunds 原值保留用于核对。category_change 仍可为负。
