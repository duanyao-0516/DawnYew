package com.dawnyew.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
    val statisticsPage by controller.statistics.collectAsState()
    val yearPage by controller.yearStatistics.collectAsState()
    val budgetPage by controller.budgetStatistics.collectAsState()
    val homePeriod by controller.homeStatistics.collectAsState()
    val navigation by controller.navigation.collectAsState()
    val generation by controller.transientGeneration.collectAsState()
    val financeNavigation by controller.finance.navigation.collectAsState()
    val financeState by controller.finance.snapshot.collectAsState()
    val activities by controller.finance.activities.collectAsState()
    val activitySummary by controller.finance.activitySummary.collectAsState()
    val recent by controller.finance.homeActivities.collectAsState()
    val calendar by controller.finance.calendar.collectAsState()
    val pending by controller.finance.pending.collectAsState()
    val profile by controller.finance.profile.collectAsState()
    val recurring by controller.recurring.state.collectAsState()
    val panels by controller.dashboard.panels.collectAsState()
    val accountHistory by controller.dashboard.history.collectAsState()
    val s=ui.dataset
    val page=navigation.page
    val tab=navigation.tab
    val statisticsRoutes=PanelCatalog.definitions(PanelSurface.STATISTICS).map {it.title}.toSet()+setOf("统计","分类统计","日趋势","月度收支")
    val scope=if(page in statisticsRoutes) financeNavigation.sourceStatisticsScope ?: navigation.statisticsScope else navigation.scope
    val financeLedgerIds=financeNavigation.sourceLedgerIds ?: scope.ledgerIds
    val selectedLedger=financeLedgerIds.singleOrNull()
    val homeScope=homeStatisticsScope(navigation.homeScope)
    val homeBudgetScope=homeMonthlyScope(homeScope)
    var homeCalendarMonth by rememberSaveable(generation,s?.datasetId,homeScope.startDate,homeScope.endDate) {mutableStateOf(homeScope.endDate.take(7))}
    val homeCalendarQuery=homeCalendarScope(homeScope,homeCalendarMonth)
    val statisticsScope=homeStatisticsScope(financeNavigation.sourceStatisticsScope ?: navigation.statisticsScope)
    val homeScroll=rememberLazyListState()
    val statisticsScroll=rememberLazyListState()
    val toolStates=rememberSaveableStateHolder()
    // Reordering a dashboard starts at its new first card; returning from an unchanged detail preserves scrolling.
    LaunchedEffect(s?.preferences?.find {it.key=="homePanels"}?.value) {homeScroll.scrollToItem(0)}
    LaunchedEffect(s?.preferences?.find {it.key=="statisticsPanels"}?.value) {statisticsScroll.scrollToItem(0)}
    var todayDate by remember {mutableStateOf(controller.environment.localNow().take(10))}
    var selectedYear by remember(generation) {mutableStateOf(statisticsScope.startDate.take(4))}
    val yearScope=statisticsScope.copy(startDate="$selectedYear-01-01",endDate="$selectedYear-12-31")
    LaunchedEffect(Unit) {while(true) {todayDate=controller.environment.localNow().take(10);delay(30_000)}}
    val editorPresence = remember(controller,generation) { var count=0; {opened:Boolean->count=(count+if(opened) 1 else -1).coerceAtLeast(0);controller.setFinancialEditorOpen(count>0)} }
    val theme=s?.preferences?.find {it.key=="theme"}?.value ?: "system"
    DawnYewTheme(theme) {CompositionLocalProvider(LocalCompactAmounts provides (s?.preferences?.find {it.key=="amountDisplay"}?.value=="compact"),LocalFinanceEditorPresence provides editorPresence) {key(generation,s?.datasetId) {
        val snack=remember {SnackbarHostState()}
        val financePages=rememberSaveableStateHolder()
        LaunchedEffect(ui.message) {ui.message?.let {snack.showSnackbar(it);controller.clearMessage()}}
        var ledgerPicker by remember {mutableStateOf(false)}
        var confirm by remember {mutableStateOf<Confirmation?>(null)}
        var transferAmount by remember {mutableStateOf("")}
        var entrySavedFor by remember {mutableStateOf<String?>(null)}
        var mbtiBack by remember {mutableStateOf<(() -> Boolean)?>(null)}
        val registerMbtiBack = remember { {handler: (() -> Boolean)? -> mbtiBack = handler} }
        LaunchedEffect(entrySavedFor) {
            val operation=entrySavedFor
            if(operation!=null) {delay(1800);if(entrySavedFor==operation) entrySavedFor=null}
        }
        val busy=ui.busy || pendingOperation!=null
        val primary=isPrimaryFinanceDestination(page,financeNavigation.id)
        val snapshot=financeState.snapshot?.takeIf {!financeState.loading && financeState.error==null && financeState.ledgerIds==financeLedgerIds && financeState.datasetId==s?.datasetId && financeState.contentVersion==s?.contentVersion}
        val now=controller.environment.localNow()
        val currentNavigationPage=rememberUpdatedState(page)
        val currentHomeScope=rememberUpdatedState(homeScope)
        val currentStatisticsScope=rememberUpdatedState(statisticsScope)
        val dispatch:FinanceDispatch={action,id,done->controller.finance.perform(action,id,done);Unit}
        val command:CommandDispatch={value,id,done->controller.perform(value,id) {done()};Unit}
        fun navigatePage(destination:String) {
            if(destination=="MBTI 测试") controller.mbti.openIntro(controller.mbti.state.value.generation)
            if(currentNavigationPage.value=="首页" && destination in statisticsRoutes && !isPrimaryFinanceDestination(destination)) {
                // A new home-card visit starts from that card's default kind; drill-back retains its state.
                financePages.removeState("statistics:home:$destination")
                controller.finance.openStatistics(destination,currentHomeScope.value)
            } else controller.finance.open(destination)
        }
        fun selectObject(id:String?) {if(id==null) controller.finance.back() else controller.finance.open(page,id)}
        fun openActivityId(family:ActivityFamily,id:String) {controller.finance.open(if(family==ActivityFamily.ENTRY) "账单详情" else "转账详情",id)}
        fun finishBack() {
            if(page=="记一笔") {controller.clearEditor();controller.navigate {it.copy(page=it.editorReturnPage)}}
            else if(!controller.finance.back()) controller.finance.selectTab(tab)
        }
        fun requestBack() {
            if(page=="MBTI 测试" && (mbtiBack?.invoke() ?: true)) return
            if(page=="记一笔" && busy) {controller.notify("正在保存，请等待结果");return}
            if(page=="记一笔" && editor?.needsDiscardConfirmation==true) confirm=Confirmation("放弃未保存的账单？","输入内容和截图识别草稿会被清除；已保存的账单不受影响。",::finishBack)
            else finishBack()
        }
        SideEffect {onBackHandlerChanged(if(!primary || financeNavigation.id!=null) ({requestBack()}) else null)}
        DisposableEffect(Unit) {onDispose {onBackHandlerChanged(null)}}
        LaunchedEffect(captureDraft,s?.datasetId) {if(captureDraft!=null && s!=null) {controller.beginEditor(selectedLedger,capture=captureDraft);onCaptureConsumed()}}
        LaunchedEffect(page,editor) {if(page=="记一笔" && editor==null) {controller.finance.selectTab("首页");controller.notify("未保存草稿已清除，请重新录入")}}
        LaunchedEffect(financeLedgerIds,s?.datasetId,s?.contentVersion) {if(s!=null) controller.finance.loadSnapshot(financeLedgerIds)}
        LaunchedEffect(page,statisticsScope,selectedYear,s?.datasetId,s?.contentVersion) {if(page in statisticsRoutes && s!=null) {controller.loadStatistics(statisticsScope);controller.loadYearStatistics(yearScope)}}
        LaunchedEffect(page,homeScope,homeCalendarQuery,homeBudgetScope,todayDate,s?.datasetId,s?.contentVersion) {if(page in listOf("首页","更多") && s!=null) {
            controller.loadHome(homeScope,todayDate)
            controller.loadBudgetStatistics(homeBudgetScope)
            if(page=="首页") {
                controller.finance.loadHome(homeScope.toActivityScope(),homeCalendarQuery.toActivityScope())
            }
        }}
        LaunchedEffect(page,financeLedgerIds,s?.datasetId,s?.contentVersion) {if(page=="我的" && s!=null) controller.finance.loadProfile(financeLedgerIds)}
        LaunchedEffect(page,financeNavigation.activityScope,s?.datasetId,s?.contentVersion) {if(page in listOf("流水","明细","回收站") && s!=null) controller.finance.loadActivities(if(page=="回收站") ActivityScope(deleted=true) else financeNavigation.activityScope ?: scope.toActivityScope())}
        fun setScope(value:QueryScope) {if(page in statisticsRoutes && page!="统计") controller.finance.selectStatisticsScope(value) else controller.selectScope(value)}
        fun setHomeRange(value:DateRange) {controller.selectScope(currentHomeScope.value.copy(startDate=value.startDate,endDate=value.endDate))}
        fun setStatisticsRange(value:DateRange) {controller.finance.selectStatisticsScope(currentStatisticsScope.value.copy(startDate=value.startDate,endDate=value.endDate))}
        fun startTransfer(amount:String) {transferAmount=amount;navigatePage("新增转账")}
        val branded=page in listOf("首页","我的","记一笔")
        val objectTitle=financeNavigation.id?.let {id->when(page) {
            "账户"->s?.accountDisplayName(id)
            "借入","借出","报销"->s?.obligations?.find {it.id==id}?.title
            "周期"->s?.recurringPlans?.find {it.id==id}?.name
            "存钱"->s?.savingsPlans?.find {it.id==id}?.name
            else->null
        }}
        Scaffold(
            containerColor=MaterialTheme.colorScheme.background,
            topBar={if(page!="尺子") {
                if(page=="统计") Spacer(Modifier.fillMaxWidth().statusBarsPadding())
                else Surface(color=if(branded) FinanceColors.brand else MaterialTheme.colorScheme.background,
                    contentColor=if(branded) Color.White else MaterialTheme.colorScheme.onBackground) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        if(page=="首页") {
                            TextButton(onClick={ledgerPicker=true},colors=ButtonDefaults.textButtonColors(contentColor=Color.White)) {
                                Text((s?.ledgers?.find {it.id==selectedLedger}?.name ?: "全部账本")+" ▾",style=MaterialTheme.typography.titleLarge)
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick={controller.finance.showActivities(homeScope.toActivityScope())},modifier=Modifier.semantics {contentDescription="搜索账单"}) {AppGlyph("搜索")}
                        } else {
                            if(!primary) IconButton(onClick=::requestBack,modifier=Modifier.semantics {contentDescription="返回"}) {AppGlyph("返回")}
                            else Spacer(Modifier.width(48.dp))
                            Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
                                if(page=="账户" && primary) TextButton(onClick={ledgerPicker=true}) {Text((s?.ledgers?.find {it.id==selectedLedger}?.name ?: "全部账本")+" ▾",style=MaterialTheme.typography.titleLarge)}
                                else Text(if(page=="记一笔" && entrySavedFor!=null && entrySavedFor==editor?.operationId) "记一笔 · 已保存" else objectTitle ?: page,style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            if(page=="我的") IconButton(onClick={navigatePage("设置")},modifier=Modifier.semantics {contentDescription="设置"}) {AppGlyph("设置")}
                            else Spacer(Modifier.width(48.dp))
                        }
                    }
                }
            }},
            bottomBar={if(primary) Surface(color=MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().height(54.dp),verticalAlignment=Alignment.CenterVertically) {
                    listOf("首页","账户","统计","更多","我的").forEach {title->
                        FinanceNavigationItem(title,page==title,{controller.finance.selectTab(title)},if(title=="我的") ({navigatePage("Debug")}) else null)
                    }
                }
            }},
            floatingActionButton={if(page in listOf("首页","流水")) FloatingActionButton(onClick={controller.beginEditor(selectedLedger)},shape=CircleShape,containerColor=FinanceColors.brand,contentColor=Color.White,modifier=Modifier.size(64.dp).semantics {contentDescription="记一笔"}) {AppGlyph("新增")}},
            snackbarHost={SnackbarHost(snack)},
        ) {padding->
            if(s==null) Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.Center) {CircularProgressIndicator()}
            else Box(Modifier.fillMaxSize().then(if(page=="尺子") Modifier else Modifier.padding(padding).consumeWindowInsets(padding))) {
                when(page) {
                    "首页" -> FinanceHomePage(s,homePeriod,homeScope,todayDate,homeScroll,recent,calendar,pending,snapshot,{setHomeRange(it)},{controller.loadHome(homeScope,todayDate,true);controller.finance.loadHome(homeScope.toActivityScope(),homeCalendarQuery.toActivityScope(),true);controller.loadBudgetStatistics(homeBudgetScope,true)},::navigatePage,controller.finance::openActivity,controller.finance::showActivities,panels=panels,onLoadPanel={controller.dashboard.loadPanel(it)},monthlyPeriod=budgetPage,calendarScope=homeCalendarQuery,onCalendarMonth={homeCalendarMonth=it.startDate.take(7)})
                    "账户" -> financePages.SaveableStateProvider("account:${financeNavigation.id.orEmpty()}") { AccountsPage(s,snapshot,selectedLedger,busy,now,controller.environment::id,dispatch,financeNavigation.id,::selectObject,controller.finance::showActivities,::navigatePage,history=accountHistory,onLoadHistory={controller.dashboard.loadHistory(it)},accountActivities=activities,onLoadAccountActivities={controller.finance.loadActivities(it)},onOpenActivity=controller.finance::openActivity) }
                    "资产趋势" -> AssetHistoryPage(s,financeLedgerIds,now,accountHistory,{controller.dashboard.loadHistory(it)})
                    "更多" -> financePages.SaveableStateProvider("more") { MorePage(summaries=moreSummaries(snapshot,s,selectedLedger,budgetPage.snapshot?.takeIf {budgetPage.scope==homeBudgetScope && !budgetPage.loading && budgetPage.error==null},homeBudgetScope),onOpen=::navigatePage) }
                    "工具箱" -> ToolboxPage(::navigatePage)
                    "MBTI 测试" -> MbtiPage(controller.mbti,registerMbtiBack)
                    "调色盘","尺子","噪音测量","做个决定","石头剪刀布" -> toolStates.SaveableStateProvider(page) {when(page) {"调色盘"->PalettePage();"尺子"->RulerPage(utilities,::requestBack);"噪音测量"->NoisePage(utilities);"做个决定"->DecisionPage();"石头剪刀布"->RockPaperScissorsPage()}}
                    "流水","明细" -> {val activityScope=financeNavigation.activityScope ?: scope.toActivityScope();ActivitiesPage(s,activities,activitySummary,activityScope,{controller.finance.loadActivities(activityScope,append=true)},{controller.finance.loadActivities(activityScope,force=true)},controller.finance::openActivity,controller.finance::updateActivities,actions::chooseScreenshot)}
                    in statisticsRoutes -> financePages.SaveableStateProvider("statistics:${if(financeNavigation.sourceStatisticsScope!=null) "home" else "tab"}:$page") { FinanceStatisticsPage(s,statisticsPage,yearPage,statisticsScope,selectedYear,todayDate,statisticsScroll,page.takeUnless {it=="统计"},{setStatisticsRange(it)},{selectedYear=it},{controller.loadStatistics(statisticsScope,true)},{controller.loadYearStatistics(yearScope,true)},{controller.finance.showActivities(it.toActivityScope())},::navigatePage,panels=panels,onLoadPanel={controller.dashboard.loadPanel(it)},onActivities=controller.finance::showActivities,onEntry={openActivityId(ActivityFamily.ENTRY,it)},onChooseLedger={ledgerPicker=true}) }
                    "借入","借出","报销" -> ObligationsPage(s,snapshot,when(page){"借入"->ObligationKind.BORROW;"借出"->ObligationKind.LEND;else->ObligationKind.REIMBURSEMENT},selectedLedger,busy,now,controller.environment::id,dispatch,financeNavigation.id,::selectObject,controller.finance::showActivities,onOpenActivity=::openActivityId)
                    "往来" -> ContactsPage(s,selectedLedger,busy,controller.environment::id,dispatch,controller.finance::showActivities)
                    "周期" -> RecurringPage(s,selectedLedger,financeNavigation.id,::selectObject,busy,now,controller.environment::id,dispatch,::openActivityId,recurring,{controller.recurring.continueDue()})
                    "存钱" -> SavingsPage(s,snapshot,selectedLedger,financeNavigation.id,::selectObject,busy,now,controller.environment::id,dispatch,::openActivityId)
                    "预算" -> BudgetPage(s,selectedLedger,homeBudgetScope.startDate.take(7),busy,controller.environment::id,dispatch,command,budgetPage,{controller.loadBudgetStatistics(it)},controller.finance::showActivities)
                    "项目","商家","标签" -> DimensionsPage(s,when(page){"项目"->DimensionKind.PROJECT;"商家"->DimensionKind.MERCHANT;else->DimensionKind.TAG},selectedLedger,busy,controller.environment::id,dispatch,controller.finance::showActivities)
                    "Debug" -> DebugPage(s,todayDate,busy,onGenerate={ledgerId,from,to,count->controller.perform(Command.GenerateDebugEntries(ledgerId,from,to,count)) {controller.notify("已生成 $count 条测试账单")}},onUndo={batchId->controller.perform(Command.UndoImport(batchId)) {controller.notify("已撤销这批测试数据")}})
                    "我的" -> financePages.SaveableStateProvider("profile") { ProfilePage(snapshot,profile.summary?.takeIf {!profile.loading && profile.error==null && profile.scope?.ledgerIds==financeLedgerIds && profile.datasetId==s.datasetId},::navigatePage) }
                    "备份" -> BackupPage(actions,{confirm=it},::navigatePage)
                    "安全" -> SecurityPage(actions,lockEnabled,::navigatePage)
                    "设置" -> financePages.SaveableStateProvider("settings") { LocalSettingsPage(s,actions,{controller.perform(Command.SetPreference("theme",it))},{key,value->controller.perform(Command.SetPreference(key,value))},{confirm=it},::navigatePage) }
                    "手册" -> LocalHelpPage()
                    "提醒" -> ReminderPage(snapshot,s.recurringPlans,todayDate) {route,id->controller.finance.open(route,id)}
                    "主页面板","统计面板" -> {
                        val surface=if(page=="主页面板") PanelSurface.HOME else PanelSurface.STATISTICS
                        PanelManagementPage(s,surface,busy) {layout->controller.perform(Command.SetPreference(PanelCatalog.preferenceKey(surface),PanelCatalog.encode(surface,layout)))}
                    }
                    "自动" -> AutoCapturePage(actions.capturePlatform,actions::chooseScreenshot)
                    "货币" -> SettingsSection {SettingsText("人民币 CNY","账本统一以人民币元显示，金额精确到分。")}
                    "记一笔" -> editor?.let {draft->key(draft.operationId) {EntryForm(s,draft,busy,onChange={controller.changeEditor(draft.operationId,it)},onSave={keep->controller.saveEditor(keep) {if(keep) {controller.clearMessage();entrySavedFor=controller.editor.value?.operationId} else controller.navigate {it.copy(page=it.editorReturnPage)}}},onCapture=actions::chooseScreenshot,onRetryDuplicateCheck=controller::retryCaptureDuplicateCheck,newId=controller.environment::id,onSaveDimension={value,id,done->controller.createEditorDimension(draft.operationId,value,id,done)},onTransfer={amount->confirm=Confirmation("切换到转账？","金额会保留，当前分类、备注及其它未保存字段会清除。") {controller.clearEditor();controller.navigate {it.copy(page=it.editorReturnPage)};startTransfer(amount)}})}}
                    "新增转账" -> TransferEditor(s,null,selectedLedger,transferAmount,now,busy,controller.environment::id,dispatch,::finishBack)
                    "编辑转账" -> s.transfers.find {it.id==financeNavigation.id}?.let {TransferEditor(s,it,selectedLedger,transferAmount,now,busy,controller.environment::id,dispatch,::finishBack)} ?: EmptyState("这笔转账已不存在","请返回查看最新流水")
                    "编辑往来流水" -> s.transfers.find {it.id==financeNavigation.id}?.let {value->s.obligations.find {it.id==value.businessId}?.let {obligation->ObligationMovementEditor(s,obligation,value.businessKind==TransferBusinessKind.OBLIGATION_SETTLEMENT,snapshot?.obligations?.find {it.obligation.id==obligation.id}?.remainingMinor,now,busy,controller.environment::id,dispatch,value,::finishBack)}} ?: EmptyState("这笔往来记录已不存在","请返回查看最新流水")
                    "编辑存钱记录" -> s.transfers.find {it.id==financeNavigation.id}?.let {value->s.savingsMovements.find {it.id==value.businessId}?.let {movement->s.savingsPlans.find {it.id==movement.planId}?.let {plan->SavingsMovementEditor(s,plan,movement.kind,movement,now,busy,controller.environment::id,dispatch,::finishBack)}}} ?: EmptyState("这笔存钱记录已不存在","请返回查看最新流水")
                    "转账详情" -> s.transfers.find {it.id==financeNavigation.id}?.let {value->TransferDetail(s,value,busy,controller.environment::id,dispatch,{controller.finance.open(when(value.businessKind){TransferBusinessKind.MANUAL->"编辑转账";TransferBusinessKind.SAVINGS->"编辑存钱记录";else->"编辑往来流水"},value.id)},{route,id->controller.finance.open(route,id)},::finishBack)} ?: EmptyState("这笔转账已不存在","请返回查看最新流水")
                    "账单详情" -> s.entries.find {it.id==navigation.selectedEntryId}?.let {entry->EntryDetail(s,entry,busy,onEdit={controller.beginEditor(entry.ledgerId,editId=entry.id)},onRefund={controller.beginEditor(entry.ledgerId,refundParentId=entry.id)},onDelete={confirm=Confirmation("移入回收站？","原支出及当前有效退款会一起移入回收站，可在「我的」的回收站中恢复。") {controller.perform(Command.SoftDelete(entry.id)) {finishBack()}}},onMove={ledger,category,done->controller.perform(Command.MoveEntry(entry.id,ledger,category)) {done()}},onOpenEntry={openActivityId(ActivityFamily.ENTRY,it)})} ?: EmptyState("这笔账单已不存在","请返回查看最新流水")
                    "账本管理" -> LedgerManagement(s,controller,onConfirm={confirm=it})
                    "分类管理" -> CategoryManagement(s,selectedLedger,controller,onConfirm={confirm=it},onActivity=controller.finance::showActivities)
                    "回收站" -> FinanceRecyclePage(s,activities,busy,controller.environment::id,dispatch,command,{controller.finance.loadActivities(ActivityScope(deleted=true),append=true)},{controller.finance.loadActivities(ActivityScope(deleted=true),force=true)},{confirm=it})
                    "导入","导出","组件" -> FileAndWidgetPage(page,s,selectedLedger,homeScope,actions,command,controller.environment::id,{confirm=it},::navigatePage)
                    else -> Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text("此页面已不可用",style=MaterialTheme.typography.titleMedium)
                        TextButton(onClick=::finishBack) {Text("返回上一页")}
                    }
                }
                if(financeState.error!=null && page in listOf("账户","更多","我的","借入","借出","报销","存钱")) Surface(Modifier.fillMaxWidth().align(Alignment.TopCenter),color=MaterialTheme.colorScheme.errorContainer) {Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically) {Text(financeState.error.orEmpty(),Modifier.weight(1f));TextButton(onClick={controller.finance.loadSnapshot(financeLedgerIds,true)}) {Text("重试")}}}
                if(ui.busy && !ui.savingPreference) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
        if(ledgerPicker && s!=null) ChoiceDialog("选择账本",listOf(null to "全部账本（含归档）")+s.ledgers.sortedBy {it.sortOrder}.map {it.id to (it.name+if(it.archived) " · 已归档" else "")},selectedLedger,{id->setScope(scope.copy(ledgerIds=id?.let {setOf(it)} ?: emptySet(),categoryIds=emptySet()));ledgerPicker=false},{ledgerPicker=false})
        confirm?.let {confirmation->AlertDialog(onDismissRequest={confirm=null},title={Text(confirmation.title)},text={Text(confirmation.message)},confirmButton={TextButton(onClick={confirm=null;confirmation.action()},enabled=!busy) {Text("确认")}},dismissButton={TextButton(onClick={confirm=null}) {Text("取消")}})}
        pendingOperation?.let {recovery->AlertDialog(onDismissRequest={},title={Text(if(recovery.outcome!=null) "上次操作已提交" else "上次操作未确认提交")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {Text(if(recovery.outcome!=null) "本机已找到这次操作的提交记录，数据已经保存。请确认后查看明细或「我的」中的对应功能，不要重复录入。" else "本机未找到这次操作的提交记录。未保存的输入只保留在当前进程，请先核对明细，再决定是否重新操作。");Text("此提示会保留到你确认；确认不会删除已经保存的数据。",style=MaterialTheme.typography.bodySmall)}},confirmButton={TextButton(onClick={controller.acknowledgePendingOperation(recovery.operationId)},enabled=!ui.busy) {Text(if(ui.busy) "正在确认…" else "已了解，继续")}})}
    }}}
}

