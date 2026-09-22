package com.arena.bpdiary

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Экран без темы приложения: остаётся открытым, даже если дневник упал. */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            textSize = 14f
            setTextIsSelectable(true)
            text = "Дневник АД закрылся с ошибкой. Пришлите этот текст:\n\n" +
                (intent?.getStringExtra("trace") ?: "unknown")
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }
}
