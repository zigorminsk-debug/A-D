package com.arena.bpdiary

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Общий отрисовщик графика АД.
 * Используется и во вкладке «График» (BpChartView), и в PDF-отчёте (PdfExporter).
 */
object BpChartRenderer {

    const val NAVY = 0xFF1F4E79.toInt()
    const val BLUE = 0xFF2E74B5.toInt()
    const val GRID = 0xFFB9C6D4.toInt()
    const val TEXT = 0xFF66707A.toInt()
    const val TARGET = 0xFFC8871E.toInt()

    // Ночная палитра (тёмная тема)
    const val NAVY_D = 0xFF7FB3E8.toInt()
    const val BLUE_D = 0xFF9EC9F5.toInt()
    const val GRID_D = 0xFF37465A.toInt()
    const val TEXT_D = 0xFF97A3B0.toInt()

    fun draw(canvas: Canvas, area: RectF, recordsAsc: List<BpRecord>, labels: Boolean, dark: Boolean = false) {
        if (recordsAsc.isEmpty() || area.width() <= 0f || area.height() <= 0f) return

        val cSys = if (dark) NAVY_D else NAVY
        val cDia = if (dark) BLUE_D else BLUE
        val cGrid = if (dark) GRID_D else GRID
        val cText = if (dark) TEXT_D else TEXT

        val padL = if (labels) 34f else 8f
        val padR = 10f
        val padT = 12f
        val padB = if (labels) 24f else 8f
        val chartW = area.width() - padL - padR
        val chartH = area.height() - padT - padB
        if (chartW <= 10f || chartH <= 10f) return

        val minDia = recordsAsc.minOf { it.dia }
        val maxSys = recordsAsc.maxOf { it.sys }
        val yMin = (((minDia - 15) / 10) * 10).coerceAtLeast(50)
        val yMax = (((maxSys + 15 + 9) / 10) * 10).coerceAtMost(220)
        val span = (yMax - yMin).coerceAtLeast(20)

        fun y(v: Int): Float = area.top + padT + chartH * (1f - (v - yMin) / span.toFloat())
        val n = recordsAsc.size
        fun x(i: Int): Float = area.left + padL + if (n == 1) chartW / 2f else chartW * i / (n - 1)

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cGrid; strokeWidth = 0.8f; style = Paint.Style.STROKE
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cText; textSize = if (labels) 9f else 8f
        }
        val dashPaint = Paint(gridPaint).apply {
            color = TARGET; strokeWidth = 1.2f
            pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
        }

        // Сетка + подписи мм рт.ст.
        var v = yMin
        while (v <= yMax) {
            val yy = y(v)
            canvas.drawLine(area.left + padL, yy, area.right - padR, yy, gridPaint)
            if (labels) canvas.drawText(v.toString(), area.left + 2f, yy + 3f, labelPaint)
            v += 20
        }

        // Целевые линии 130/80
        if (130f in yMin.toFloat()..yMax.toFloat()) {
            val yy = y(130)
            canvas.drawLine(area.left + padL, yy, area.right - padR, yy, dashPaint)
        }
        if (80f in yMin.toFloat()..yMax.toFloat()) {
            val yy = y(80)
            canvas.drawLine(area.left + padL, yy, area.right - padR, yy, dashPaint)
        }

        // Линии диастолы (снизу) и систолы (поверх)
        val diaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cDia; strokeWidth = 2f; style = Paint.Style.STROKE
        }
        val sysPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cSys; strokeWidth = 2.4f; style = Paint.Style.STROKE
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        if (n > 1) {
            val pDia = android.graphics.Path()
            val pSys = android.graphics.Path()
            recordsAsc.indices.forEach { i ->
                val r = recordsAsc[i]
                if (i == 0) {
                    pDia.moveTo(x(i), y(r.dia)); pSys.moveTo(x(i), y(r.sys))
                } else {
                    pDia.lineTo(x(i), y(r.dia)); pSys.lineTo(x(i), y(r.sys))
                }
            }
            canvas.drawPath(pDia, diaPaint)
            canvas.drawPath(pSys, sysPaint)
        }

        recordsAsc.indices.forEach { i ->
            val r = recordsAsc[i]
            dotPaint.color = cDia
            canvas.drawCircle(x(i), y(r.dia), if (labels) 2.6f else 2f, dotPaint)
            dotPaint.color = cSys
            canvas.drawCircle(x(i), y(r.sys), if (labels) 3f else 2.3f, dotPaint)
        }

        // Подписи дат: первая / середина / последняя
        if (labels && n > 1) {
            val df = SimpleDateFormat("dd.MM", Locale.getDefault())
            val mid = n / 2
            val pairs = listOf(0 to Paint.Align.LEFT, mid to Paint.Align.CENTER, n - 1 to Paint.Align.RIGHT)
            pairs.forEach { (idx, align) ->
                labelPaint.textAlign = align
                canvas.drawText(
                    df.format(Date(recordsAsc[idx].time)),
                    x(idx), area.bottom - 6f, labelPaint
                )
            }
            labelPaint.textAlign = Paint.Align.LEFT
        }
    }
}
