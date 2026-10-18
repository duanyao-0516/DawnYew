package com.dawnyew.core

import kotlinx.datetime.*

private fun firstOfMonth(yearMonth:String):LocalDate {
    validateOccurrence("$yearMonth-01T00:00:00")
    return LocalDate.parse("$yearMonth-01")
}
fun monthRange(yearMonth:String):Pair<String,String> {
    val first=firstOfMonth(yearMonth)
    val end=if(first.year==9999 && first.month==Month.DECEMBER) LocalDate(9999,12,31) else first.plus(1,DateTimeUnit.MONTH).minus(1,DateTimeUnit.DAY)
    return first.toString() to end.toString()
}
fun shiftMonth(yearMonth:String,delta:Int):String {
    val next=try { firstOfMonth(yearMonth).plus(delta,DateTimeUnit.MONTH) } catch (_:Exception) { invalid("月份超出支持范围") }
    if(next.year !in 1..9999) invalid("月份超出支持范围")
    return next.toString().take(7)
}
