package com.dawnyew.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dawnyew.core.*
import com.dawnyew.presentation.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DawnYewApp(controller:AppController,actions:PlatformActions,lockEnabled:Boolean,captureDraft:CaptureDraftUi?=null,utilities:UtilityPlatform,onBackHandlerChanged:((()->Unit)?)->Unit={},onCaptureConsumed:()->Unit={}) {
    val ui by controller.ui.collectAsState()
    val editor by controller.editor.collectAsState()
    val pendingOperation by controller.pendingOperation.collectAsState()
    val entryPage by controller.entries.collectAsState()
    val statisticsPage by controller.statistics.collectAsState()
    val homePeriod by controller.homeStatistics.collectAsState()
    val homeToday by controller.todayStatistics.collectAsState()
    val navigation by controller.navigation.collectAsState()
    val s=ui.dataset
    val page=navigation.page
    val tab=navigation.tab
    val scope=navigation.scope
    val selectedLedger=scope.ledgerIds.singleOrNull()
    val start=scope.startDate
    val end=scope.endDate
    val toolStates=rememberSaveableStateHolder()
    val homeScroll=rememberLazyListState()
    var todayDate by remember {mutableStateOf(controller.environment.localNow().take(10))}
    LaunchedEffect(Unit) {
        while(true) {todayDate=controller.environment.localNow().take(10);delay(30_000)}
    }
    val detailsScroll=rememberLazyListState()
    val statisticsScroll=rememberLazyListState()
    val theme=s?.preferences?.find {it.key=="theme"}?.value ?: "system"
    DawnYewTheme(theme) {
        val snack=remember {SnackbarHostState()}
        LaunchedEffect(ui.message) {ui.message?.let {snack.showSnackbar(it);controller.clearMessage()}}
        var ledgerPicker by remember {mutableStateOf(false)}
        var filters by remember {mutableStateOf(false)}
        var searchFromHome by remember {mutableStateOf(false)}
        var confirm by remember {mutableStateOf<Confirmation?>(null)}
        val primary=page in listOf("首页","统计","更多","我的")
        val drillActive=page=="明细" && navigation.drillReturnScope!=null
        fun navigatePage(destination:String) {controller.navigate {it.copy(page=destination)}}
        fun finishBack() {
            when {
                page=="记一笔" -> {controller.clearEditor();controller.navigate {it.copy(page=it.editorReturnPage)}}
                drillActive -> controller.navigate {it.copy(page="统计",tab="统计",detailsScope=it.drillReturnScope ?: it.detailsScope,drillReturnScope=null)}
                page=="账单详情" -> navigatePage("明细")
                else -> navigatePage(tab)
            }
        }
        fun requestBack() {
            if(page=="记一笔" && ui.busy) {controller.notify("正在保存，请等待结果");return}
            if(page=="记一笔" && editor?.needsDiscardConfirmation==true) {
                confirm=Confirmation("放弃未保存的账单？","输入内容和截图识别草稿会被清除；已保存的账单不受影响。",::finishBack)
            } else finishBack()
        }
        SideEffect {onBackHandlerChanged(if(!primary || drillActive) ({requestBack()}) else null)}
        DisposableEffect(Unit) {onDispose {onBackHandlerChanged(null)}}
        LaunchedEffect(captureDraft,s?.datasetId) {
            if(captureDraft!=null && s!=null) {controller.beginEditor(selectedLedger,capture=captureDraft);onCaptureConsumed()}
        }
        LaunchedEffect(page,editor) {
            if(page=="记一笔" && editor==null) {controller.navigate {it.copy(page="首页",tab="首页")};controller.notify("未保存草稿已清除，请重新录入")}
        }
        LaunchedEffect(page,scope,s?.datasetId,s?.contentVersion) {if(page=="明细" && s!=null) controller.loadEntries(scope)}
        LaunchedEffect(page,scope,s?.datasetId,s?.contentVersion) {if(page=="统计" && s!=null) controller.loadStatistics(scope)}
        LaunchedEffect(page,scope,todayDate,s?.datasetId,s?.contentVersion) {if(page=="首页" && s!=null) controller.loadHome(scope,todayDate)}
        val stats=statisticsPage.snapshot
        fun setScope(value:QueryScope) {controller.selectScope(value)}
        fun setRange(value:DateRange) {setScope(scope.copy(startDate=value.startDate,endDate=value.endDate))}
        fun openHomeDetails(value:QueryScope=homeStatisticsScope(navigation.homeScope)) {
            controller.navigate {it.copy(page="明细",tab="首页",detailsScope=value,drillReturnScope=null)}
        }
        Scaffold(
            topBar={if(page!="尺子") TopAppBar(title={
                if(page=="首页") TextButton(onClick={ledgerPicker=true},contentPadding=PaddingValues(horizontal=0.dp)) {
                    Text((s?.ledgers?.find {it.id==selectedLedger}?.name ?: "全部账本")+" ▾",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                } else Column {
                    Text(page,fontWeight=FontWeight.SemiBold)
                    if(page in listOf("统计","明细")) TextButton(onClick={ledgerPicker=true},contentPadding=PaddingValues(0.dp)) {Text((s?.ledgers?.find {it.id==selectedLedger}?.name ?: "全部账本")+" ▾")}
                }
            },navigationIcon={if(!primary) TextButton(onClick={requestBack()}) {Text("返回")}},actions={
                if(page=="首页") IconButton(onClick={searchFromHome=true;filters=true},modifier=Modifier.semantics {contentDescription="搜索账单"}) {AppGlyph("搜索")}
                else if(page in listOf("统计","明细")) TextButton(onClick={searchFromHome=false;filters=true}) {Text("筛选")}
            })},
            bottomBar={if(primary) NavigationBar {listOf("首页","统计","更多","我的").forEach {title->
                if(title=="我的") MyNavigationItem(isSelected=page==title,
                    onClick={controller.navigate {it.copy(page="我的",tab="我的")}},
                    onOpenDebug={controller.navigate {it.copy(page="Debug",tab="我的",drillReturnScope=null)}})
                else NavigationBarItem(selected=page==title,onClick={controller.navigate {it.copy(page=title,tab=title)}},icon={AppGlyph(title)},label={Text(title)})
            }}},
            floatingActionButton={if(page in listOf("首页","明细")) FloatingActionButton(
                onClick={controller.beginEditor(selectedLedger)},shape=CircleShape,modifier=Modifier.size(64.dp).semantics {contentDescription="记一笔"},
            ) {AppGlyph("新增")}},
            snackbarHost={SnackbarHost(snack)},
        ) {padding->
            if(s==null) Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.Center) {CircularProgressIndicator()}
            else Box(Modifier.fillMaxSize().then(if(page=="尺子") Modifier else Modifier.padding(padding))) {
                when(page) {
                    "首页" -> HomePage(homePeriod,homeToday,scope,todayDate,homeScroll,onRange=::setRange,
                        onRetryPeriod={controller.loadHome(scope,todayDate,force=true)},onRetryToday={controller.loadHome(scope,todayDate,force=true)},
                        onDetails={openHomeDetails()},onPeriodDrill={period->
                            val source=homePeriod.snapshot?.scope
                            if(source!=null && source==homeStatisticsScope(scope) && !homePeriod.loading && homePeriod.error==null) openHomeDetails(drillScope(source,period=period))
                        },onTodayDrill={kind->
                            val source=homeToday.snapshot?.scope
                            if(source!=null && source==todayStatisticsScope(scope,todayDate) && !homeToday.loading && homeToday.error==null) openHomeDetails(source.copy(kinds=setOf(kind)))
                        })
                    "更多" -> MorePage(onOpen=::navigatePage)
                    "调色盘","尺子","噪音测量","做个决定","石头剪刀布" -> toolStates.SaveableStateProvider(page) {
                        when(page) {
                            "调色盘" -> PalettePage()
                            "尺子" -> RulerPage(utilities,::requestBack)
                            "噪音测量" -> NoisePage(utilities)
                            "做个决定" -> DecisionPage()
                            "石头剪刀布" -> RockPaperScissorsPage()
                        }
                    }
                    "明细" -> DetailsPage(s,entryPage,scope,todayDate,detailsScroll,
                        onRange=::setRange,onMore={controller.loadEntries(scope,append=true)},onRetry={controller.loadEntries(scope,force=true)},
                        onOpen={entry->controller.navigate {it.copy(selectedEntryId=entry.id,page="账单详情")}},onCapture=actions::chooseScreenshot,
                        onClearFilter={setScope(scope.copy(keyword="",kinds=emptySet(),categoryIds=emptySet()))})
                    "统计" -> Column(Modifier.fillMaxSize()) {
                        DateRangeSelector(start,end,todayDate,::setRange)
                        QueryStatus(statisticsPage.loading,stats?.scope,scope,statisticsPage.error) {controller.loadStatistics(scope,force=true)}
                        if(stats!=null) {
                            val interactive=!statisticsPage.loading && statisticsPage.error==null && stats.scope==scope && statisticsPage.scope==scope
                            StatisticsPage(s,stats,todayDate,stats.scope.ledgerIds.singleOrNull(),stats.scope.startDate,stats.scope.endDate,
                                ui.busy || !interactive,statisticsScroll,interactive=interactive,
                                onBudget={amount,done->if(interactive) stats.scope.ledgerIds.singleOrNull()?.let {controller.perform(Command.SetBudget(it,stats.scope.startDate.take(7),amount)) {done()}}},
                                onDrill={category,date,ledger->if(interactive) {
                                    val narrowed=drillScope(stats.scope,category,date,ledger)
                                    controller.navigate {it.copy(page="明细",tab="统计",detailsScope=narrowed,drillReturnScope=it.detailsScope)}
                                }},onRefundDrill={category->if(interactive) {
                                    val narrowed=drillScope(stats.scope,categoryId=category,categoryKind=EntryKind.REFUND)
                                    controller.navigate {it.copy(page="明细",tab="统计",detailsScope=narrowed,drillReturnScope=it.detailsScope)}
                                }})
                        }
                    }
                    "Debug" -> key(s.datasetId) { DebugPage(s,todayDate,ui.busy || pendingOperation!=null,
                        onGenerate={ledgerId,from,to,count->controller.perform(Command.GenerateDebugEntries(ledgerId,from,to,count)) {controller.notify("已生成 $count 条测试账单")}},
                        onUndo={batchId->controller.perform(Command.UndoImport(batchId)) {controller.notify("已撤销这批测试数据")}}) }
                    "我的" -> MyPage(s,actions,lockEnabled,onNavigate=::navigatePage,onDetails={controller.navigate {it.copy(page="明细",detailsScope=homeStatisticsScope(it.homeScope),drillReturnScope=null)}},onTheme={controller.perform(Command.SetPreference("theme",it))},onConfirm={confirm=it})
                    "记一笔" -> editor?.let {draft->key(draft.operationId) {EntryForm(s,draft,ui.busy,onChange={value->controller.changeEditor(draft.operationId,value)},onSave={keep->controller.saveEditor(keep) {if(keep) controller.notify("已保存，可以继续记账") else controller.navigate {it.copy(page=it.editorReturnPage)}}},onCapture=actions::chooseScreenshot,onRetryDuplicateCheck=controller::retryCaptureDuplicateCheck)}}
                    "账单详情" -> s.entries.find {it.id==navigation.selectedEntryId}?.let {entry->EntryDetail(s,entry,ui.busy,onEdit={controller.beginEditor(entry.ledgerId,editId=entry.id)},onRefund={controller.beginEditor(entry.ledgerId,refundParentId=entry.id)},onDelete={confirm=Confirmation("移入回收站？","原支出及当前有效退款会一起移入回收站，可在「我的」的回收站中恢复。") {controller.perform(Command.SoftDelete(entry.id)) {navigatePage("明细")}}},onMove={ledger,category->controller.perform(Command.MoveEntry(entry.id,ledger,category))})} ?: EmptyState("这笔账单已不存在","请返回明细查看最新数据")
                    "账本管理" -> LedgerManagement(s,controller,onConfirm={confirm=it})
                    "分类管理" -> CategoryManagement(s,selectedLedger,controller,onConfirm={confirm=it})
                    "回收站" -> RecyclePage(s,controller,onConfirm={confirm=it})
                    "Excel 与账单导入" -> TemplatePage()
                }
                if(ui.busy && !ui.savingPreference) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
        if(ledgerPicker && s!=null) ChoiceDialog("选择账本",listOf(null to "全部账本（含归档）")+s.ledgers.sortedBy {it.sortOrder}.map {it.id to (it.name+if(it.archived) " · 已归档" else "")},selectedLedger,onSelect={id->setScope(scope.copy(ledgerIds=id?.let {setOf(it)} ?: emptySet(),categoryIds=emptySet()));ledgerPicker=false},onDismiss={ledgerPicker=false})
        if(filters && s!=null) {
            val filterScope=if(searchFromHome) homeStatisticsScope(navigation.homeScope) else scope
            FilterDialog(s,filterScope.ledgerIds.singleOrNull(),filterScope.keyword,filterScope.kinds.singleOrNull()?.name,filterScope.categoryIds.singleOrNull(),
                search=searchFromHome,onDismiss={filters=false},onApply={keyword,kind,category->
                    val next=filterScope.copy(keyword=keyword,kinds=kind?.let {setOf(EntryKind.valueOf(it))} ?: emptySet(),categoryIds=category?.let {setOf(it)} ?: emptySet())
                    if(searchFromHome) openHomeDetails(next) else setScope(next)
                    filters=false
                })
        }
        confirm?.let {confirmation->AlertDialog(onDismissRequest={confirm=null},title={Text(confirmation.title)},text={Text(confirmation.message)},confirmButton={TextButton(onClick={confirm=null;confirmation.action()}) {Text("确认")}},dismissButton={TextButton(onClick={confirm=null}) {Text("取消")}})}
        pendingOperation?.let {recovery->
            AlertDialog(onDismissRequest={},title={Text(if(recovery.outcome!=null) "上次操作已提交" else "上次操作未确认提交")},text={
                Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(if(recovery.outcome!=null) "本机已找到这次操作的提交记录，数据已经保存。请确认后查看明细或「我的」中的对应功能，不要重复录入。"
                        else "本机未找到这次操作的提交记录。未保存的输入只保留在当前进程，请先核对明细，再决定是否重新操作。")
                    Text("此提示会保留到你确认；确认不会删除已经保存的数据。",style=MaterialTheme.typography.bodySmall)
                }
            },confirmButton={TextButton(onClick={controller.acknowledgePendingOperation(recovery.operationId)},enabled=!ui.busy) {Text(if(ui.busy) "正在确认…" else "已了解，继续")}})
        }

    }
}

internal data class Confirmation(val title:String,val message:String,val action:()->Unit)
@Composable internal fun EmptyState(title:String,body:String) { Column(Modifier.fillMaxWidth().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) { Text("◌",fontSize=48.sp,color=MaterialTheme.colorScheme.primary);Text(title,style=MaterialTheme.typography.titleMedium);Text(body,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable internal fun SectionTitle(text:String) { Text(text,Modifier.padding(top=12.dp,bottom=4.dp),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold) }
@Composable internal fun AmountText(amount:Long,modifier:Modifier=Modifier,prefix:String="¥ ") { Text(prefix+Money.format(amount),modifier,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold) }
@Composable internal fun EntryRow(s:LedgerState,e:Entry,enabled:Boolean=true,onClick:()->Unit) {
    Card(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.secondaryContainer) {Text(when(e.kind){EntryKind.EXPENSE->"支";EntryKind.INCOME->"收";EntryKind.REFUND->"退"},Modifier.padding(12.dp))}
                Column(Modifier.weight(1f)) {Text(s.categories.find {it.id==e.categoryId}?.name ?: "分类",fontWeight=FontWeight.Medium);Text(e.occurrence.replace('T',' '),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            Text(e.note.ifEmpty {s.ledgers.find {it.id==e.ledgerId}?.name ?: ""},style=MaterialTheme.typography.bodyMedium)
            Text((if(e.kind==EntryKind.EXPENSE) "−" else "+")+Money.format(e.amountMinor),Modifier.align(Alignment.End),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,color=if(e.kind==EntryKind.EXPENSE) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
        }
    }
}
@Composable private fun DetailsPage(s:LedgerState,entries:EntryPageUi,scope:QueryScope,today:String,listState:LazyListState,onRange:(DateRange)->Unit,onMore:()->Unit,onRetry:()->Unit,onOpen:(Entry)->Unit,onCapture:()->Unit,onClearFilter:()->Unit) {
    val interactive=!entries.loading && entries.error==null && entries.contentScope==scope && entries.scope==scope
    Column(Modifier.fillMaxSize()) {
        DateRangeSelector(scope.startDate,scope.endDate,today,onRange)
        QueryStatus(entries.loading,entries.contentScope,scope,entries.error,onRetry)
        LazyColumn(Modifier.weight(1f),state=listState,contentPadding=PaddingValues(16.dp,0.dp,16.dp,100.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item("tools") {FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(onClick=onCapture) {Text("▧ 截图记账")}
                if(scope.keyword.isNotEmpty() || scope.categoryIds.isNotEmpty() || scope.kinds.isNotEmpty()) TextButton(onClick=onClearFilter) {Text("清除筛选")}
            }}
            if(entries.loaded) item("count") {Text("已显示 ${entries.entries.size} 笔"+if(entries.nextCursor!=null) " · 还有更多" else "",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(entries.loaded && entries.entries.isEmpty() && entries.error==null) item("empty") {EmptyState(if(entries.contentScope==scope) "当前范围没有账单" else "上次筛选结果暂无账单","可以调整日期或筛选，也可以点击右下角加号开始")}
            items(entries.entries,key={it.id}) {entry->EntryRow(s,entry,enabled=interactive) {onOpen(entry)}}
            if(entries.nextCursor!=null && !entries.loading) item("more") {TextButton(onClick=onMore,enabled=interactive,modifier=Modifier.fillMaxWidth()) {Text("加载下一页")}}
        }
    }
}
@Composable internal fun SummaryCard(stats:StatisticsSnapshot?) {
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) { Text("本期净支出",style=MaterialTheme.typography.labelLarge);Text("¥ "+Money.format(stats?.netExpense ?: 0),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) { Column {Text("收入",style=MaterialTheme.typography.labelMedium);Text(Money.format(stats?.income ?: 0))};Column {Text("原始支出",style=MaterialTheme.typography.labelMedium);Text(Money.format(stats?.expense ?: 0))};Column {Text("退款",style=MaterialTheme.typography.labelMedium);Text(Money.format(stats?.refunds ?: 0))};Column {Text("本期结余",style=MaterialTheme.typography.labelMedium);Text(Money.format(stats?.netIncome ?: 0))} }
        }
    }
}
@Composable internal fun <T> ChoiceDialog(title:String,options:List<Pair<T,String>>,selected:T,onSelect:(T)->Unit,onDismiss:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={LazyColumn {items(options.size) {i->val (value,label)=options[i];Row(Modifier.fillMaxWidth().clickable {onSelect(value)}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {RadioButton(selected==value,onClick={onSelect(value)});Text(label,Modifier.weight(1f))}}}},confirmButton={TextButton(onClick=onDismiss) {Text("关闭")}})
}
@Composable private fun FilterDialog(s:LedgerState,ledgerId:String?,initialKeyword:String,initialKind:String?,initialCategory:String?,search:Boolean=false,onDismiss:()->Unit,onApply:(String,String?,String?)->Unit) {
    var keyword by remember {mutableStateOf(initialKeyword)};var kind by remember {mutableStateOf(initialKind)};var category by remember {mutableStateOf(initialCategory)};var cat by remember {mutableStateOf(false)}
    val focus=remember {FocusRequester()}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(search) "搜索账单" else "筛选账单")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(keyword,{keyword=it},modifier=Modifier.focusRequester(focus),label={Text("搜索备注")},singleLine=true)
        LaunchedEffect(search) {if(search) focus.requestFocus()}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(null to "全部","EXPENSE" to "支出","INCOME" to "收入","REFUND" to "退款").forEach {(k,n)->FilterChip(kind==k,{kind=k},label={Text(n)})}}
        TextButton(onClick={cat=true}) {Text("分类："+(s.categories.find {it.id==category}?.name ?: "全部"))}
    }},confirmButton={TextButton(onClick={onApply(keyword,kind,category)}) {Text("应用")}},dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})
    if(cat) ChoiceDialog("筛选分类",listOf(null to "全部分类")+s.categories.filter {ledgerId==null || it.ledgerId==ledgerId}.map {it.id to it.name},category,{category=it;cat=false},{cat=false})
}

