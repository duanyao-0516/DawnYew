package com.dawnyew.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** The launcher receives static actions only. No ledger, amount or note is put in RemoteViews. */
class QuickEntryWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views=RemoteViews(context.packageName,R.layout.widget_quick_entry)
            listOf(R.id.widget_expense to "EXPENSE",R.id.widget_income to "INCOME",R.id.widget_capture to "OCR").forEachIndexed { index,(view,kind) ->
                val intent=Intent(context,MainActivity::class.java).setAction(ACTION_QUICK_ENTRY)
                    .putExtra(EXTRA_KIND,kind).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                views.setOnClickPendingIntent(view,PendingIntent.getActivity(context,index,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            manager.updateAppWidget(id,views)
        }
    }
    companion object {
        const val ACTION_QUICK_ENTRY="com.dawnyew.app.QUICK_ENTRY"
        const val EXTRA_KIND="quick_entry_kind"
    }
}
