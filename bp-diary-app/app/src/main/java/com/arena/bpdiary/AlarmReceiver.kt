package com.arena.bpdiary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val pending = goAsync()
        var hold = false
        try {
            val id = intent.getIntExtra("id", 0)
            val hour = intent.getIntExtra("hour", 8)
            val minute = intent.getIntExtra("minute", 0)
            val type = intent.getStringExtra("type") ?: "measure"
            val isSnooze = intent.getBooleanExtra(ReminderScheduler.EXTRA_SNOOZE, false)

            when (type) {
                "med" -> {
                    val name = intent.getStringExtra("name") ?: ""
                    val dose = intent.getStringExtra("dose") ?: ""
                    Notifications.showMed(context, id, name, dose, hour, minute)
                    Notifications.playMedSignal(context)
                    hold = true
                    // Регулярный суточный будильник перезапускаем только если это был плановый вызов, а не snooze
                    if (!isSnooze) {
                        ReminderScheduler.scheduleMedSlot(context, id, hour, minute, name, dose)
                    }
                }
                else -> {
                    Notifications.show(
                        context,
                        id,
                        context.getString(R.string.notify_title),
                        context.getString(R.string.notify_text),
                        hour,
                        minute
                    )
                    if (!isSnooze) {
                        ReminderScheduler.schedule(context, Reminder(id, hour, minute, true, ""))
                    }
                }
            }

            // Показываем плавающий баннер поверх рабочего стола/приложений
            try {
                val bannerIntent = ReminderBannerActivity.createIntent(
                    ctx = context,
                    type = type,
                    id = id,
                    hour = hour,
                    minute = minute,
                    name = intent.getStringExtra("name") ?: "",
                    dose = intent.getStringExtra("dose") ?: ""
                )
                context.startActivity(bannerIntent)
            } catch (_: Exception) {
                // Если система временно ограничила запуск Activity из фона,
                // сработает heads-up уведомление с fullScreenIntent и кнопками.
            }

        } catch (_: Exception) {
            // нет разрешения на уведомления или сбой прошивки — не закрываем приложение
        }
        // Удерживаем WakeLock на несколько секунд, чтобы устройство успело зажечь экран и показать Activity
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val wl = pm?.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                "bpdiary:alarm_wake"
            )
            wl?.acquire(3500L)
        } catch (_: Exception) {
        }

        if (hold) {
            Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 2200)
        } else {
            pending.finish()
        }
    }
}
