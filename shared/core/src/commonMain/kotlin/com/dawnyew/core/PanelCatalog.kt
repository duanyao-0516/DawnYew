package com.dawnyew.core

enum class PanelSurface { HOME, STATISTICS }
data class PanelDefinition(val id:String,val title:String,val description:String,val defaultEnabled:Boolean)
data class PanelLayout(val order:List<String>,val enabled:Set<String>)

/** Stable wire identifiers are independent of display language and route names. */
object PanelCatalog {
    private val home=listOf(
        PanelDefinition("monthly_expense","本月支出","本月支出总额和每日变化",true),
        PanelDefinition("recent_transactions","最近交易","查看最近记录的账单",true),
        PanelDefinition("budget_overview","预算概览","本月预算与剩余额度",true),
        PanelDefinition("budget_tracking","预算追踪","各分类预算的使用进度",false),
        PanelDefinition("account_tracking","账户追踪","资金账户余额与资产变化",false),
        PanelDefinition("category_change","分类涨跌","与上月比较各分类净支出",false),
        PanelDefinition("calendar","日历视图","按日期查看收支与交易",true),
        PanelDefinition("income_expense","收支对比","比较每天的收入与支出",false),
        PanelDefinition("category_radar","分类倾向","查看支出分类的分布",false),
        PanelDefinition("savings","存钱计划","查看本地存钱目标的进度",true),
    )
    private val statistics=listOf(
        PanelDefinition("category","分类统计","按分类查看金额分布",true),
        PanelDefinition("daily","日趋势","每日收支变化",true),
        PanelDefinition("monthly","月度收支","本年度每月收支",true),
        PanelDefinition("category_monthly","分类收支","所选分类每月的收支变化",false),
        PanelDefinition("yearly","年度统计","历年收支对比",false),
        PanelDefinition("weekday","一周统计","按星期统计收支",false),
        PanelDefinition("account","账户统计","按记账资金账户统计",false),
        PanelDefinition("maximum","单笔最高","每月金额最高的单笔交易",false),
        PanelDefinition("average","分类均价","各分类每笔平均金额",false),
        PanelDefinition("count","笔数统计","每月交易笔数",false),
        PanelDefinition("weekend","周末统计","工作日与周末收支对比",false),
        PanelDefinition("amount_distribution","金额分布","按单笔金额区间统计",false),
        PanelDefinition("project","项目统计","按项目查看收支",false),
        PanelDefinition("merchant","商家统计","按商家查看收支",false),
        PanelDefinition("tag","标签统计","按标签统计，多个标签可能重复归属",false),
        PanelDefinition("quarterly","季度统计","本年度每季度收支",false),
    )
    fun definitions(surface:PanelSurface):List<PanelDefinition> = if(surface==PanelSurface.HOME) home else statistics
    fun preferenceKey(surface:PanelSurface):String = if(surface==PanelSurface.HOME) "homePanels" else "statisticsPanels"
    fun defaultLayout(surface:PanelSurface):PanelLayout = definitions(surface).let {list->PanelLayout(list.map {it.id},list.filter {it.defaultEnabled}.map {it.id}.toSet())}
    fun resolve(preferences:List<UserPreference>,surface:PanelSurface):PanelLayout = preferences.firstOrNull {it.key==preferenceKey(surface)}?.let {decode(surface,it.value)} ?: defaultLayout(surface)
    private fun validate(surface:PanelSurface,layout:PanelLayout) {
        val ids=definitions(surface).map {it.id}.toSet()
        if(layout.order.size!=ids.size || layout.order.toSet()!=ids || !ids.containsAll(layout.enabled)) invalid("面板配置不完整或包含未知项")
    }
    fun encode(surface:PanelSurface,layout:PanelLayout):String {
        validate(surface,layout)
        return "1|"+layout.order.joinToString("|") {"$it=${if(it in layout.enabled) 1 else 0}"}
    }
    fun decode(surface:PanelSurface,value:String):PanelLayout {
        if(value.length>2048) invalid("面板配置过长")
        val parts=value.split('|')
        if(parts.firstOrNull()!="1") invalid("不支持的面板配置版本")
        val enabled=mutableSetOf<String>()
        val order=parts.drop(1).map {part->
            val fields=part.split('=')
            if(fields.size!=2 || fields[1] !in setOf("0","1")) invalid("面板配置格式错误")
            if(fields[1]=="1") enabled+=fields[0]
            fields[0]
        }
        return PanelLayout(order,enabled).also {validate(surface,it)}
    }
}
