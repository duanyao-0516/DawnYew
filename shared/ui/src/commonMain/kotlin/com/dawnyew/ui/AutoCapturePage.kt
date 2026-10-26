package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dawnyew.presentation.CapturePlatform

@Composable internal fun AutoCapturePage(platform: CapturePlatform, onChooseImage: () -> Unit) {
    val state by platform.state.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSection {
            SettingsText("本机截图记账", "点击全局悬浮球即可识别并记账，也可使用截屏快捷按钮、系统截图监听或分享图片。截图仅在本机处理。")
        }
        SettingsSection("全局悬浮记账") {
            SettingItem("显示悬浮球", "在其他 App 看到账单时点一下悬浮球，即可截取当前画面并离线识别。拖动可调整位置。",
                onClick = { platform.setFloatingCapture(!state.floatingEnabled) }, trailing = {
                    CompactFinanceSwitch(state.floatingEnabled, platform::setFloatingCapture, modifier = Modifier.semantics { contentDescription = "全局悬浮记账开关" })
                })
            SettingsDivider()
            SettingItem("保存前确认一次", "默认识别后直接保存；开启后先显示全局确认面板，可调整金额和收支方向再保存。",
                onClick = { platform.setFloatingConfirmation(!state.floatingConfirmBeforeSave) }, trailing = {
                    CompactFinanceSwitch(state.floatingConfirmBeforeSave, platform::setFloatingConfirmation, modifier = Modifier.semantics { contentDescription = "悬浮记账保存前确认开关" })
                })
            SettingsDivider()
            SettingItem("悬浮记账权限", "需要记账结果通知及系统无障碍截屏授权", value = if (state.floatingConnected) "已连接" else "设置", onClick = platform::requestFloatingPermissions)
            SettingsText("使用说明", "仅点击时截取一张画面，不读取界面节点、不代替操作其他 App。缺少日期时采用截图时间，账本与分类使用默认选择；保存后可从通知查看、修改或撤销。启用应用锁时先解锁账本，再继续入账。")
            SettingsText("运行状态", when {
                !state.floatingEnabled -> "未开启。打开开关并完成系统授权后，悬浮球才会显示。"
                state.floatingMessage != null -> state.floatingMessage.orEmpty()
                state.floatingConnected -> "悬浮球已连接，可切换到目标 App 点击记账。锁屏时自动隐藏。"
                else -> "等待系统无障碍服务连接，请完成系统授权。"
            })
        }
        SettingsSection("截屏记账") {
            SettingItem("添加截屏记账按钮", "下拉通知栏点「截屏记账」，授权后截取当前页面，不在相册留下截图。", value = if (state.screenTileAdded) "已添加" else "添加", onClick = { platform.requestTile(false) })
            SettingsDivider()
            SettingsText("使用方法", "先打开支付账单，再下拉通知栏点击快捷按钮。每次截屏都由系统确认，完成后立即停止；不支持截图的页面仍受系统保护。")
        }
        SettingsSection("自动记账") {
            SettingItem("识别新系统截图", "开启后使用系统截图快捷键。识别结果通过通知打开核对；启用应用锁时，解锁后才读取图片。",
                onClick = { platform.setAutomaticCapture(!state.automaticRunning) }, trailing = {
                    CompactFinanceSwitch(state.automaticRunning, platform::setAutomaticCapture, modifier = Modifier.semantics { contentDescription = "自动识别截图开关" })
                })
            SettingsDivider()
            SettingItem("添加自动记账开关", "从通知栏直接开启或停止截图监听", value = if (state.automaticTileAdded) "已添加" else "添加", onClick = { platform.requestTile(true) })
            SettingsDivider()
            SettingItem("系统权限", "${if (state.mediaPermission) "照片已允许" else "需要允许读取全部照片"} · ${if (state.notificationPermission) "通知已允许" else "需要允许通知"}", onClick = platform::openAppSettings)
            SettingsText("运行状态", when {
                state.automaticPaused -> "等待解锁后继续；不会读取锁定状态下的图片。"
                state.automaticRunning -> "正在监听新截图，可随时从本页或运行通知停止。"
                else -> "未开启。只有主动开启后才监听新截图，不扫描历史照片。"
            })
        }
        SettingsSection("识图记账") {
            SettingItem("选择本地截图", "也可以在系统截图预览或相册中点分享，选择 DawnYew。", onClick = onChooseImage)
        }
        state.message?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(12.dp))
    }
}
