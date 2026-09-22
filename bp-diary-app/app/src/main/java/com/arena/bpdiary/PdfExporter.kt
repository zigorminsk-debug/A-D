package com.arena.bpdiary

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Генератор PDF-отчёта для врача: сводка, график, таблица измерений.
 * Длинная таблица продолжается на следующих страницах и не залезает на подвал.
 */
object PdfExporter {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val M = 40f
    private const val BOTTOM = 786f

    fun build(context: Context, recordsDesc: List<BpRecord>, periodNote: String? = null): File? {
        if (recordsDesc.isEmpty()) return null
        val asc = recordsDesc.sortedBy { it.time }

        val doc = PdfDocument()
        var pageIndex = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageIndex).create())
        var c = page.canvas
        var y = 48f

        val dfFull = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val navy = 0xFF1F4E79.toInt()
        val gray = 0xFF66707A.toInt()
        val light = 0xFFF2F7FC.toInt()
        val line = 0xFFB9C6D4.toInt()

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = navy
            textSize = 16f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gray; textSize = 8.5f }
        val h2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = navy
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1A.toInt(); textSize = 9.5f }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gray; textSize = 8f }
        val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line; strokeWidth = 0.7f }
        val hdrBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = navy }
        val hdrTx = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 9f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val rowAlt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = light }

        fun footer() {
            c.drawLine(M, PAGE_H - 36f, PAGE_W - M, PAGE_H - 36f, thin)
            c.drawText(
                "Справочно, не заменяет врача. Категории — офисные значения ESH 2023. Стр. $pageIndex",
                M, PAGE_H - 22f, sub
            )
        }

        fun newPage() {
            footer()
            doc.finishPage(page)
            pageIndex++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageIndex).create())
            c = page.canvas
            y = 48f
            c.drawText("Дневник АД — продолжение", M, y, h2)
            y += 18f
        }

        fun ensure(need: Float) {
            if (y + need > BOTTOM) newPage()
        }

        c.drawText("Дневник артериального давления — отчёт", M, y, title)
        y += 16f
        val period = if (periodNote.isNullOrBlank()) "все измерения" else periodNote
        c.drawText(
            "Сформирован: ${dfFull.format(Date())} • $period • приложение «Дневник АД»",
            M, y, sub
        )
        y += 12f
        c.drawLine(M, y, PAGE_W - M, y, thin)
        y += 20f

        val patient = Paint(body).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        c.drawText("Пациент:", M, y, patient)
        c.drawLine(M + 52f, y + 2f, M + 250f, y + 2f, thin)
        c.drawText("Дата отчёта:", M + 270f, y, patient)
        c.drawLine(M + 350f, y + 2f, PAGE_W - M, y + 2f, thin)
        y += 22f

        c.drawText("Сводка", M, y, h2)
        y += 16f

        val all = recordsDesc
        val week = all.filter { it.time >= System.currentTimeMillis() - 7L * 86_400_000 }
        fun avg(l: List<BpRecord>, f: (BpRecord) -> Int): Int =
            if (l.isEmpty()) 0 else l.map(f).average().toInt()
        fun pulseText(l: List<BpRecord>): String {
            val p = BpClassifier.pulseAvg(l)
            return if (p == null) "пульс не указан" else "пульс $p"
        }
        val last = all.first()
        val lastCat = BpClassifier.classify(context, last.sys, last.dia).label
        val lines = mutableListOf(
            "Измерений в отчёте: ${all.size} • за последние 7 дней: ${week.size}",
            "Среднее за период отчёта: ${avg(all) { it.sys }}/${avg(all) { it.dia }} мм рт.ст. • ${pulseText(all)}"
        )
        if (week.isNotEmpty()) {
            lines.add("Среднее за 7 дней: ${avg(week) { it.sys }}/${avg(week) { it.dia }} мм рт.ст. • ${pulseText(week)}")
        }
        if (week.size >= 2) {
            val hourOf = { t: Long ->
                java.util.Calendar.getInstance().apply { timeInMillis = t }.get(java.util.Calendar.HOUR_OF_DAY)
            }
            val mo = week.filter { hourOf(it.time) in 5..11 }
            val ev = week.filter { hourOf(it.time) in 17..22 }
            if (mo.isNotEmpty() && ev.isNotEmpty()) {
                lines.add(
                    "Средние утро/вечер (7 дн): ${avg(mo) { it.sys }}/${avg(mo) { it.dia }} • ${avg(ev) { it.sys }}/${avg(ev) { it.dia }}"
                )
            }
        }
        lines.add("Последнее: ${dfFull.format(Date(last.time))} — ${last.sys}/${last.dia} ($lastCat)")
        lines.add("Цель по гайдлайнам: ниже 130/80 мм рт.ст.; дома — ниже 135/85")
        lines.forEach {
            ensure(14f)
            c.drawText(it, M, y, body)
            y += 14f
        }

        ensure(210f)
        y += 8f
        c.drawText("Динамика давления", M, y, h2)
        val chartTop = y + 8f
        val chartH = 170f
        BpChartRenderer.draw(
            c,
            RectF(M, chartTop, PAGE_W - M, chartTop + chartH),
            asc,
            labels = true
        )
        y = chartTop + chartH + 16f

        val legend = listOf(
            navy to "Систолическое",
            BpChartRenderer.BLUE to "Диастолическое",
            BpChartRenderer.TARGET to "Цель 130 / 80"
        )
        var lx = M
        legend.forEach { (color, text) ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            c.drawRect(lx, y - 7f, lx + 14f, y - 2f, p)
            c.drawText(text, lx + 18f, y, small)
            lx += 18f + small.measureText(text) + 14f
        }
        y += 20f

        fun drawTableHeader() {
            ensure(20f)
            c.drawRect(M, y, PAGE_W - M, y + 18f, hdrBg)
            c.drawText("Дата и время", M + 4f, y + 12.5f, hdrTx)
            c.drawText("АД", M + 156f, y + 12.5f, hdrTx)
            c.drawText("Пульс", M + 214f, y + 12.5f, hdrTx)
            c.drawText("Категория", M + 268f, y + 12.5f, hdrTx)
            y += 18f
        }

        ensure(40f)
        c.drawText("Измерения", M, y, h2)
        y += 8f
        drawTableHeader()

        val rowH = 16f
        all.forEachIndexed { i, r ->
            if (y + rowH > BOTTOM) {
                newPage()
                drawTableHeader()
            }
            if (i % 2 == 1) c.drawRect(M, y, PAGE_W - M, y + rowH, rowAlt)
            val cat = BpClassifier.classify(context, r.sys, r.dia).label
            c.drawText(dfFull.format(Date(r.time)), M + 4f, y + 11.5f, body)
            c.drawText("${r.sys}/${r.dia}", M + 156f, y + 11.5f, body)
            c.drawText(if (r.pulse > 0) r.pulse.toString() else "—", M + 214f, y + 11.5f, body)
            c.drawText(cat, M + 268f, y + 11.5f, body)
            c.drawLine(M, y + rowH, PAGE_W - M, y + rowH, thin)
            y += rowH
        }

        footer()
        doc.finishPage(page)

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "BP_report_$ts.pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }
}
