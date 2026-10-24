package com.dawnyew.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.dawnyew.core.*
import com.dawnyew.presentation.PlatformActions

@Composable internal fun ProfilePage(snapshot: FinanceSnapshot?, activity: ActivitySummary?, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { DashboardCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ProfileMetric(activity?.days?.size?.toString() ?: "—", "记账天数")
                ProfileMetric(activity?.activityCount?.toString() ?: "—", "交易笔数")
                ProfileMetric(snapshot?.netWorthMinor?.let { summaryAmount(it, LocalCompactAmounts.current) } ?: "—", "净资产")
            }
        } }
        item { DashboardCard { ManagementGrid(listOf("分类" to "分类管理", "项目" to "项目", "商家" to "商家", "标签" to "标签", "导入" to "导入", "导出" to "导出", "账本" to "账本管理", "回收站" to "回收站"), onOpen) } }
        item { ProfileGroup(listOf("自动" to "本地截图识别", "组件" to "快捷记账", "备份" to "本地完整备份"), onOpen) }
        item { ProfileGroup(listOf("货币" to "人民币", "安全" to "设备认证应用锁", "提醒" to "到期计划与往来", "工具箱" to "本地实用工具"), onOpen) }
        item { ProfileGroup(listOf("设置" to "", "手册" to "本地使用说明"), onOpen) }
        item { Text("所有记录保存在此设备", Modifier.fillMaxWidth().padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun ProfileGroup(items: List<Pair<String, String>>, onOpen: (String) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            items.forEachIndexed { index, (title, subtitle) ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                ProfileAction(title, subtitle) { onOpen(title) }
            }
        }
    }
}

@Composable private fun ProfileMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.widthIn(min = 88.dp)) { Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium); Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
@Composable private fun ProfileAction(title: String, subtitle: String = "", onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), contentPadding = PaddingValues(0.dp)) {
        Box(Modifier.size(28.dp).padding(4.dp)) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { AppGlyph(title, Modifier.size(20.dp)) }
        }
        Spacer(Modifier.width(12.dp)); Text(title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { AppGlyph("前进", Modifier.size(16.dp)) }
    }
}

@Composable internal fun BackupPage(actions: PlatformActions, onConfirm: (Confirmation) -> Unit, onOpen: (String) -> Unit) {
    var passwordMode by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SettingsSection {
            SettingItem("导出 JSON 备份", "完整账本、资金账户、计划及关系", onClick = { onConfirm(Confirmation("导出明文备份？", "JSON 文件可被持有文件的人读取，请保存到你信任的本地位置。") { actions.exportBackup(false, charArrayOf()) }) })
            SettingsDivider()
            SettingItem("导出加密备份", "使用独立口令保护 .dwybak 文件", onClick = { passwordMode = "export" })
            SettingsDivider()
            SettingItem("从备份恢复", "校验预览后整体替换，保留一次保护副本", onClick = { passwordMode = "restore" })
            SettingsDivider()
            SettingItem("恢复保护副本", "重新验证上一次替换前的本机账本", onClick = actions::restoreProtectionBackup)
            SettingsDivider()
            SettingItem("清理保护副本", "删除上次恢复前保留的本机数据", destructive = true, onClick = { onConfirm(Confirmation("清理保护副本？", "不会影响当前账本，清理后不能用此副本恢复。", actions::clearProtectionBackup)) })
        } }
    }
    passwordMode?.let { mode -> PasswordDialog(mode == "export", { passwordMode = null }) { password -> passwordMode = null; if (mode == "export") actions.exportBackup(true, password.toCharArray()) else actions.restoreBackup(password.toCharArray()) } }
}

@Composable internal fun SecurityPage(actions: PlatformActions, enabled: Boolean, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { SettingsSection {
            SettingItem("应用锁", "使用设备指纹、面容或锁屏密码", onClick = { actions.setAppLock(!enabled) }, trailing = { CompactFinanceSwitch(enabled, actions::setAppLock, modifier = Modifier.semantics { contentDescription = "应用锁" }) })
        } }
    }
}

private enum class LocalSetting(val title: String, val key: String) {
    THEME("暗夜模式", "theme"), AMOUNT("金额显示", "amountDisplay"), LEDGER("默认账本", "lastLedgerId"), ACCOUNT("默认记账账户", "defaultAccountId")
}

