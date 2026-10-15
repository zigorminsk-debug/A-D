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

            // ДЕДУПЛИКАЦИЯ: Если несколько напоминаний сработали в одну и ту же минуту
            // (или старые параллельные будильники сработали одновременно),
            // сигнал должен звучать СТРОГО ОДИН РАЗ, а не для каждого!
            val nowMs = System.currentTimeMillis()
            val timeSlotKey = "${type}_${hour}_${minute}"
            synchronized(alarmLock) {
                if (nowMs - lastTriggerTime < 15_000L && lastTriggerSlot == timeSlotKey) {
                    // Это повторный сработавший будильник на то же самое время — гасим дубликат!
                    pending.finish()
                    return
                }
                lastTriggerTime = nowMs
                lastTriggerSlot = timeSlotKey
            }

            // Очищаем старые разрозненные будильники и актуализируем единое расписание
            ReminderScheduler.rescheduleAllMeds(context)

            // Проверяем, сколько лекарств назначено на данное время (час:минута)
            var finalName = intent.getStringExtra("name") ?: ""
            var finalDose = intent.getStringExtra("dose") ?: ""
            var medCount = 1

            if (type == "med") {
                val store = Store(context)
                val sameTimeMeds = store.meds().filter { it.enabled }.mapNotNull { m ->
                    val t = m.times.firstOrNull { it.enabled && it.hour == hour && it.minute == minute }
                    if (t != null) m else null
                }
                if (sameTimeMeds.isNotEmpty()) {
                    medCount = sameTimeMeds.size
                    // Объединяем названия всех препаратов на это время: "Лозартан (50 мг) + Амлодипин (5 мг)"
                    finalName = sameTimeMeds.joinToString(" + ") { m ->
                        if (m.dose.isNotBlank()) "${m.name} (${m.dose})" else m.name
                    }
                    finalDose = ""
                }
            }

            when (type) {
                "med" -> {
                    // Звучит строго один раз на это время
                    Notifications.showMed(context, id, finalName, finalDose, hour, minute)
                    Notifications.playMedSignal(context)
                    hold = true
                    // Планируем постоянное расписание (единый будильник на каждую точку времени)
                    ReminderScheduler.rescheduleAllMeds(context)
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
                    Notifications.playMedSignal(context)
                    hold = true
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
                    name = finalName,
                    dose = finalDose,
                    medCount = medCount
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
    companion object {
        private val alarmLock = Any()
        @Volatile private var lastTriggerTime: Long = 0L
        @Volatile private var lastTriggerSlot: String = ""
    }
}