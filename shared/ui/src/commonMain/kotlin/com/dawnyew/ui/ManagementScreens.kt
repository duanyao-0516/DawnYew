package com.dawnyew.ui
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*
import com.dawnyew.presentation.AppController

@Composable internal fun LedgerManagement(s:LedgerState,controller:AppController,onConfirm:(Confirmation)->Unit) {
    var show by remember {mutableStateOf(false)};var edit by remember {mutableStateOf<Ledger?>(null)}
    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {Button(onClick={edit=null;show=true},modifier=Modifier.fillMaxWidth()) {Text("新建账本")}}
        items(s.ledgers.sortedWith(compareBy<Ledger>{it.sortOrder}.thenBy {it.id}),key={it.id}) {l->Card {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(l.name+if(l.archived) " · 已归档" else "",style=MaterialTheme.typography.titleLarge);Text("${s.entries.count {it.ledgerId==l.id && it.active}} 笔有效账单",style=MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {TextButton(onClick={edit=l;show=true}) {Text("重命名")};TextButton(onClick={controller.perform(Command.UpdateLedger(l.id,l.name,l.archived,(s.ledgers.minOfOrNull {it.sortOrder} ?: 0)-1))}) {Text("置顶")};TextButton(onClick={onConfirm(Confirmation(if(l.archived) "取消归档账本？" else "归档账本？",if(l.archived) "取消归档后可继续记账。" else "历史数据保留，归档后不能新增或修改其中的账单。") {controller.perform(Command.UpdateLedger(l.id,l.name,!l.archived,l.sortOrder))})}) {Text(if(l.archived) "取消归档" else "归档")};TextButton(onClick={onConfirm(Confirmation("删除空账本？","会同时删除该账本的预算与无引用分类。有账单的账本请使用归档。") {controller.perform(Command.DeleteLedger(l.id))})}) {Text("删除")}}
        }}}
    }
    if(show) NameDialog(if(edit==null) "新建账本" else "重命名账本",edit?.name.orEmpty(),{show=false}) {name->val l=edit;controller.perform(if(l==null) Command.CreateLedger(name) else Command.UpdateLedger(l.id,name,l.archived,l.sortOrder)) {show=false}}
}
@Composable internal fun CategoryManagement(s:LedgerState,defaultLedger:String?,controller:AppController,onConfirm:(Confirmation)->Unit) {
    var ledger by remember {mutableStateOf(defaultLedger ?: s.ledgers.filterNot {it.archived}.minWith(compareBy<Ledger> {it.sortOrder}.thenBy {it.id}).id)};var direction by remember {mutableStateOf(Direction.EXPENSE)};var picker by remember {mutableStateOf(false)};var show by remember {mutableStateOf(false)};var edit by remember {mutableStateOf<Category?>(null)}
    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item {TextButton(onClick={picker=true}) {Text("账本："+(s.ledgers.find {it.id==ledger}?.name.orEmpty())+" ▾")}}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {FilterChip(direction==Direction.EXPENSE,{direction=Direction.EXPENSE},label={Text("支出")});FilterChip(direction==Direction.INCOME,{direction=Direction.INCOME},label={Text("收入")})}}
        item {Button(onClick={edit=null;show=true},modifier=Modifier.fillMaxWidth()) {Text("新建分类")}}
        items(s.categories.filter {it.ledgerId==ledger && it.direction==direction}.sortedBy {it.sortOrder},key={it.id}) {c->Card {Column(Modifier.fillMaxWidth().padding(12.dp)) {Row(verticalAlignment=Alignment.CenterVertically) {Text(c.name,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Switch(c.enabled,{controller.perform(Command.SaveCategory(c.ledgerId,c.direction,c.name,c.id,it,c.sortOrder))})};FlowRow {TextButton(onClick={edit=c;show=true}) {Text("重命名")};TextButton(onClick={controller.perform(Command.SaveCategory(c.ledgerId,c.direction,c.name,c.id,c.enabled,(s.categories.filter {it.ledgerId==ledger && it.direction==direction}.minOfOrNull {it.sortOrder} ?: 0)-1))}) {Text("置顶")};TextButton(onClick={onConfirm(Confirmation("删除分类？","已有账单引用的分类无法删除，请使用停用。") {controller.perform(Command.DeleteCategory(c.id))})}) {Text("删除")}}}}}
    }
    if(picker) ChoiceDialog("选择账本",s.ledgers.map {it.id to (it.name+if(it.archived) " · 已归档" else "")},ledger,{ledger=it;picker=false},{picker=false})
    if(show) NameDialog(if(edit==null) "新建分类" else "重命名分类",edit?.name.orEmpty(),{show=false}) {name->controller.perform(Command.SaveCategory(ledger,direction,name,edit?.id,edit?.enabled ?: true,edit?.sortOrder ?: (s.categories.filter {it.ledgerId==ledger && it.direction==direction}.maxOfOrNull {it.sortOrder} ?: -1)+1)) {show=false}}
}
@Composable internal fun RecyclePage(s:LedgerState,controller:AppController,onConfirm:(Confirmation)->Unit) {
    val groups=s.entries.filter {!it.active}.groupBy {it.deletionGroupId}
    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(groups.isEmpty()) item {EmptyState("回收站为空","删除的账单会在这里保留，直到你永久清理")}
        groups.forEach {(group,entries)->item(key=group) {Card {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("删除组 · ${entries.size} 笔",style=MaterialTheme.typography.titleMedium)
            entries.forEach {e->Text("${e.localDate} · ${s.categories.find {it.id==e.categoryId}?.name} · ¥ ${Money.format(e.amountMinor)}")}
            TextButton(onClick={controller.perform(Command.RestoreGroup(group!!))}) {Text("恢复本组")}
            entries.forEach {e->TextButton(onClick={val linked=s.entries.count {it.parentExpenseId==e.id};onConfirm(Confirmation("永久删除这笔${if(e.kind==EntryKind.REFUND) "退款" else "账单"}？","此操作无法撤销。"+if(linked>0) "同时清理 $linked 笔关联退款。" else "") {controller.perform(Command.Purge(e.id))})}) {Text("永久删除 ¥ ${Money.format(e.amountMinor)}",color=MaterialTheme.colorScheme.error)}}
        }}}}
    }
}
@Composable internal fun NameDialog(title:String,initial:String,onDismiss:()->Unit,onSave:(String)->Unit) {var name by remember {mutableStateOf(initial)};AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={OutlinedTextField(name,{name=it},label={Text("名称")},singleLine=true)},confirmButton={TextButton(onClick={onSave(name)},enabled=name.isNotBlank()) {Text("保存")}},dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})}