internal fun moreSummaries(snapshot:FinanceSnapshot?,s:LedgerState,ledger:String?,statistics:StatisticsSnapshot?,budgetScope:QueryScope):Map<String,String> {
    if(snapshot==null) return emptyMap()
    fun remaining(kind:ObligationKind)=snapshot.obligations.filter {it.obligation.kind==kind && !it.obligation.archived}.fold(0L) {sum,value->Money.add(sum,value.remainingMinor)}
    val monthly=statistics?.takeIf {it.scope==budgetScope && it.datasetId==s.datasetId && it.contentVersion==s.contentVersion}
    val month=budgetScope.startDate.take(7)
    val budget=s.budgets.find {it.ledgerId==ledger && it.yearMonth==month}
    return buildMap {
        put("借入",Money.format(remaining(ObligationKind.BORROW))+" 待还");put("借出",Money.format(remaining(ObligationKind.LEND))+" 待收");put("报销",Money.format(remaining(ObligationKind.REIMBURSEMENT))+" 待报")
        put("往来","与 ${s.contacts.count {!it.archived && (ledger==null || it.ledgerId==ledger)}} 人往来")
        put("周期","${s.recurringPlans.count {!it.archived && !it.paused && (it.totalOccurrences?.let {total->it.nextIndex<total} ?: true) && (ledger==null || it.ledgerId==ledger)}} 个进行中")
        put("存钱",Money.format(snapshot.savings.filter {!it.plan.archived}.fold(0L) {sum,item->Money.add(sum,item.savedMinor)})+" 已存")
        if(budget!=null && monthly!=null) put("预算","${month.take(4)}年${month.takeLast(2).toInt()}月 · "+Money.format(budget.amountMinor-monthly.budgetUsed)+" 剩余")
    }
}

