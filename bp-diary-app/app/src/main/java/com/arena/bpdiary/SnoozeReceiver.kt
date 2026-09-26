package com.arena.bpdiary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

/**
 * Приёмник нажатия кнопки «Отложить на 30 минут» прямо из уведомления в шторке.
 */
class SnoozeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val id = intent.getIntExtra(ReminderBannerActivity.EXTRA_ID, 0)
        val type = intent.getStringExtra(ReminderBannerActivity.EXTRA_TYPE) ?: ReminderBannerActivity.TYPE_MEASURE
        val hour = intent.getIntExtra(ReminderBannerActivity.EXTRA_HOUR, 8)
        val minute = intent.getIntExtra(ReminderBannerActivity.EXTRA_MINUTE, 0)
        val name = intent.getStringExtra(ReminderBannerActivity.EXTRA_NAME).orEmpty()
        val dose = intent.getStringExtra(ReminderBannerActivity.EXTRA_DOSE).orEmpty()

        // Скрываем текущее уведомление
        try {
            NotificationManagerCompat.from(context).cancel(id)
        } catch (_: Exception) {
        }

        // Планируем повтор через 30 минут с повторным звуком и баннером
        ReminderScheduler.scheduleSnooze(
            ctx = context.applicationContext,
            type = type,
            id = id,
            hour = hour,
            minute = minute,
            name = name,
            dose = dose,
            delayMinutes = 30
        )

        Toast.makeText(context, R.string.reminder_banner_snoozed_toast, Toast.LENGTH_SHORT).show()
    }
}
