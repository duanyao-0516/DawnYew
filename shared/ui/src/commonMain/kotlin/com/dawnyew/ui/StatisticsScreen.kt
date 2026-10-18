package com.dawnyew.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*
import com.dawnyew.presentation.*
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable internal fun StatisticsPage(
    s:LedgerState,stats:StatisticsSnapshot?,today:String,ledgerId:String?,start:String,end:String,busy:Boolean,
    listState:LazyListState,interactive:Boolean,
    onBudget:(Long?,()->Unit)->Unit,onDrill:(String?,String?,String?)->Unit,onRefundDrill:(String)->Unit,
) {
    var budgetDialog by remember {mutableStateOf(false)}
    var table by remember {mutableStateOf(false)}
    val month=start.take(7)
    val budget=s.budgets.find {it.ledgerId==ledgerId && it.yearMonth==month}
    val eligible=ledgerId!=null && monthRange(month)==(start to end) && stats?.scope?.kinds.isNullOrEmpty() && stats?.scope?.categoryIds.isNullOrEmpty() && stats?.scope?.keyword.isNullOrEmpty()
    val categories=remember(stats,s.categories,s.ledgers) {stats?.let {categoryFinancialRows(it,s)}.orEmpty()}
    val ledgers=remember(stats,s.ledgers) {stats?.let {ledgerFinancialRows(it,s)}.orEmpty()}
    val ledgerMaximum=(ledgers.maxOfOrNull {abs(it.netExpense)} ?: 0L).coerceAtLeast(1)
    val ledgerNegative=ledgers.any {it.netExpense<0}
    LazyColumn(Modifier.fillMaxSize(),state=listState,contentPadding=PaddingValues(16.dp,8.dp,16.dp,100.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {SummaryCard(stats)}
        if(eligible) item {Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Text("月度预算",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);TextButton(onClick={budgetDialog=true},enabled=!busy) {Text(if(budget==null) "设置" else "修改")}}
            if(budget==null) Text("为当前账本设置本月支出上限") else {
                val used=stats?.budgetUsed ?: 0
                Text("已用 ¥ ${Money.format(used)} / ¥ ${Money.format(budget.amountMinor)}（${percentageText(used,budget.amountMinor)}%）")
                LinearProgressIndicator(progress={(used.toFloat()/budget.amountMinor).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                Text((if(used>budget.amountMinor) "超出 ¥ " else "剩余 ¥ ")+Money.format(abs(budget.amountMinor-used)),color=if(used>budget.amountMinor) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        }}} else item {Text("选择单个账本的完整月份，并清除类型、分类和备注筛选，可查看预算。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("收入与净支出趋势",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            TextButton(onClick={table=!table}) {Text(if(table) "收起数据表" else "数据表")}
        }}
        if(stats==null || stats.entryCount==0L) item {EmptyState("暂无统计数据","记账后可在这里查看趋势、分类与账本对比")}
        else {
            item {Card {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                    Text("● 收入",color=MaterialTheme.colorScheme.primary)
                    Text("◆ 净支出",color=MaterialTheme.colorScheme.tertiary)
                }
                TrendChart(stats.trend,today,interactive) {onDrill(null,it,null)}
                Text("点击图表或数据行查看对应明细；净支出 = 原始支出 − 退款。",style=MaterialTheme.typography.bodySmall)
            }}}
            if(table) items(stats.trend,key={"trend-"+it.period}) {point->
                val future=isFuture(point,today)
                val description=if(future && !hasValues(point)) "— 尚未发生" else (if(future) "日期晚于本机今天，请核对 · " else "")+
                    "收入 ${Money.format(point.income)} · 原始支出 ${Money.format(point.expense)} · 退款 ${Money.format(point.refunds)} · 净支出 ${Money.format(point.netExpense)}"
                SettingRow(point.period,description,enabled=interactive) {onDrill(null,point.period,null)}
            }
            item {SectionTitle("分类支出与退款")}
            if(stats.expense==0L) item {Text("本期没有原始支出；退款单独列示，不计入支出占比分母。")}
            items(categories,key={"category-"+it.id}) {row->
                val share=if(stats.expense==0L) 0f else row.expense.toFloat()/stats.expense
                Card(modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(row.label,style=MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("原始支出 ¥ ${Money.format(row.expense)} · ${percentageText(row.expense,stats.expense)}%")
                        Text("退款 ¥ ${Money.format(row.refunds)}")
                        Text("净支出 ¥ ${Money.format(row.netExpense)}")
                    }
                    LinearProgressIndicator(progress={share.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().semantics {contentDescription="${row.label}原始支出占比 ${percentageText(row.expense,stats.expense)}%"})
                    if(row.expense>0) TextButton(enabled=interactive,onClick={onDrill(row.id,null,null)}) {Text("查看本分类原始支出")}
                    if(row.refunds>0) TextButton(enabled=interactive,onClick={onRefundDrill(row.id)}) {Text("查看本分类退款明细")}
                }}
            }
            item {SectionTitle("账本净支出对比")}
            items(ledgers,key={"ledger-"+it.id}) {row->
                Card(onClick={onDrill(null,null,row.id)},enabled=interactive,modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(row.label,style=MaterialTheme.typography.titleMedium)
                    LedgerBar(row.netExpense,ledgerMaximum,ledgerNegative)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("原始支出 ¥ ${Money.format(row.expense)}")
                        Text("退款 ¥ ${Money.format(row.refunds)}")
                        Text("净支出 ¥ ${Money.format(row.netExpense)}")
                    }
                }}
            }
        }
    }
    if(budgetDialog) {
        var value by remember {mutableStateOf(budget?.let {Money.format(it.amountMinor)}.orEmpty())}
        var error by remember {mutableStateOf<String?>(null)}
        AlertDialog(onDismissRequest={if(!busy) budgetDialog=false},title={Text("$month 月预算")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(value,{value=it;error=null},enabled=!busy,label={Text("预算金额（元）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }},confirmButton={TextButton(onClick={try {onBudget(Money.parse(value)) {budgetDialog=false}} catch(e:DomainException){error=e.message}},enabled=!busy) {Text("保存")}},dismissButton={FlowRow {
            if(budget!=null) TextButton(onClick={onBudget(null) {budgetDialog=false}},enabled=!busy) {Text("移除预算")}
            TextButton(onClick={budgetDialog=false},enabled=!busy) {Text("取消")}
        }})
    }
}
private fun isFuture(point:TrendPoint,today:String)=if(point.period.length==7) point.period>today.take(7) else point.period>today
private fun hasValues(point:TrendPoint)=point.income!=0L || point.expense!=0L || point.refunds!=0L

@Composable private fun TrendChart(points:List<TrendPoint>,today:String,interactive:Boolean,onPoint:(String)->Unit) {
    val incomeColor=MaterialTheme.colorScheme.primary
    val expenseColor=MaterialTheme.colorScheme.tertiary
    val axis=MaterialTheme.colorScheme.outlineVariant
    val visible=points.filter {hasValues(it) || !isFuture(it,today)}
    val maximum=(visible.maxOfOrNull {maxOf(it.income,abs(it.netExpense))} ?: 0L).coerceAtLeast(1).toFloat()
    val negative=visible.any {it.netExpense<0}
    Canvas(Modifier.fillMaxWidth().height(180.dp).semantics {contentDescription="收入与净支出双线趋势图；下方可展开等价数据表"}.pointerInput(points,interactive) {
        detectTapGestures {offset->if(interactive && points.isNotEmpty()) {val index=(offset.x/size.width*(points.size-1)).roundToInt().coerceIn(0,points.lastIndex);onPoint(points[index].period)}}
    }) {
        val zero=if(negative) size.height/2 else size.height-12
        val span=if(negative) size.height/2-12 else size.height-24
        drawLine(axis,Offset(0f,zero),Offset(size.width,zero),1.dp.toPx())
        var lastIncome:Offset?=null
        var lastExpense:Offset?=null
        points.forEachIndexed {index,point->
            if(!isFuture(point,today) || hasValues(point)) {
                val x=if(points.size==1) size.width/2 else index.toFloat()/(points.size-1)*size.width
                val income=Offset(x,zero-point.income.toFloat()/maximum*span)
                val expense=Offset(x,zero-point.netExpense.toFloat()/maximum*span)
                lastIncome?.let {drawLine(incomeColor,it,income,2.dp.toPx())}
                lastExpense?.let {drawLine(expenseColor,it,expense,2.dp.toPx())}
                drawCircle(incomeColor,3.dp.toPx(),income)
                drawRect(expenseColor,Offset(expense.x-3.dp.toPx(),expense.y-3.dp.toPx()),Size(6.dp.toPx(),6.dp.toPx()))
                lastIncome=income;lastExpense=expense
            } else {lastIncome=null;lastExpense=null}
        }
    }
}
@Composable private fun LedgerBar(amount:Long,maximum:Long,hasNegative:Boolean) {
    val color=if(amount>=0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val axis=MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(28.dp).semantics {contentDescription="净支出柱图，${Money.format(amount)}元"}) {
        val zero=if(hasNegative) size.width/2 else 0f
        val extent=(if(hasNegative) size.width/2 else size.width)*abs(amount).toFloat()/maximum
        drawLine(axis,Offset(zero,0f),Offset(zero,size.height),1.dp.toPx())
        drawRect(color,Offset(if(amount<0) zero-extent else zero,4.dp.toPx()),Size(extent,(size.height-8.dp.toPx()).coerceAtLeast(0f)))
    }
}
