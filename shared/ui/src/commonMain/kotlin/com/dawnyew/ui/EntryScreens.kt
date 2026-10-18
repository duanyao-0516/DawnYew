package com.dawnyew.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dawnyew.core.*
import com.dawnyew.presentation.EditorChange

@Composable internal fun EntryForm(s:LedgerState,draft:com.dawnyew.presentation.EditorDraft,busy:Boolean,onChange:(EditorChange)->Unit,onSave:(Boolean)->Unit,onCapture:()->Unit,onRetryDuplicateCheck:()->Unit) {
    var error by remember(draft.operationId) {mutableStateOf<String?>(null)}
    var ledgerPicker by remember {mutableStateOf(false)}
    val editing = draft.editId?.let { id -> s.entries.find { it.id == id } }
    val refundParent = draft.refundParentId?.let { id -> s.entries.find { it.id == id } }
    val direction = if(draft.kind==EntryKind.INCOME) Direction.INCOME else Direction.EXPENSE
    val categories=s.categories.filter {it.ledgerId==draft.ledgerId && it.direction==direction && (it.enabled || it.id==editing?.categoryId)}.sortedBy {it.sortOrder}
    fun change(event:EditorChange) {
        error=null
        onChange(event)
    }
    fun save(keep:Boolean) {
        try {draft.command();onSave(keep)} catch(e:DomainException) {error=e.message}
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        if(refundParent!=null) Text("为原支出 ¥ ${Money.format(refundParent.amountMinor)} 添加退款",color=MaterialTheme.colorScheme.primary)
        draft.capture?.let {capture->Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("截图识别草稿 · 尚未记账",fontWeight=FontWeight.SemiBold)
                (capture.warnings+draft.duplicateMessages).forEach {Text(it,style=MaterialTheme.typography.bodyMedium)}
                if(draft.duplicateCheckPending) Text("正在核对本机疑似重复记录…")
                draft.duplicateCheckError?.let {Text(it,color=MaterialTheme.colorScheme.error);TextButton(onClick=onRetryDuplicateCheck,enabled=!busy) {Text("重试核对")}}
                if(capture.duplicateWarning) Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(draft.duplicateAcknowledged,{onChange(EditorChange.DuplicateAcknowledged(it))},enabled=!busy && !draft.duplicateCheckPending && draft.duplicateCheckError==null);Text("我已核对疑似重复，仍要保留本次账单",Modifier.weight(1f))}
                Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(draft.confirmed,{onChange(EditorChange.Confirmed(it))},enabled=!busy && !draft.duplicateCheckPending && draft.duplicateCheckError==null);Text("我已核对金额、时间、账本和账单类型",Modifier.weight(1f))}
            }
        }}
        if(draft.kind!=EntryKind.REFUND) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(EntryKind.EXPENSE to "支出",EntryKind.INCOME to "收入").forEach {(kind,label)->FilterChip(draft.kind==kind,{
            change(EditorChange.Kind(kind))
        },enabled=!busy,label={Text(label)})}}
        OutlinedTextField(draft.amount,{change(EditorChange.Amount(it))},enabled=!busy,label={Text("金额（元）")},prefix={Text("¥")},textStyle=MaterialTheme.typography.headlineLarge,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())
        TextButton(onClick={ledgerPicker=true},enabled=!busy && draft.kind!=EntryKind.REFUND) {Text("账本："+(s.ledgers.find {it.id==draft.ledgerId}?.name ?: "请选择实际账本")+" ▾")}
        SectionTitle("分类")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {categories.forEach {category->FilterChip(draft.categoryId==category.id,{change(EditorChange.Category(category.id))},enabled=!busy && draft.kind!=EntryKind.REFUND,label={Text(category.name)})}}
        if(categories.isEmpty()) Text(if(draft.ledgerId==null) "先选择本次记账的账本" else "当前没有可用分类，请先在设置中创建",color=MaterialTheme.colorScheme.error)
        OutlinedTextField(draft.date,{change(EditorChange.Date(it))},enabled=!busy,label={Text("日期 YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(draft.time,{change(EditorChange.Time(it))},enabled=!busy,label={Text("时间 HH:mm:ss")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(draft.note,{change(EditorChange.Note(it))},enabled=!busy,label={Text("备注")},supportingText={Text("${runCatching {codePointCount(draft.note)}.getOrDefault(draft.note.length)}/500")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=5)
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        Button(onClick={save(false)},enabled=!busy && !draft.duplicateCheckPending && draft.duplicateCheckError==null && (draft.capture==null || draft.confirmed) && (draft.capture?.duplicateWarning!=true || draft.duplicateAcknowledged),modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text(if(editing!=null) "保存修改" else if(refundParent!=null) "确认退款" else "保存账单")}
        if(editing==null && refundParent==null && draft.capture==null) OutlinedButton(onClick={save(true)},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("保存并继续")}
        if(editing==null && refundParent==null && draft.capture==null) TextButton(onClick=onCapture,enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("从本地截图识别")}
        Spacer(Modifier.height(20.dp))
    }
    if(ledgerPicker) ChoiceDialog("选择账本",s.ledgers.filter {!it.archived}.sortedBy {it.sortOrder}.map {it.id to it.name},draft.ledgerId,{id->
        change(EditorChange.Ledger(id));ledgerPicker=false
    },{ledgerPicker=false})
}

@Composable internal fun EntryDetail(s:LedgerState,e:Entry,busy:Boolean,onEdit:()->Unit,onRefund:()->Unit,onDelete:()->Unit,onMove:(String,String)->Unit) {
    var move by remember {mutableStateOf(false)}
    val archived=s.ledgers.find {it.id==e.ledgerId}?.archived==true
    val refunds=s.entries.filter {it.parentExpenseId==e.id && it.active}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text(when(e.kind){EntryKind.EXPENSE->"支出";EntryKind.INCOME->"收入";EntryKind.REFUND->"退款"},color=MaterialTheme.colorScheme.primary)
        Text("¥ "+Money.format(e.amountMinor),style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.SemiBold)
        DetailLine("账本",s.ledgers.find {it.id==e.ledgerId}?.name.orEmpty());DetailLine("分类",s.categories.find {it.id==e.categoryId}?.name.orEmpty());DetailLine("时间",e.occurrence.replace('T',' '));DetailLine("备注",e.note.ifEmpty {"无"});DetailLine("来源",when(e.originKind){OriginKind.MANUAL->"手动记账";OriginKind.OCR->"截图识别后确认";OriginKind.IMPORT->"文件导入"})
        if(e.kind==EntryKind.REFUND) s.entries.find {it.id==e.parentExpenseId}?.let {DetailLine("原支出","¥ ${Money.format(it.amountMinor)} · ${it.localDate}")}
        if(refunds.isNotEmpty()) {SectionTitle("退款记录 · 合计 ¥ ${Money.format(refunds.sumOf {it.amountMinor})}");refunds.forEach {r->DetailLine(r.localDate,"¥ ${Money.format(r.amountMinor)} ${r.note}")}}
        if(archived) Text("此账本已归档，取消归档后可修改",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick=onEdit,enabled=!busy && !archived,modifier=Modifier.fillMaxWidth()) {Text("编辑账单")}
        if(e.kind==EntryKind.EXPENSE) OutlinedButton(onClick=onRefund,enabled=!busy && !archived && refunds.sumOf {it.amountMinor}<e.amountMinor,modifier=Modifier.fillMaxWidth()) {Text("添加退款")}
        if(e.kind!=EntryKind.REFUND) OutlinedButton(onClick={move=true},enabled=!busy && !archived,modifier=Modifier.fillMaxWidth()) {Text("移动到账本")}
        TextButton(onClick=onDelete,enabled=!busy && !archived,modifier=Modifier.fillMaxWidth()) {Text("移入回收站",color=MaterialTheme.colorScheme.error)}
    }
    if(move) MoveDialog(s,e,{move=false}) {l,c->move=false;onMove(l,c)}
}
@Composable private fun DetailLine(label:String,value:String) {Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value)}}
@Composable private fun MoveDialog(s:LedgerState,e:Entry,onDismiss:()->Unit,onMove:(String,String)->Unit) {
    var ledger by remember {mutableStateOf(e.ledgerId)};var category by remember {mutableStateOf(e.categoryId)}
    val direction=if(e.kind==EntryKind.INCOME) Direction.INCOME else Direction.EXPENSE
    AlertDialog(onDismissRequest=onDismiss,title={Text("移动账单")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("原支出的有效退款将一起移动。")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {s.ledgers.filter {!it.archived}.forEach {l->FilterChip(ledger==l.id,{ledger=l.id;category=s.categories.filter {it.ledgerId==l.id && it.direction==direction && it.enabled}.minWithOrNull(compareBy<Category> {it.sortOrder}.thenBy {it.id})?.id.orEmpty()},label={Text(l.name)})}}
        Text("目标分类")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {s.categories.filter {it.ledgerId==ledger && it.direction==direction && it.enabled}.forEach {c->FilterChip(category==c.id,{category=c.id},label={Text(c.name)})}}
    }},confirmButton={TextButton(onClick={onMove(ledger,category)},enabled=category.isNotEmpty()) {Text("确认移动")}},dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})
}
