package com.arena.bpdiary

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Планировщик будильников: напоминания об измерениях и приёмах таблеток.
 * Все будильники живут в AlarmManager с уникальными id:
 *  - id напоминания об измерении = Reminder.id
 *  - id будильника приёма таблетки = MedTime.id
 */
object ReminderScheduler {

    private const val EXTRA_TYPE = "type"
    private const val EXTRA_ID = "id"
    private const val EXTRA_HOUR = "hour"
    private const val EXTRA_MINUTE = "minute"
    private const val EXTRA_NAME = "name"
    private const val EXTRA_DOSE = "dose"

    private const val TYPE_MEASURE = "measure"
    private const val TYPE_MED = "med"

    private fun pi(ctx: Context, alarmId: Int, intent: Intent): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, alarmId, intent, flags)
    }

    private fun scheduleAt(ctx: Context, alarmId: Int, hour: Int, minute: Int, intent: Intent) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (before(java.util.Calendar.getInstance())) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val pending = pi(ctx, alarmId, intent)
        try {
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending)
            }
        } catch (_: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending)
            } catch (_: Exception) {
                // прошивка запретила будильник — приложение должно остаться открытым
            }
        } catch (_: Exception) {
        }
    }

    // ---------- Измерения ----------

    fun schedule(ctx: Context, r: Reminder) {
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .putExtra(EXTRA_TYPE, TYPE_MEASURE)
            .putExtra(EXTRA_ID, r.id)
            .putExtra(EXTRA_HOUR, r.hour)
            .putExtra(EXTRA_MINUTE, r.minute)
        scheduleAt(ctx, r.id, r.hour, r.minute, intent)
    }

    fun cancel(ctx: Context, alarmId: Int) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, AlarmReceiver::class.java)
            am.cancel(pi(ctx, alarmId, intent))
        } catch (_: Exception) {
        }
    }

    fun rescheduleAllMeasures(ctx: Context) {
        Store(ctx).reminders().filter { it.enabled }.forEach { schedule(ctx, it) }
    }

    // ---------- Таблетки ----------

    fun scheduleMedSlot(ctx: Context, slotId: Int, hour: Int, minute: Int, name: String, dose: String) {
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .putExtra(EXTRA_TYPE, TYPE_MED)
            .putExtra(EXTRA_ID, slotId)
            .putExtra(EXTRA_HOUR, hour)
            .putExtra(EXTRA_MINUTE, minute)
            .putExtra(EXTRA_NAME, name)
            .putExtra(EXTRA_DOSE, dose)
        scheduleAt(ctx, slotId, hour, minute, intent)
    }

    fun scheduleMedAll(ctx: Context, med: Med) {
        if (!med.enabled) return
        med.times.filter { it.enabled }.forEach { t ->
            scheduleMedSlot(ctx, t.id, t.hour, t.minute, med.name, med.dose)
        }
    }

    fun cancelMed(ctx: Context, med: Med) {
        med.times.forEach { cancel(ctx, it.id) }
    }

    fun rescheduleAllMeds(ctx: Context) {
        Store(ctx).meds().forEach { scheduleMedAll(ctx, it) }
    }

    // ---------- Общее ----------

    fun cancelAll(ctx: Context) {
        val store = Store(ctx)
        store.reminders().forEach { cancel(ctx, it.id) }
        store.meds().forEach { med -> med.times.forEach { cancel(ctx, it.id) } }
    }

    fun rescheduleAll(ctx: Context) {
        rescheduleAllMeasures(ctx)
        rescheduleAllMeds(ctx)
    }

    // ---------- Откладывание напоминаний ----------

    const val EXTRA_SNOOZE = "snooze"

    fun scheduleSnooze(
        ctx: Context,
        type: String,
        id: Int,
        hour: Int,
        minute: Int,
        name: String = "",
        dose: String = "",
        delayMinutes: Int = 30
    ) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerTime = System.currentTimeMillis() + delayMinutes * 60 * 1000L
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_HOUR, hour)
            putExtra(EXTRA_MINUTE, minute)
            putExtra(EXTRA_NAME, name)
            putExtra(EXTRA_DOSE, dose)
            putExtra(EXTRA_SNOOZE, true)
        }
        // Уникальный request code для отложенного будильника, чтобы не затереть регулярный суточный
        val snoozeAlarmId = if (id != 0) -kotlin.math.abs(id) else -9999
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getBroadcast(ctx, snoozeAlarmId, intent, flags)
        try {
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pending)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pending)
            }
        } catch (_: Exception) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pending)
            } catch (_: Exception) {
            }
        }
    }

}
