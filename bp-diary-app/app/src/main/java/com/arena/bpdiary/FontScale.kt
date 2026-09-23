package com.arena.bpdiary

import android.content.Context
import android.content.res.Configuration

/** Размер шрифта внутри приложения. Не уменьшает системный шрифт телефона. */
object FontScale {

    private const val PREFS = "bp_ui"
    private const val KEY = "font_step"

    val steps = floatArrayOf(1.0f, 1.15f, 1.30f, 1.45f)
    val labelIds = intArrayOf(
        R.string.font_normal,
        R.string.font_larger,
        R.string.font_large,
        R.string.font_xlarge
    )

    fun step(ctx: Context): Int {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0)
        return raw.coerceIn(0, steps.lastIndex)
    }

    fun setStep(ctx: Context, step: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY, step.coerceIn(0, steps.lastIndex))
            .commit()
    }

    fun wrap(base: Context): Context {
        val chosen = steps[step(base)]
        val config = Configuration(base.resources.configuration)
        config.fontScale = maxOf(config.fontScale, chosen)
        return base.createConfigurationContext(config)
    }
}
