package com.arena.bpdiary

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Простой экран без темы приложения: открывается, даже если основная тема ломает старт. */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            textSize = 14f
            setTextIsSelectable(true)
            text = intent?.getStringExtra("trace") ?: "unknown"
        }
        setContentView(text)
    }
}
