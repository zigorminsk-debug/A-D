package com.arena.bpdiary

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.util.Log

/**
 * Если старт всё же падает, показываем текст ошибки вместо системного «приложение остановлено».
 * Экран ошибки в отдельном процессе, чтобы его не убило вместе с упавшим.
 */
class DiaryApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val crashProcess = Build.VERSION.SDK_INT >= 28 &&
                Application.getProcessName().endsWith(":crash")
            val main = thread == Looper.getMainLooper().thread
            if (!crashProcess && main) {
                try {
                    val i = Intent(this, CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra("trace", Log.getStackTraceString(error).take(4000))
                    startActivity(i)
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return@setDefaultUncaughtExceptionHandler
                } catch (_: Exception) {
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