@Composable private fun MyPage(s:LedgerState,actions:PlatformActions,lockEnabled:Boolean,onNavigate:(String)->Unit,onDetails:()->Unit,onTheme:(String)->Unit,onConfirm:(Confirmation)->Unit) {
    var passwordMode by remember {mutableStateOf<String?>(null)}
    LazyColumn(contentPadding=PaddingValues(16.dp,8.dp,16.dp,32.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item {SectionTitle("记账工具")}
        item {SettingRow("截图记账","选择本地截图，识别后核对并保存",onClick=actions::chooseScreenshot)}
        item {SettingRow("账单明细","查看与搜索当前账本的账单",onClick=onDetails)}
        item {SectionTitle("我的账本")};item {SettingRow("账本管理","按用途独立记账；归档保留历史") {onNavigate("账本管理")}};item {SettingRow("分类管理","收入与支出分类可排序、停用") {onNavigate("分类管理")}};item {SettingRow("回收站","已删除 ${s.entries.count {!it.active}} 笔") {onNavigate("回收站")}}
        item {SectionTitle("本地数据")};item {SettingRow("导出 JSON 备份","包含账本、流水、退款、预算和回收站") {onConfirm(Confirmation("导出明文备份？","JSON 文件可被持有文件的人读取，请保存到你信任的本地位置。") {actions.exportBackup(false,charArrayOf())})}}
        item {SettingRow("导出加密备份","使用独立口令保护 .dwybak 文件") {passwordMode="export"}}
        item {SettingRow("从备份恢复","校验预览后整体替换；保留一次保护副本") {passwordMode="restore"}}
        item {SettingRow("Excel 与账单导入","模板待确认，当前提供 JSON 备份迁移") {onNavigate("Excel 与账单导入")}}
        item {SettingRow("恢复保护副本","重新验证并预览上次替换前的本机账本") {actions.restoreProtectionBackup()}}
        item {SettingRow("清理保护副本","删除上一次恢复前保留的本机数据") {onConfirm(Confirmation("清理保护副本？","不会影响当前账本，清理后不能用此副本恢复。",actions::clearProtectionBackup))}}
        item {SectionTitle("隐私与外观")};item {Card {Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)) {Text("应用锁");Text("强生物识别或设备锁屏凭据",style=MaterialTheme.typography.bodySmall)};Switch(lockEnabled,onCheckedChange=actions::setAppLock)}}}
        item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach {(value,label)->FilterChip((s.preferences.find {it.key=="theme"}?.value ?: "system")==value,{onTheme(value)},label={Text(label)})}}}
        item {SettingRow("清空全部数据","包括回收站、内部副本与暂存文件；保留应用锁") {onConfirm(Confirmation("清空所有本机记账数据？","此操作无法撤销。请先保存并验证备份，应用锁设置会保留。",actions::clearAllData))}}
        item {Text("DawnYew · 完全离线 · 免费\n不创建账号，不上传账单。截图只用于本次识别，不存储原图。",Modifier.padding(vertical=16.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
    passwordMode?.let {mode->PasswordDialog(mode=="export",onDismiss={passwordMode=null},onConfirm={password->passwordMode=null;if(mode=="export") actions.exportBackup(true,password.toCharArray()) else actions.restoreBackup(password.toCharArray())})}
}
@Composable internal fun SettingRow(title:String,subtitle:String,enabled:Boolean=true,onClick:()->Unit) {Card(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth()) {Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(title,fontWeight=FontWeight.Medium);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text("›",Modifier.padding(start=12.dp),fontSize=24.sp)}}}
@Composable private fun PasswordDialog(export:Boolean,onDismiss:()->Unit,onConfirm:(String)->Unit) {
    var password by remember {mutableStateOf("")};var repeat by remember {mutableStateOf("")};var error by remember {mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(export) "设置备份口令" else "选择备份恢复")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(if(export) "使用 12–128 个字符。口令遗失无法恢复。" else "JSON 明文备份无需口令；加密备份请填写原口令。")
        OutlinedTextField(password,{password=it;error=null},label={Text("备份口令")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),singleLine=true)
        if(export) OutlinedTextField(repeat,{repeat=it;error=null},label={Text("再次输入口令")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),singleLine=true)
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={if(export && (codePointCount(password) !in 12..128 || password!=repeat)) error="口令长度不符或两次输入不一致" else onConfirm(password)}) {Text(if(export) "导出" else "选择文件")}},dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})
}
@Composable private fun TemplatePage() {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {Text("模板入口已预留",style=MaterialTheme.typography.headlineSmall);Text("微信、支付宝和 DawnYew Excel 模板的具体字段尚未确认，因此当前版本不会猜测列名或导入规则。完整数据迁移请使用 JSON 或加密备份。");billTemplates.forEach {Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp)) {Text(it.name,style=MaterialTheme.typography.titleMedium);Text(it.reason,Modifier.padding(top=8.dp));Text("等待模板",Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}}
