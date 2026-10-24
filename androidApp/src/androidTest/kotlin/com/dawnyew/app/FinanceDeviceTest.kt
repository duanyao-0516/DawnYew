package com.dawnyew.app

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawnyew.core.*
import com.dawnyew.platform.files.JsonStreamCodec
import com.dawnyew.platform.storage.GenerationRepository
import com.dawnyew.presentation.*
import com.dawnyew.protocol.BackupProtocol
import com.dawnyew.ui.DawnYewApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class FinanceDeviceTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val env=object:DomainEnvironment {
        override fun id()=UUID.randomUUID().toString()
        override fun localNow()="2026-10-24T12:00:00"
        override fun utcNow()="2026-10-24T04:00:00Z"
        override fun normalize(value:String)=normalizeTextNfc(value)
    }
    private fun isolated():Pair<Context,File> {
        val dir=File(context.cacheDir,"finance-test-${env.id()}").apply {mkdirs()}
        return object:ContextWrapper(context) {override fun getNoBackupFilesDir()=dir} to dir
    }
    private fun sample():LedgerState {
        val engine=DomainEngine(env);var s=engine.initialState();val ledger=s.ledgers.single().id
        fun command(c:Command) {s=engine.apply(s,c,env.id()).state}
        fun action(a:FinanceAction)=command(Command.Finance(a))
        val cash=env.id();val bank=env.id();val time="2026-10-24T10:00:00"
        action(FinanceAction.SaveAccount(Account(cash,ledger,"UI测试·现金",openingBalanceMinor=100000,openingAt="2026-01-01T00:00:00")))
        action(FinanceAction.SaveAccount(Account(bank,ledger,"UI测试·储蓄",AccountType.BANK,openingAt="2026-01-01T00:00:00")))
        fun entry(amount:Long,kind:EntryKind=EntryKind.EXPENSE,recorded:Boolean=true) {
            command(Command.SaveEntry(ledgerId=ledger,kind=kind,amountMinor=amount,
                categoryId=s.categories.first {it.direction==if(kind==EntryKind.INCOME)Direction.INCOME else Direction.EXPENSE}.id,
                occurrence=time,note="UI测试",details=EntryDetails("",cash,recorded)))
        }
        entry(1234);entry(10000,EntryKind.INCOME)
        action(FinanceAction.SaveRecurringPlan(RecurringPlan(env.id(),ledger,"UI测试·月度订阅",ActivityKind.EXPENSE,300,time,RecurringFrequency.MONTHLY,totalOccurrences=1,categoryId=s.categories.first {it.direction==Direction.EXPENSE}.id,accountId=cash)))
        fun obligation(kind:ObligationKind,amount:Long):String {val id=env.id();action(FinanceAction.SaveObligation(Obligation(id,ledger,kind,"UI测试·${kind.name}",env.id(),time),initialPrincipal=ObligationPrincipal(cash,amount,time)));return id}
        val borrow=obligation(ObligationKind.BORROW,1000)
        action(FinanceAction.RecordObligation(borrow,cash,200,time,settlement=true))
        obligation(ObligationKind.LEND,600);obligation(ObligationKind.REIMBURSEMENT,400)
        action(FinanceAction.SaveTransfer(AccountTransfer(env.id(),ledger,cash,bank,2000,time,createdAt=env.utcNow(),updatedAt=env.utcNow())))
        entry(100,recorded=false);command(Command.SetBudget(ledger,"2026-10",30000))
        val plan=SavingsPlan(env.id(),ledger,"UI测试·旅行基金",SavingsScenario.GOAL,targetMinor=10000)
        action(FinanceAction.SaveSavingsPlan(plan));action(FinanceAction.SaveSavingsMovement(SavingsMovement(env.id(),plan.id,SavingsMovementKind.DEPOSIT,500,time)))
        return s
    }
    private suspend fun verify(repo:GenerationRepository) {
        val s=repo.snapshot();val f=repo.financeSnapshot();val stats=repo.statistics(QueryScope())
        assertEquals(106266L,f.accounts.single {it.account.name=="UI测试·现金"}.balanceMinor)
        assertEquals(108466L,f.netWorthMinor);assertEquals(500L,f.savings.single().savedMinor)
        assertEquals(1634L,stats.expense);assertEquals(28366L,s.budgets.single().amountMinor-stats.budgetUsed)
        val query=ActivityScope(recorded=false)
        assertEquals(1L,repo.activitySummary(query).activityCount)
        assertEquals(100L,repo.queryActivityPage(query).activities.single().amountMinor)
        assertEquals(s.entries.count {it.active}+s.transfers.count {it.active},repo.activitySummary(ActivityScope()).activityCount.toInt())
    }
    @Test fun financialGraphSurvivesRealDatabaseReopenAndProductionBackupRestore()=runBlocking {
        val (isolated,dir)=isolated();var repo=GenerationRepository(isolated,env)
        try {
            repo.initialize();repo.replace(sample());verify(repo)
            repo.close();repo=GenerationRepository(isolated,env);repo.initialize();verify(repo)
            val bytes=ByteArrayOutputStream();repo.exportBackup(bytes,env.utcNow()) {}
            val before=repo.snapshot();val baseline=repo.versionToken()
            val candidate=JsonStreamCodec.prepare(bytes.toByteArray().inputStream(),File(dir,"restore")).use {repo.prepareRestore(it,baseline)}
            try {repo.replaceCandidate(candidate)} finally {repo.discardCandidate(candidate)}
            verify(repo);assertEquals(before.entryDetails.associateBy {it.entryId},repo.snapshot().entryDetails.associateBy {it.entryId})
            val entry=repo.snapshot().entries.first {it.kind==EntryKind.EXPENSE}
            var operation=env.id();repo.execute(Command.SoftDelete(entry.id),operation);repo.acknowledgeOperation(operation)
            assertEquals(1,repo.queryActivityPage(ActivityScope(deleted=true)).activities.size)
            val deleted=repo.snapshot().entries.first {it.id==entry.id}
            operation=env.id();repo.execute(Command.RestoreGroup(deleted.deletionGroupId!!),operation);repo.acknowledgeOperation(operation);verify(repo)
        } finally {repo.close();dir.deleteRecursively()}
    }
    @Test fun legacyNineEntityBackupRestoresIntoCurrentProductionGeneration()=runBlocking {
        val (isolated,dir)=isolated();val repo=GenerationRepository(isolated,env)
        try {
            repo.initialize();val base=DomainEngine(env).initialState();val current=BackupProtocol.create(base,env.utcNow());val types=BackupProtocol.entityTypes(1).toSet()
            val legacy=current.copy(formatVersion=1,records=current.records.filterKeys {it in types},counts=current.counts.filterKeys {it in types},sha256="")
            val document=legacy.copy(sha256=BackupProtocol.digest(legacy));val output=ByteArrayOutputStream();JsonStreamCodec.write(document,output)
            val candidate=JsonStreamCodec.prepare(output.toByteArray().inputStream(),File(dir,"legacy")).use {prepared->
                assertEquals(9,prepared.metadata.counts.size);assertEquals(23,prepared.expectedCandidateCounts.size)
                repo.prepareRestore(prepared,repo.versionToken())
            }
            try {repo.replaceCandidate(candidate)} finally {repo.discardCandidate(candidate)}
            assertEquals(base.ledgers,repo.snapshot().ledgers);assertTrue(repo.snapshot().accounts.isEmpty())
        } finally {repo.close();dir.deleteRecursively()}
    }
    @Test fun financialScreensRenderWithSyntheticOfflineDataAndSecureFlagIntact()=runBlocking {
        // Only run this rendering fixture in the isolated validation package.
        assertTrue(context.packageName.endsWith(".validation"))
        val (isolated,dir)=isolated();val repo=GenerationRepository(isolated,env)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        try {
            repo.initialize();repo.replace(sample().copy(preferences=listOf(UserPreference("theme","light"))));val app=AppController(repo,env,scope)
            val instrument=InstrumentationRegistry.getInstrumentation()
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                scenario.onActivity {activity->activity.setContent {DawnYewApp(app,PreviewActions,false,utilities=PreviewTools)}}
                fun settle(predicate:()->Boolean) {
                    val start=SystemClock.elapsedRealtime();var ready=false
                    while(!ready && SystemClock.elapsedRealtime()-start<10_000) {
                        instrument.waitForIdleSync();instrument.runOnMainSync {ready=predicate()};if(!ready)SystemClock.sleep(25)
                    }
                    assertTrue(ready,"UI data did not settle")
                    instrument.waitForIdleSync();SystemClock.sleep(350);instrument.waitForIdleSync()
                }
                val output=File(context.getExternalFilesDir(null),"correction-ui").apply {deleteRecursively();mkdirs()}
                fun capture(name:String) {
                    scenario.onActivity {activity->
                        assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE!=0)
                        val view=activity.window.decorView;assertTrue(view.width>0 && view.height>0)
                        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                        val canvas=Canvas(bitmap);view.draw(canvas)
                        // Dialogs own separate secure windows; draw only this synthetic fixture's process views.
                        val origin=IntArray(2);view.getLocationOnScreen(origin)
                        val dialogs=android.view.inspector.WindowInspector.getGlobalWindowViews().filter {it!==view && it.isShown && it.width>0 && it.height>0}
                        if(name in listOf("06-账户详情","08-存钱详情")) assertTrue(dialogs.isEmpty(),"Details must be full secondary pages")
                        dialogs.forEach {dialog->
                            val position=IntArray(2);dialog.getLocationOnScreen(position)
                            canvas.save();canvas.translate((position[0]-origin[0]).toFloat(),(position[1]-origin[1]).toFloat());dialog.draw(canvas);canvas.restore()
                        }
                        File(output,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                    }
                }
                settle {app.ui.value.dataset!=null && app.finance.snapshot.value.snapshot!=null && app.homeStatistics.value.snapshot!=null}
                listOf("首页","账户","统计","更多","我的").forEachIndexed {index,tab->
                    instrument.runOnMainSync {app.finance.selectTab(tab)}
                    settle {when(tab){"首页"->!app.homeStatistics.value.loading && !app.finance.calendar.value.loading;"统计"->PanelCatalog.definitions(PanelSurface.STATISTICS).filter {it.defaultEnabled}.all {app.dashboard.panels.value[it.id]?.let {state->!state.loading && state.snapshot!=null}==true};"我的"->app.finance.profile.value.summary!=null;"账户"->!app.finance.snapshot.value.loading && !app.dashboard.history.value.loading && app.dashboard.history.value.snapshot!=null;else->!app.finance.snapshot.value.loading}}
                    capture("0${index+1}-$tab")
                }
                val state=repo.snapshot()
                listOf(Triple("账户",state.accounts.first().id,"06-账户详情"),Triple("周期",null,"07-周期"),Triple("存钱",state.savingsPlans.single().id,"08-存钱详情"),Triple("预算",null,"09-预算"),Triple("存钱",null,"14-存钱列表"),Triple("主页面板",null,"15-主页面板"),Triple("统计面板",null,"16-统计面板"),Triple("借入",state.obligations.first {it.kind==ObligationKind.BORROW}.id,"17-借入详情"),Triple("周期",state.recurringPlans.single().id,"18-周期详情")).forEach {(page,id,name)->
                    instrument.runOnMainSync {app.finance.open(page,id)};settle {app.navigation.value.page==page && (page!="账户" || (!app.dashboard.history.value.loading && app.dashboard.history.value.snapshot!=null && app.dashboard.history.value.error==null))};capture(name)
                }
                instrument.runOnMainSync {app.beginEditor(state.ledgers.single().id,editId=state.entries.first().id)}
                settle {app.editor.value!=null};capture("10-账单表单")
                val draftId=app.editor.value!!.operationId
                instrument.runOnMainSync {assertEquals(draftId,app.editor.value?.operationId);app.clearEditor();app.finance.showActivities(ActivityScope(ledgerIds=setOf(state.ledgers.single().id)))}
                settle {!app.finance.activities.value.loading && app.finance.activitySummary.value.summary!=null};capture("11-流水")
                instrument.runOnMainSync {app.finance.selectTab("首页");app.perform(Command.SetPreference("theme","dark"))}
                settle {app.ui.value.dataset?.preferences?.any {it.key=="theme" && it.value=="dark"}==true && !app.ui.value.busy};capture("13-深色首页")
                instrument.runOnMainSync {app.finance.selectTab("我的")}
                settle {app.navigation.value.page=="我的" && app.finance.profile.value.summary!=null};capture("19-深色我的")
                instrument.runOnMainSync {app.perform(Command.SetPreference("theme","light"))}
                settle {app.ui.value.dataset?.preferences?.any {it.key=="theme" && it.value=="light"}==true && !app.ui.value.busy}
                val project=Dimension(env.id(),state.ledgers.single().id,DimensionKind.PROJECT,"UI测试·日常项目")
                val operation=env.id();repo.execute(Command.Finance(FinanceAction.SaveDimension(project)),operation);repo.acknowledgeOperation(operation)
                settle {app.ui.value.dataset?.dimensions?.any {it.id==project.id}==true}
                listOf("分类管理" to "20-分类管理","账本管理" to "21-账本管理","项目" to "22-项目").forEach {(page,name)->
                    instrument.runOnMainSync {app.finance.open(page)};settle {app.navigation.value.page==page};capture(name)
                }
            }
        } finally {scope.cancel();repo.close();dir.deleteRecursively()}
    }
    private class DeviceUi(
        private val instrument:android.app.Instrumentation,
        private val observedApp:AppController,
        private val report:File,
    ) {
        fun settle(predicate:()->Boolean) {
            val end=SystemClock.elapsedRealtime()+15_000;var ready=false
            while(!ready && SystemClock.elapsedRealtime()<end) {
                instrument.waitForIdleSync();instrument.runOnMainSync {ready=predicate()}
                if(!ready)SystemClock.sleep(30)
            }
            assertTrue(ready,"UI did not settle: page=${observedApp.navigation?.value?.page}; ui=${observedApp.ui?.value?.let {"busy=${it.busy},message=${it.message},version=${it.dataset?.contentVersion}"}}; categories=${observedApp.ui?.value?.dataset?.categories?.sortedBy {it.sortOrder}?.map {"${it.ledgerId}/${it.name}/${it.sortOrder}/${it.enabled}"}}; errors=${observedApp.dashboard?.panels?.value?.filterValues {it.error!=null}}")
            instrument.waitForIdleSync();SystemClock.sleep(180)
        }
        fun nodes():List<android.view.accessibility.AccessibilityNodeInfo> {
            if(android.os.Build.VERSION.SDK_INT>=33) instrument.uiAutomation.clearCache()
            val root=instrument.uiAutomation.rootInActiveWindow ?: return emptyList()
            val result=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            fun visit(node:android.view.accessibility.AccessibilityNodeInfo) {
                result+=node
                repeat(node.childCount) {node.getChild(it)?.let(::visit)}
            }
            visit(root);return result
        }
        fun click(label:String,scroll:Boolean=true) {
            fun matches(n:android.view.accessibility.AccessibilityNodeInfo)=
                (n.text?.toString()?.split('\n').orEmpty()+n.contentDescription?.toString()?.split('\n').orEmpty()).any {it==label}
            var scrollForward=true
            repeat(if(scroll) 30 else 20) {
                instrument.waitForIdleSync()
                val all=nodes()
                for(node in all.filter {matches(it)}) {
                    var target:android.view.accessibility.AccessibilityNodeInfo?=node
                    while(target!=null && !target.isClickable) target=target.parent
                    // A stale route title can have the same text as its former navigation row.
                    // Only tap a node that actually exposes a click action.
                    if(target==null && node.actionList.none {it.id==android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK} && node.contentDescription?.toString()!=label) continue
                    val bounds=android.graphics.Rect()
                    (target ?: node).getBoundsInScreen(bounds)
                    report.appendText("MATCH $label bounds=$bounds clickable=${node.isClickable} actions=${node.actionList.map {it.id}}\n")
                    if(bounds.width()<=0 || bounds.height()<=0) continue
                    val accepted=target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true
                    if(!accepted) {
                        // Some OEM accessibility bridges expose Compose click actions but reject ACTION_CLICK.
                        // Inject the same physical tap at the freshly read node, then assert the resulting state.
                        val time=SystemClock.uptimeMillis()
                        listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP).forEachIndexed {index,action->
                            val event=android.view.MotionEvent.obtain(time,time+index*60,action,bounds.exactCenterX(),bounds.exactCenterY(),0)
                            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                            assertTrue(instrument.uiAutomation.injectInputEvent(event,true));event.recycle()
                        }
                    }
                    instrument.waitForIdleSync();SystemClock.sleep(200)
                    report.appendText("CLICK $label via ${if(accepted) "accessibility" else "physical tap"}\n");return
                }
                if(scroll) all.firstOrNull {it.isScrollable && it.isVisibleToUser}?.let {list->
                    val moved=list.performAction(if(scrollForward) android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    if(!moved)scrollForward=!scrollForward
                }
                SystemClock.sleep(if(scroll) 400 else 120)
            }
            error("Clickable target missing: $label; visible=${nodes().mapNotNull {it.text?.toString()?:it.contentDescription?.toString()}}")
        }
        fun boundsFor(label:String):android.graphics.Rect {
            repeat(30) {
                instrument.waitForIdleSync()
                val node=nodes().firstOrNull {(it.text?.toString()?.split('\n').orEmpty()+it.contentDescription?.toString()?.split('\n').orEmpty()).contains(label)}
                if(node!=null) {
                    val result=android.graphics.Rect().also(node::getBoundsInScreen)
                    if(result.width()>0 && result.height()>0)return result
                }
                SystemClock.sleep(100)
            }
            error("Node missing: $label; visible=${nodes().mapNotNull {it.text?.toString()?:it.contentDescription?.toString()}}")
        }
        fun drag(source:String,target:String,down:Boolean,cancel:Boolean=false) {
            settle {observedApp.ui?.value?.busy==false && observedApp.pendingOperation?.value==null}
            report.appendText("DRAG_STATE page=${observedApp.navigation?.value?.page} busy=${observedApp.ui?.value?.busy} version=${observedApp.ui?.value?.dataset?.contentVersion}\n")
            val from=boundsFor(source);val to=boundsFor(target)
            report.appendText("DRAG_BOUNDS $source=$from $target=$to\n")
            val x=from.left+from.width()*.30f;val start=from.exactCenterY()
            val end=if(down) to.bottom-12f else to.top+12f
            val downAt=SystemClock.uptimeMillis()
            fun inject(action:Int,y:Float) {
                val event=android.view.MotionEvent.obtain(downAt,SystemClock.uptimeMillis(),action,x,y,0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrument.uiAutomation.injectInputEvent(event,true));event.recycle()
            }
            inject(android.view.MotionEvent.ACTION_DOWN,start)
            SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout()+150L)
            repeat(18) {step->inject(android.view.MotionEvent.ACTION_MOVE,start+(end-start)*(step+1)/18);SystemClock.sleep(35)}
            inject(if(cancel) android.view.MotionEvent.ACTION_CANCEL else android.view.MotionEvent.ACTION_UP,end)
            instrument.waitForIdleSync();SystemClock.sleep(450)
            report.appendText("${if(cancel) "CANCEL_DRAG" else "LONG_DRAG"} $source -> $target\n")
        }
    }

    @Test fun panelSwitchesAndSecondaryRoutesRespondToRealAccessibilityClicks()=runBlocking {
        assertTrue(context.packageName.endsWith(".validation"))
        val (isolated,dir)=isolated();val repo=GenerationRepository(isolated,env)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val report=File(context.getExternalFilesDir(null),"correction-interactions.txt")
        report.writeText("")
        try {
            repo.initialize();repo.replace(sample().copy(preferences=listOf(UserPreference("theme","light"))));val app=AppController(repo,env,scope)
            val driver=DeviceUi(instrument,app,report)
            fun settle(predicate:()->Boolean)=driver.settle(predicate)
            fun nodes()=driver.nodes()
            fun click(label:String,scroll:Boolean=true)=driver.click(label,scroll)
            fun boundsFor(label:String)=driver.boundsFor(label)
            fun drag(source:String,target:String,down:Boolean,cancel:Boolean=false)=driver.drag(source,target,down,cancel)
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                var fixtureActivity:MainActivity?=null
                scenario.onActivity {fixtureActivity=it;it.setContent {DawnYewApp(app,PreviewActions,false,utilities=PreviewTools)}}
                settle {fixtureActivity?.hasWindowFocus()==true && app.ui.value.dataset!=null && !app.finance.snapshot.value.loading}
                val managementOnly=InstrumentationRegistry.getArguments().getString("dawnyewManagementOnly")=="true"
                click("我的",false);settle {app.navigation.value.page=="我的"}
                if(!managementOnly) {
                val profile=nodes().mapNotNull {it.text?.toString()?:it.contentDescription?.toString()}.joinToString("\n")
                listOf("登录","会员","AI","消息").forEach {assertFalse(profile.contains(it),"Online entry remains: $it")}
                click("更多",false);click("主页面板");settle {app.navigation.value.page=="主页面板"}
                val first=PanelCatalog.definitions(PanelSurface.HOME).first()
                val before=PanelCatalog.resolve(repo.snapshot().preferences,PanelSurface.HOME)
                click("开关${first.title}")
                settle {app.ui.value.dataset?.let {first.id !in PanelCatalog.resolve(it.preferences,PanelSurface.HOME).enabled}==true && !app.ui.value.busy}
                val second=PanelCatalog.definitions(PanelSurface.HOME)[1]
                val beforeCancel=repo.snapshot()
                drag("拖动排序${first.title}","拖动排序${second.title}",true,cancel=true)
                assertEquals(beforeCancel.contentVersion,repo.snapshot().contentVersion,"Cancelled drag wrote a preference")
                assertEquals(PanelCatalog.resolve(beforeCancel.preferences,PanelSurface.HOME),PanelCatalog.resolve(repo.snapshot().preferences,PanelSurface.HOME))
                drag("拖动排序${first.title}","拖动排序${second.title}",true)
                settle {app.ui.value.dataset?.let {PanelCatalog.resolve(it.preferences,PanelSurface.HOME).order[1]==first.id}==true && !app.ui.value.busy}
                click("返回",false);click("主页面板")
                assertFalse(PanelCatalog.resolve(repo.snapshot().preferences,PanelSurface.HOME).enabled.contains(first.id))
                click("开关${first.title}")
                settle {app.ui.value.dataset?.let {first.id in PanelCatalog.resolve(it.preferences,PanelSurface.HOME).enabled}==true && !app.ui.value.busy}
                // Returning to the home tab consumes the same committed order immediately.
                click("返回",false);click("首页",false)
                settle {app.navigation.value.page=="首页" && !app.homeStatistics.value.loading}
                assertTrue(boundsFor("最近交易").top < boundsFor("10月支出 ▾").top,"Home ignored committed panel order")
                report.appendText("PASS homepage immediately follows reordered panel layout\n")
                click("更多",false);click("主页面板")
                drag("拖动排序${first.title}","拖动排序${second.title}",false)
                settle {app.ui.value.dataset?.let {PanelCatalog.resolve(it.preferences,PanelSurface.HOME)==before}==true && !app.ui.value.busy}
                click("返回",false);click("统计面板")
                val definitions=PanelCatalog.definitions(PanelSurface.STATISTICS)
                definitions.filterNot {it.defaultEnabled}.forEach {definition->
                    click("开关${definition.title}")
                    settle {app.ui.value.dataset?.let {definition.id in PanelCatalog.resolve(it.preferences,PanelSurface.STATISTICS).enabled}==true && !app.ui.value.busy}
                }
                click("返回",false);click("统计",false)
                settle {app.navigation.value.page=="统计"}
                for(definition in definitions) {
                    click(definition.title)
                    settle {app.navigation.value.page==definition.title && app.dashboard.panels.value[definition.id]?.let {panel->
                        val dataset=app.ui.value.dataset
                        !panel.loading && panel.error==null && panel.snapshot?.let {it.request==panel.request && it.datasetId==dataset?.datasetId && it.contentVersion==dataset.contentVersion}==true
                    }==true}
                    assertFalse(nodes().any {it.text?.toString()=="此页面已不可用"},definition.id)
                    report.appendText("DETAIL ${definition.id}\n")
                    click("返回",false);settle {app.navigation.value.page=="统计"}
                }
                click("更多",false)
                listOf("借入","借出","报销","往来","周期","预算","存钱","工具箱").forEach {title->
                    click(title);settle {app.navigation.value.page==title}
                    assertFalse(nodes().any {it.text?.toString()=="此页面已不可用"},title)
                    report.appendText("PAGE $title\n")
                    click("返回",false);settle {app.navigation.value.page=="更多"}
                }
                click("账户",false);click("UI测试·现金")
                settle {app.finance.navigation.value.id!=null && !app.dashboard.history.value.loading}
                assertNotNull(app.dashboard.history.value.snapshot)
                assertFalse(android.view.inspector.WindowInspector.getGlobalWindowViews().count {it.isShown}>1,"Account detail still a dialog")
                click("返回",false);settle {app.finance.navigation.value.id==null}
                click("我的",false)
                listOf("分类" to "分类管理","项目" to "项目","商家" to "商家","标签" to "标签","导入" to "导入","导出" to "导出","账本" to "账本管理","回收站" to "回收站","自动" to "自动","组件" to "组件","备份" to "备份","货币" to "货币","安全" to "安全","提醒" to "提醒","工具箱" to "工具箱","设置" to "设置","手册" to "手册").forEach { (label,page)->
                    click(label);settle {app.navigation.value.page==page}
                    assertFalse(nodes().any {it.text?.toString()=="此页面已不可用"},page)
                    report.appendText("PROFILE_PAGE $page\n")
                    click("返回",false);settle {app.navigation.value.page=="我的"}
                }
                }
                click("设置",false);settle {app.navigation.value.page=="设置"}
                repeat(3) {nodes().firstOrNull {it.isScrollable && it.isVisibleToUser}?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);SystemClock.sleep(400)}
                val beforeSettingsReturn=boundsFor("备份")
                click("备份");settle {app.navigation.value.page=="备份"}
                click("返回",false);settle {app.navigation.value.page=="设置"}
                val afterSettingsReturn=boundsFor("备份")
                assertTrue(kotlin.math.abs(beforeSettingsReturn.top-afterSettingsReturn.top)<=3,"Settings jumped on return: $beforeSettingsReturn -> $afterSettingsReturn")
                report.appendText("PASS settings scroll retained at y=${afterSettingsReturn.top} after detail return\n")
                click("返回",false);settle {app.navigation.value.page=="我的"}
                // Fixture setup uses production commands; all reordering below is an actual long press gesture.
                val ledger=repo.snapshot().ledgers.single().id
                suspend fun fixture(command:Command) {
                    val operation=env.id();repo.execute(command,operation);repo.acknowledgeOperation(operation)
                    settle {!app.ui.value.busy && app.ui.value.dataset?.contentVersion==repo.state.value?.contentVersion}
                }
                fixture(Command.CreateLedger("UI测试账本二"))
                click("账本")
                val ledgerOrder=repo.snapshot().ledgers.sortedWith(compareBy<Ledger> {it.sortOrder}.thenBy {it.id})
                drag("拖动排序${ledgerOrder[0].name}","拖动排序${ledgerOrder[1].name}",true)
                settle {app.ui.value.dataset?.ledgers?.minByOrNull {it.sortOrder}?.id==ledgerOrder[1].id}
                click("返回",false);click("分类")
                val categories=repo.snapshot().categories.filter {it.ledgerId==ledger && it.direction==Direction.EXPENSE}.sortedWith(compareBy<Category> {it.sortOrder}.thenBy {it.id})
                drag("拖动排序${categories[0].name}","拖动排序${categories[1].name}",true)
                settle {app.ui.value.dataset?.categories?.filter {it.ledgerId==ledger && it.direction==Direction.EXPENSE}?.minByOrNull {it.sortOrder}?.id==categories[1].id}
                click("启用${categories[0].name}")
                settle {!app.ui.value.busy && app.ui.value.dataset?.categories?.find {it.id==categories[0].id}?.enabled==false}
                click("启用${categories[0].name}")
                settle {!app.ui.value.busy && app.ui.value.dataset?.categories?.find {it.id==categories[0].id}?.enabled==true}
                click("管理分类${categories[0].name}")
                click("重命名",false);boundsFor("重命名分类")
                click("取消",false)
                click(categories[0].name)
                settle {app.navigation.value.page=="流水" && app.finance.navigation.value.activityScope?.categoryIds==setOf(categories[0].id) && !app.finance.activities.value.loading}
                click("返回",false);settle {app.navigation.value.page=="分类管理"}
                report.appendText("PASS shared category row toggle, rename menu and activity drill after dragging\n")
                click("返回",false)
                for(kind in DimensionKind.entries) {
                    val first=Dimension(env.id(),ledger,kind,"UI维度一")
                    val second=Dimension(env.id(),ledger,kind,"UI维度二",sortOrder=1)
                    fixture(Command.Finance(FinanceAction.SaveDimension(first)))
                    fixture(Command.Finance(FinanceAction.SaveDimension(second)))
                    click(when(kind){DimensionKind.PROJECT->"项目";DimensionKind.MERCHANT->"商家";DimensionKind.TAG->"标签"})
                    drag("拖动排序${first.name}","拖动排序${second.name}",true)
                    settle {app.ui.value.dataset?.dimensions?.filter {it.ledgerId==ledger && it.kind==kind}?.minByOrNull {it.sortOrder}?.id==second.id}
                    click("返回",false)
                }
                val newCash=Account(env.id(),ledger,"UI测试·零钱",openingAt="2026-01-01T00:00:00",sortOrder=1)
                fixture(Command.Finance(FinanceAction.SaveAccount(newCash)))
                click("账户",false)
                settle {!app.finance.snapshot.value.loading && app.finance.snapshot.value.contentVersion==app.ui.value.dataset?.contentVersion}
                drag("拖动排序UI测试·现金","拖动排序UI测试·零钱",true)
                settle {app.ui.value.dataset?.accounts?.filter {it.ledgerId==ledger && it.type==AccountType.CASH}?.minByOrNull {it.sortOrder}?.id==newCash.id}
                report.appendText("PASS real ledger/category/project/merchant/tag/account drag gestures\n")
                val output=ByteArrayOutputStream();repo.exportBackup(output,env.utcNow()) {}
                val beforeRestore=PanelCatalog.resolve(repo.snapshot().preferences,PanelSurface.STATISTICS)
                val candidate=JsonStreamCodec.prepare(output.toByteArray().inputStream(),File(dir,"panels-restore")).use {repo.prepareRestore(it,repo.versionToken())}
                try {repo.replaceCandidate(candidate)} finally {repo.discardCandidate(candidate)}
                assertEquals(beforeRestore,PanelCatalog.resolve(repo.snapshot().preferences,PanelSurface.STATISTICS))
                report.appendText("PASS layouts persisted through production backup restore\n")
            }
        } finally {scope.cancel();repo.close();dir.deleteRecursively()}
    }

    @Test fun singlePageEntrySavesAmountCategoryAndTagsWithoutScrolling()=runBlocking {
        assertTrue(context.packageName.endsWith(".validation"))
        val (isolated,dir)=isolated();val repo=GenerationRepository(isolated,env)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val output=File(context.getExternalFilesDir(null),"entry-composer").apply {deleteRecursively();mkdirs()}
        val report=File(output,"interactions.txt")
        try {
            repo.initialize();val initial=sample().copy(preferences=listOf(UserPreference("theme","light")));repo.replace(initial)
            val ledger=initial.ledgers.single().id
            val tag=Dimension(env.id(),ledger,DimensionKind.TAG,"UI测试标签")
            val operation=env.id();repo.execute(Command.Finance(FinanceAction.SaveDimension(tag)),operation);repo.acknowledgeOperation(operation)
            val app=AppController(repo,env,scope);val ui=DeviceUi(instrument,app,report)
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                var activity:MainActivity?=null
                scenario.onActivity {activity=it;it.setContent {DawnYewApp(app,PreviewActions,false,utilities=PreviewTools)}}
                ui.settle {activity?.hasWindowFocus()==true && app.ui.value.dataset?.dimensions?.any {it.id==tag.id}==true}
                fun capture(name:String) {scenario.onActivity {host->
                    val view=host.window.decorView
                    assertTrue(host.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE!=0)
                    val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                    File(output,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                }}
                ui.click("记一笔",false)
                ui.settle {app.navigation.value.page=="记一笔" && app.editor.value!=null}
                assertFalse(ui.nodes().any {it.text?.toString()=="下一步"},"Amount-only front page remains")
                listOf("金额输入","分类购物","标签${tag.name}","保存账单").forEach {label->
                    val bounds=ui.boundsFor(label)
                    assertTrue(bounds.top>=0 && bounds.bottom<=activity!!.window.decorView.height,"Entry control outside first screen: $label=$bounds")
                }
                capture("01-amount-category-tag-same-screen")
                listOf("1","7").forEach {ui.click("金额键$it",false);report.appendText("DRAFT_AFTER_KEY $it amount=${app.editor.value?.amount}\n")}
                assertEquals("17",app.editor.value?.amount,"A second digit must append instead of replacing the first")
                ui.click("金额键⌫",false);assertEquals("1",app.editor.value?.amount,"Backspace removes only the final digit")
                listOf("2",".","3","4").forEach {ui.click("金额键$it",false);report.appendText("DRAFT_AFTER_KEY $it amount=${app.editor.value?.amount}\n")}
                ui.click("分类购物",false);ui.click("标签${tag.name}",false)
                val category=initial.categories.single {it.ledgerId==ledger && it.direction==Direction.EXPENSE && it.name=="购物"}
                report.appendText("DRAFT_BEFORE_SAVE amount=${app.editor.value?.amount} category=${app.editor.value?.categoryId} expected=${category.id} tags=${app.editor.value?.tagIds}\n")
                capture("02-ready-to-save-without-scroll")
                ui.settle {app.editor.value?.let {it.amount=="12.34" && it.categoryId==category.id && it.tagIds==setOf(tag.id)}==true}
                capture("02-ready-to-save-without-scroll")
                val before=repo.snapshot().entries.map {it.id}.toSet()
                ui.click("保存账单",false)
                ui.settle {app.navigation.value.page=="首页" && !app.ui.value.busy && app.editor.value==null}
                val created=repo.snapshot().entries.single {it.id !in before}
                assertEquals(1234L,created.amountMinor);assertEquals(category.id,created.categoryId)
                assertEquals(setOf(tag.id),repo.snapshot().entryTags.filter {it.entryId==created.id}.map {it.tagId}.toSet())
                ui.click("记一笔",false);ui.settle {app.navigation.value.page=="记一笔" && app.editor.value!=null}
                val draftId=app.editor.value!!.operationId
                listOf("3","+","4").forEach {ui.click("金额键$it",false)}
                ui.click("标签${tag.name}",false)
                ui.click("保存并继续",false)
                ui.settle {app.navigation.value.page=="记一笔" && !app.ui.value.busy && app.editor.value?.operationId!=draftId && app.editor.value?.amount==""}
                assertTrue(repo.snapshot().entries.any {it.id !in before && it.id!=created.id && it.amountMinor==700L})
                assertEquals(setOf(tag.id),app.editor.value?.tagIds)
                assertFalse(ui.nodes().any {it.text?.toString() in setOf("已保存","已保存，可以继续记账")},"Success snackbar obscures the next entry's save controls")
                capture("03-continue-keeps-selection-clears-amount")
                ui.click("备注输入",false)
                ui.settle {activity!!.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true}
                val ime=activity!!.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                assertTrue(ui.boundsFor("保存账单").bottom<=activity!!.window.decorView.height-ime,"Save is obscured by system keyboard")
                assertFalse(ui.nodes().any {it.contentDescription?.toString()=="金额键1"},"Custom and system keyboards overlap")
                assertEquals(1, android.view.inspector.WindowInspector.getGlobalWindowViews().count { it.isShown }, "Note must be inline, not a dialog")
                val noteField = ui.nodes().first { it.contentDescription?.toString() == "备注输入" }
                val multiline = "UI测试第一行\n第二行\n第三行\n第四行\n第五行"
                assertTrue(noteField.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, multiline)
                }))
                ui.settle { app.editor.value?.note == multiline }
                val noteBounds = ui.boundsFor("备注输入")
                assertTrue(noteBounds.height() <= 90 * context.resources.displayMetrics.density, "Inline note expanded beyond three text lines: $noteBounds")
                report.appendText("PASS inline 5-line note retains text within three-line viewport height=${noteBounds.height()}px; CJK supplied via SET_TEXT (not IME composition proof)\n")
                var connection: android.view.inputmethod.InputConnection? = null
                scenario.onActivity { host ->
                    connection = host.window.decorView.findFocus()?.onCreateInputConnection(android.view.inputmethod.EditorInfo())
                    assertNotNull(connection, "Focused note must expose the real IME InputConnection")
                    connection!!.setSelection(0, multiline.length)
                    connection!!.setComposingText("zhong", 1)
                }
                ui.settle { app.editor.value?.note == "zhong" }
                scenario.onActivity { connection!!.setComposingText("中文", 1) }
                ui.settle { app.editor.value?.note == "中文" }
                scenario.onActivity { connection!!.commitText("中文备注", 1); connection!!.finishComposingText() }
                ui.settle { app.editor.value?.note == "中文备注" }
                report.appendText("PASS real note InputConnection composing zhong -> 中文 -> commit 中文备注; no duplicate or lost text\n")
                capture("04-system-keyboard-save-visible")
                instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                ui.settle {activity!!.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==false}
                ui.click("备注输入",false)
                ui.settle {activity!!.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true}
                ui.click("金额输入",false)
                ui.settle {activity!!.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==false}
                listOf("3","+","4").forEach {ui.click("金额键$it",false)}
                ui.click("转账",false);ui.click("取消",false)
                assertEquals("3+4",app.editor.value?.amount,"Cancel transfer must retain expression draft")
                ui.click("转账",false);ui.click("确认",false)
                ui.settle {app.navigation.value.page=="新增转账" && app.editor.value==null}
                assertTrue(ui.nodes().any {it.text?.toString()=="7.00"},"Transfer must receive evaluated amount")
                ui.click("转出账户");ui.click("UI测试·现金",false)
                ui.click("转入账户");ui.click("UI测试·储蓄",false)
                val transferIds=repo.snapshot().transfers.map {it.id}.toSet()
                ui.click("保存",false)
                ui.settle {!app.ui.value.busy && app.navigation.value.page!="新增转账"}
                assertEquals(700L,repo.snapshot().transfers.single {it.id !in transferIds}.amountMinor)
                report.appendText("PASS amount/category/tag/save completed on one screen with no scroll; exact 12.34 and 3+4 amounts; continue creates a fresh draft\n")
                report.appendText("PASS system keyboard keeps save visible and hides custom keypad; transfer cancel retains draft and expression becomes an exact 700-minor-unit transfer\n")
            }
        } finally {scope.cancel();repo.close();dir.deleteRecursively()}
    }

    @Test fun draggedPanelTracksPointerWithoutAnExtraPlacementAnimation()=runBlocking {
        assertTrue(context.packageName.endsWith(".validation"))
        val (isolated,dir)=isolated();val repo=GenerationRepository(isolated,env)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val output=File(context.getExternalFilesDir(null),"drag-animation").apply {deleteRecursively();mkdirs()}
        val samples=mutableListOf<String>();val errors=mutableListOf<Float>()
        try {
            repo.initialize();repo.replace(sample());val app=AppController(repo,env,scope)
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                scenario.onActivity {it.setContent {DawnYewApp(app,PreviewActions,false,utilities=PreviewTools)};app.finance.open("主页面板")}
                fun nodeBounds(label:String):android.graphics.Rect {
                    repeat(60) {
                        instrument.waitForIdleSync()
                        if(android.os.Build.VERSION.SDK_INT>=34)instrument.uiAutomation.clearCache()
                        fun find(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                            if(node==null)return null
                            if(node.contentDescription?.toString()==label)return node
                            repeat(node.childCount) {find(node.getChild(it))?.let {return it}}
                            return null
                        }
                        find(instrument.uiAutomation.rootInActiveWindow)?.let {node->
                            val rect=android.graphics.Rect().also(node::getBoundsInScreen)
                            if(rect.width()>0 && rect.height()>0)return rect
                        }
                        SystemClock.sleep(100)
                    }
                    error("Missing drag node: $label")
                }
                fun capture(name:String) {
                    scenario.onActivity {activity->
                        val view=activity.window.decorView
                        assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE!=0)
                        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(bitmap))
                        File(output,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                        bitmap.recycle()
                    }
                }
                val first=PanelCatalog.definitions(PanelSurface.HOME)[0];val second=PanelCatalog.definitions(PanelSurface.HOME)[1]
                val from=nodeBounds("拖动排序${first.title}");val to=nodeBounds("拖动排序${second.title}")
                val x=from.left+from.width()*.30f;val start=from.exactCenterY();val end=to.bottom-15f
                val downAt=SystemClock.uptimeMillis()
                fun inject(action:Int,y:Float) {
                    val event=android.view.MotionEvent.obtain(downAt,SystemClock.uptimeMillis(),action,x,y,0)
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue(instrument.uiAutomation.injectInputEvent(event,true));event.recycle()
                }
                capture("00-before")
                inject(android.view.MotionEvent.ACTION_DOWN,start)
                SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout()+160L)
                // Buffer frames in memory: encoding or accessibility calls between frames hide a short placement jump.
                val frames=mutableListOf<Pair<String,Bitmap>>()
                var surfaceColor=0
                scenario.onActivity {activity->
                    val view=activity.window.decorView;val bitmap=Bitmap.createBitmap(view.width/2,view.height/2,Bitmap.Config.ARGB_8888)
                    val canvas=Canvas(bitmap);canvas.scale(.5f,.5f);view.draw(canvas)
                    surfaceColor=bitmap.getPixel(from.centerX()/2,(from.top+30)/2);bitmap.recycle()
                }
                try {
                    repeat(30) {step->
                        val pointer=if(step<3)start+(end-start)*(step+1)/3 else end
                        if(step<3)inject(android.view.MotionEvent.ACTION_MOVE,pointer)
                        SystemClock.sleep(16)
                        scenario.onActivity {activity->
                            val view=activity.window.decorView;val bitmap=Bitmap.createBitmap(view.width/2,view.height/2,Bitmap.Config.ARGB_8888)
                            val canvas=Canvas(bitmap);canvas.scale(.5f,.5f);view.draw(canvas)
                            frames+=("hold-$step" to bitmap)
                            // The floating item is 1.5% wider. This strip lies outside every ordinary row.
                            val sampleX=(from.left/2-2).coerceAtLeast(0)
                            val hits=((from.top/2-60).coerceAtLeast(0)..(to.bottom/2+90).coerceAtMost(bitmap.height-1)).filter {y->bitmap.getPixel(sampleX,y)==surfaceColor}
                            if(hits.isNotEmpty()) {
                                val center=(hits.first()+hits.last()).toFloat()
                                val error=kotlin.math.abs(center-pointer)
                                errors+=error;samples+="frame=$step pointer=$pointer pixelCenter=$center error=$error"
                            } else samples+="frame=$step no floating strip yet"
                        }
                    }
                } finally {inject(android.view.MotionEvent.ACTION_UP,end)}
                repeat(30) {step->
                    SystemClock.sleep(16)
                    scenario.onActivity {activity->
                        val view=activity.window.decorView;val bitmap=Bitmap.createBitmap(view.width/2,view.height/2,Bitmap.Config.ARGB_8888)
                        val canvas=Canvas(bitmap);canvas.scale(.5f,.5f);view.draw(canvas)
                        frames+=("drop-$step" to bitmap)
                    }
                }
                frames.forEach {(name,bitmap)->File(output,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
                val deadline=SystemClock.elapsedRealtime()+5000
                while(PanelCatalog.resolve(repo.snapshot().preferences,PanelSurface.HOME).order[1]!=first.id && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(40)
                assertEquals(first.id,PanelCatalog.resolve(repo.snapshot().preferences,PanelSurface.HOME).order[1])
                File(output,"pointer-samples.txt").writeText(samples.joinToString("\n"))
                assertTrue(errors.size>=20,"Insufficient floating-item pixel samples: $samples")
                assertTrue(errors.maxOrNull()!!<=16f,"Dragged item diverged from pointer: max=${errors.maxOrNull()}px; $samples")
            }
        } finally {File(output,"pointer-samples.txt").writeText(samples.joinToString("\n"));scope.cancel();repo.close();dir.deleteRecursively()}
    }

    @Test fun realActivityRevocationClearsDraftsButRotationKeepsThemAndCsvCancellationCleansScratch() {
        assertTrue(context.packageName.endsWith(".validation"))
        val application=context.applicationContext as DawnYewApplication
        val instrument=InstrumentationRegistry.getInstrumentation()
        fun awaitReady(predicate:()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+10_000;var ready=false
            while(!ready && SystemClock.elapsedRealtime()<deadline) {
                instrument.runOnMainSync {ready=predicate()}
                if(!ready)SystemClock.sleep(20)
            }
            assertTrue(ready)
        }
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            awaitReady {application.controller.ui.value.dataset!=null}
            var operation="";var generation=0L
            scenario.onActivity {
                val controller=application.controller
                controller.beginEditor(controller.ui.value.dataset!!.ledgers.first().id)
                operation=controller.editor.value!!.operationId
                controller.changeEditor(operation,EditorChange.Amount("42.50"))
                generation=controller.transientGeneration.value
            }
            scenario.recreate()
            scenario.onActivity {assertEquals(operation,application.controller.editor.value?.operationId)}
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            awaitReady {application.controller.editor.value==null}
            assertTrue(application.controller.transientGeneration.value>generation)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity {
                application.controller.beginEditor(application.controller.ui.value.dataset!!.ledgers.first().id)
                application.revokeSession()
                assertNull(application.controller.editor.value)
            }
            listOf(0L,5L,25L).forEach {wait->
                scenario.onActivity {it.exportCsv(QueryScope());assertTrue(application.controller.ui.value.busy,"CSV export was not started")}
                if(wait>0)SystemClock.sleep(wait)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
                awaitReady {!application.controller.ui.value.busy}
                assertTrue(File(context.noBackupFilesDir,"staging").listFiles().orEmpty().none {it.extension=="csv"},"CSV scratch remained after cancellation")
                assertNull(application.pendingExternal)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            }
        }
    }
    private object PreviewActions:PlatformActions {
        override val capturePlatform = PreviewCapturePlatform
        override fun exportBackup(encrypted:Boolean,password:CharArray)=error("Not part of rendering")
        override fun restoreBackup(password:CharArray)=error("Not part of rendering")
        override fun chooseScreenshot()=error("Not part of rendering")
        override fun setAppLock(enabled:Boolean)=error("Not part of rendering")
        override fun clearAllData()=error("Not part of rendering")
        override fun restoreProtectionBackup()=error("Not part of rendering")
        override fun clearProtectionBackup()=error("Not part of rendering")
        override fun importCsv(ledgerId:String)=error("Not part of rendering")
        override fun exportCsv(scope:QueryScope)=error("Not part of rendering")
        override fun requestWidget()=error("Not part of rendering")
    }
    private object PreviewTools:UtilityPlatform {
        override val rulerScale=MutableStateFlow(RulerScale(40f))
        override val noise=MutableStateFlow(NoiseUi())
        override fun setRulerFullscreen(enabled:Boolean)=Unit
        override fun saveRulerScale(pixelsPerCm:Float)=error("Not part of rendering")
        override fun resetRulerScale()=error("Not part of rendering")
        override fun startNoise()=error("Not part of rendering")
        override fun stopNoise()=Unit
        override fun calibrateNoise(referenceDb:Double)=error("Not part of rendering")
        override fun resetNoiseCalibration()=error("Not part of rendering")
    }
}
