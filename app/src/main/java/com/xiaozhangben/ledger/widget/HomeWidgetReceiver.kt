package com.xiaozhangben.ledger.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll

class HomeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HomeWidget()
}

/**
 * Refreshes the widget explicitly.
 *
 * Called after every capture and after any plan edit, so the widget is always an
 * up-to-date view rather than a stale snapshot that the user stops trusting.
 */
object WidgetUpdater {

    suspend fun refresh(context: Context) {
        runCatching { HomeWidget().updateAll(context.applicationContext) }
    }
}