internal data class Confirmation(val title:String,val message:String,val action:()->Unit)
@Composable internal fun EmptyState(title:String,body:String) { Column(Modifier.fillMaxWidth().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) { Text("◌",fontSize=48.sp,color=MaterialTheme.colorScheme.primary);Text(title,style=MaterialTheme.typography.titleMedium);Text(body,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable internal fun <T> ChoiceDialog(title:String,options:List<Pair<T,String>>,selected:T,onSelect:(T)->Unit,onDismiss:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={LazyColumn {items(options.size) {i->val (value,label)=options[i];Row(Modifier.fillMaxWidth().clickable {onSelect(value)}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {RadioButton(selected==value,onClick={onSelect(value)});Text(label,Modifier.weight(1f))}}}},confirmButton={TextButton(onClick=onDismiss) {Text("关闭")}})
}
@Composable internal fun SettingRow(title:String,subtitle:String,enabled:Boolean=true,onClick:()->Unit) {Card(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth()) {Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(title,fontWeight=FontWeight.Medium);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text("›",Modifier.padding(start=12.dp),fontSize=24.sp)}}}
@Composable internal fun PasswordDialog(export:Boolean,onDismiss:()->Unit,onConfirm:(String)->Unit) {
    var password by remember {mutableStateOf("")};var repeat by remember {mutableStateOf("")};var error by remember {mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(export) "设置备份口令" else "选择备份恢复")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(if(export) "使用 12–128 个字符。口令遗失无法恢复。" else "JSON 明文备份无需口令；加密备份请填写原口令。")
        OutlinedTextField(password,{password=it;error=null},label={Text("备份口令")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),singleLine=true)
        if(export) OutlinedTextField(repeat,{repeat=it;error=null},label={Text("再次输入口令")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),singleLine=true)
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={if(export && (codePointCount(password) !in 12..128 || password!=repeat)) error="口令长度不符或两次输入不一致" else onConfirm(password)}) {Text(if(export) "导出" else "选择文件")}},dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})
}