@Composable internal fun LocalSettingsPage(s: LedgerState, actions: PlatformActions, onTheme: (String) -> Unit, onPreference: (String, String) -> Unit, onConfirm: (Confirmation) -> Unit, onOpen: (String) -> Unit) {
    var selecting by remember { mutableStateOf<LocalSetting?>(null) }
    val preferences = s.preferences.associate { it.key to it.value }
    val theme = preferences["theme"] ?: "system"
    val amount = preferences["amountDisplay"] ?: "precise"
    val ledger = preferences["lastLedgerId"] ?: s.ledgers.firstOrNull { !it.archived }?.id.orEmpty()
    val account = preferences["defaultAccountId"] ?: "none"
    val themes = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
    val amounts = listOf("precise" to "精确两位小数", "compact" to "摘要省略末尾零")
    val virtual = s.obligations.map { it.virtualAccountId }.toSet()
    val ledgers = s.ledgers.filter { !it.archived }.map { it.id to it.name }
    val accounts = listOf("none" to "无账户") + s.accounts.filter { !it.archived && it.id !in virtual }.map { it.id to "${s.ledgers.find { l -> l.id == it.ledgerId }?.name.orEmpty()} · ${it.name}" }
    val ledgerName = s.ledgers.find { it.id == ledger }?.let { it.name + if (it.archived) " · 已归档" else "" } ?: "暂无账本"
    val accountName = if (account == "none") "无账户" else s.accounts.find { it.id == account }?.let { it.name + if (it.archived) " · 已归档" else "" } ?: "无账户"
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SettingsSection("外观") {
            SettingItem("暗夜模式", value = themes.find { it.first == theme }?.second ?: "跟随系统", onClick = { selecting = LocalSetting.THEME })
            SettingsDivider()
            SettingItem("金额显示", value = amounts.find { it.first == amount }?.second, onClick = { selecting = LocalSetting.AMOUNT })
        } }
        item { SettingsSection("记账") {
            SettingItem("默认账本", "下次打开应用时使用", value = ledgerName, onClick = { selecting = LocalSetting.LEDGER })
            SettingsDivider()
            SettingItem("默认记账账户", value = accountName, onClick = { selecting = LocalSetting.ACCOUNT })
            SettingsDivider()
            val continuing = preferences["continueEntry"] == "true"
            SettingItem("保存后继续记账", "普通手动新增时生效；仍可选择保存并返回", onClick = { onPreference("continueEntry", (!continuing).toString()) }, trailing = { CompactFinanceSwitch(continuing, { onPreference("continueEntry", it.toString()) }, modifier = Modifier.semantics { contentDescription = "保存后继续记账" }) })
            SettingsDivider()
            SettingItem("账本管理", "选择账本、重命名与归档", onClick = { onOpen("账本管理") })
        } }
        item { SettingsSection("数据与安全") {
            SettingItem("安全", "设备认证应用锁", onClick = { onOpen("安全") })
            SettingsDivider()
            SettingItem("备份", "本地导出与恢复", onClick = { onOpen("备份") })
            SettingsDivider()
            SettingItem("清空全部数据", "包括回收站、内部副本与暂存文件；保留应用锁", destructive = true, onClick = { onConfirm(Confirmation("清空所有本机记账数据？", "此操作无法撤销。请先保存并验证备份，应用锁设置会保留。", actions::clearAllData)) })
        } }
    }
    selecting?.let { setting ->
        val selected = when (setting) { LocalSetting.THEME -> theme; LocalSetting.AMOUNT -> amount; LocalSetting.LEDGER -> ledger; LocalSetting.ACCOUNT -> account }
        val options = when (setting) { LocalSetting.THEME -> themes; LocalSetting.AMOUNT -> amounts; LocalSetting.LEDGER -> ledgers; LocalSetting.ACCOUNT -> accounts }
        SettingsChoiceDialog(setting.title, selected, options, { selecting = null }) { value ->
            selecting = null
            if (setting == LocalSetting.THEME) onTheme(value) else onPreference(setting.key, value)
        }
    }
}

@Composable internal fun LocalHelpPage() {
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SettingsSection {
            SettingsText("本机记账", "全部记录保存在本机，支持离线使用。支出、收入与退款构成普通收支；账户间转账与借还不计入普通收支。")
            SettingsDivider()
            SettingsText("账户与计划", "账户余额由初始余额和已入账流水计算。未入账记录仍可影响收支和预算。存钱记录可以只更新计划进度，也可以明确选择资金转出和转入账户。周期计划在应用前台可用时处理到期项目。")
            SettingsDivider()
            SettingsText("保存与备份", "账单移入回收站后可以恢复；彻底删除无法撤销。JSON 和加密备份用于完整迁移，CSV 仅交换普通支出与收入。恢复前请核对预览并保存自己的备份。")
            SettingsDivider()
            SettingsText("截图识别", "截图只在本机识别，不保存原图。识别结果是待核对草稿，确认金额、日期、账本、类型与疑似重复后才能保存。")
        } }
    }
}

@Composable internal fun ReminderPage(snapshot: FinanceSnapshot?, plans: List<RecurringPlan>, today: String, onOpen: (String, String) -> Unit) {
    val duePlans = plans.filter { !it.archived && !it.paused && (it.totalOccurrences?.let { total -> it.nextIndex < total } ?: true) && runCatching { recurringDate(it, it.nextIndex).take(10) <= today }.getOrDefault(false) }
    val obligations = snapshot?.obligations?.filter { !it.obligation.archived && it.remainingMinor > 0 }.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SettingsSection("到期周期") {
            if (duePlans.isEmpty()) SettingItem("暂无到期周期")
            duePlans.forEachIndexed { index, plan ->
                if (index > 0) SettingsDivider()
                SettingItem(plan.name, "${frequencyName(plan.frequency)} · 已到期", onClick = { onOpen("周期", plan.id) })
            }
        } }
        item { SettingsSection("未结清往来") {
            if (obligations.isEmpty()) SettingItem("暂无未结清往来")
            obligations.forEachIndexed { index, balance ->
                if (index > 0) SettingsDivider()
                SettingItem(balance.obligation.title, Money.format(balance.remainingMinor) + (balance.obligation.dueDate?.let { " · 到期 $it" } ?: " · 未设置到期日"), onClick = { onOpen(obligationName(balance.obligation.kind), balance.obligation.id) })
            }
        } }
    }
}
