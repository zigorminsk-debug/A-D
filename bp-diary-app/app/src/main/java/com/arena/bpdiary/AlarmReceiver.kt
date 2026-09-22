package com.arena.bpdiary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        try {
            val id = intent.getIntExtra("id", 0)
            val hour = intent.getIntExtra("hour", 8)
            val minute = intent.getIntExtra("minute", 0)
            val type = intent.getStringExtra("type") ?: "measure"

            when (type) {
                "med" -> {
                    val name = intent.getStringExtra("name") ?: ""
                    val dose = intent.getStringExtra("dose") ?: ""
                    Notifications.showMed(context, id, name, dose)
                    ReminderScheduler.scheduleMedSlot(context, id, hour, minute, name, dose)
                }
                else -> {
                    Notifications.show(
                        context,
                        id,
                        context.getString(R.string.notify_title),
                        context.getString(R.string.notify_text)
                    )
                    ReminderScheduler.schedule(context, Reminder(id, hour, minute, true, ""))
                }
            }
        } catch (_: Exception) {
            // нет разрешения на уведомления или сбой прошивки — не закрываем приложение
        }
    }
}
