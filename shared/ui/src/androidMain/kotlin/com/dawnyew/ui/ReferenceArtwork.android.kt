package com.dawnyew.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource

/** Original decoded drawable bytes, mapped only to implemented offline UI. */
@Composable internal actual fun referenceArtwork(name:String):ReferenceArtwork? {
    val entry=when(name) {
        "首页" -> R.drawable.dwy_ref_ic_home to true
        "账户" -> R.drawable.dwy_ref_ic_account to true
        "统计" -> R.drawable.dwy_ref_ic_stat to true
        "更多" -> R.drawable.dwy_ref_ic_advance to true
        "我的" -> R.drawable.dwy_ref_ic_me to true
        "借入" -> R.drawable.dwy_ref_more_borrow to false
        "借出" -> R.drawable.dwy_ref_more_lend to false
        "报销" -> R.drawable.dwy_ref_more_remib to false
        "往来" -> R.drawable.dwy_ref_more_blender to false
        "周期" -> R.drawable.dwy_ref_more_cycle to false
        "预算" -> R.drawable.dwy_ref_more_budget to false
        "存钱" -> R.drawable.dwy_ref_more_savings to false
        "工具箱" -> R.drawable.dwy_ref_ic_advance to true
        "分类" -> R.drawable.dwy_ref_me_category to false
        "分类管理" -> R.drawable.dwy_ref_me_category to false
        "项目" -> R.drawable.dwy_ref_me_project to false
        "商家" -> R.drawable.dwy_ref_me_merchant to false
        "标签" -> R.drawable.dwy_ref_me_tag to false
        "导入" -> R.drawable.dwy_ref_me_import to false
        "导出" -> R.drawable.dwy_ref_me_export to false
        "账本" -> R.drawable.dwy_ref_me_book to false
        "账本管理" -> R.drawable.dwy_ref_me_book to false
        "自动" -> R.drawable.dwy_ref_me_auto_accounting to true
        "组件" -> R.drawable.dwy_ref_me_ic_widget to true
        "备份" -> R.drawable.dwy_ref_me_backup to true
        "安全" -> R.drawable.dwy_ref_me_secure to true
        "货币" -> R.drawable.dwy_ref_me_exchange to true
        "提醒" -> R.drawable.dwy_ref_me_alert to true
        "手册" -> R.drawable.dwy_ref_me_manual to true
        "搜索" -> R.drawable.dwy_ref_ic_search_black_24dp to true
        "设置" -> R.drawable.dwy_ref_ic_settings_black_48dp to true
        "新增" -> R.drawable.dwy_ref_ic_action_plus to true
        "返回" -> R.drawable.dwy_ref_ic_arrow_back_black_24dp to true
        "前进" -> R.drawable.dwy_ref_ic_chevron_right_black_24dp to true
        "回收站" -> R.drawable.dwy_ref_ic_restore_page_black_48dp to true
        "转账" -> R.drawable.dwy_ref_ic_swap_horiz_black_24dp to true
        "现金" -> R.drawable.dwy_ref_ic_account to true
        "储蓄" -> R.drawable.dwy_ref_ic_account to true
        "银行卡" -> R.drawable.dwy_ref_ic_account to true
        "资产背景" -> R.drawable.dwy_ref_image_account_header to false
        else -> return null
    }
    return ReferenceArtwork(painterResource(entry.first),entry.second)
}
