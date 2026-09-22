package com.arena.bpdiary

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Виджет «Последнее АД»: показывает последнее измерение,
 * тап открывает приложение. Обновляется после каждого изменения дневника
 * и раз в 30 минут.
 */
class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildViews(context)
        appWidgetIds.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {

        fun buildViews(context: Context): RemoteViews {
            val last = Store(context).records().firstOrNull()
            val views = RemoteViews(context.packageName, R.layout.widget_bp)
            if (last == null) {
                views.setTextViewText(R.id.widgetBp, context.getString(R.string.widget_empty_bp))
                views.setTextViewText(R.id.widgetInfo, context.getString(R.string.widget_empty_info))
            } else {
                val cat = BpClassifier.classify(context, last.sys, last.dia).label
                val df = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
                views.setTextViewText(
                    R.id.widgetBp,
                    context.getString(R.string.bp_value, last.sys, last.dia)
                )
                val pulse = if (last.pulse > 0)
                    context.getString(R.string.widget_pulse_fmt, last.pulse) + " • "
                else ""
                views.setTextViewText(
                    R.id.widgetInfo,
                    pulse + cat + " • " + df.format(Date(last.time))
                )
            }
            val pi = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pi)
            views.setOnClickPendingIntent(R.id.widgetBp, pi)
            views.setOnClickPendingIntent(R.id.widgetInfo, pi)
            return views
        }

        /** Обновить все виджеты (вызывать после изменений дневника). */
        fun updateAll(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val cn = ComponentName(context, WidgetProvider::class.java)
                val ids = manager.getAppWidgetIds(cn)
                if (ids.isNotEmpty()) {
                    val views = buildViews(context)
                    ids.forEach { manager.updateAppWidget(it, views) }
                }
            } catch (_: Exception) {
            }
        }
    }
}
