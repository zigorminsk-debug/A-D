package com.arena.bpdiary

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Кастомный график АД для вкладки «График». */
class BpChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var records: List<BpRecord> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val night = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        BpChartRenderer.draw(
            canvas,
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            records,
            labels = true,
            dark = night,
            fontScale = resources.displayMetrics.scaledDensity
        )
    }
}
