package com.arena.bpdiary

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.util.Log

/**
 * На старте снимаем старые уведомления: битая иконка в шторке на части
 * прошивок убивает процесс сразу после запуска. Если падение всё же
 * случится, текст ошибки открывается отдельным процессом и не закрывается.
 */
class DiaryApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            Notifications.ensureChannel(this)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancelAll()
        } catch (_: Exception) {
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val crashProcess = Build.VERSION.SDK_INT >= 28 &&
                Application.getProcessName().endsWith(":crash")
            if (!crashProcess && thread == Looper.getMainLooper().thread) {
                try {
                    startActivity(
                        Intent(this, CrashActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            .putExtra("trace", Log.getStackTraceString(error).take(4000))
                    )
                    return@setDefaultUncaughtExceptionHandler
                } catch (_: Exception) {
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
