package com.arena.bpdiary

import android.content.Context
import androidx.annotation.ColorRes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BpClassifier {

    data class Category(val label: String, @ColorRes val colorRes: Int)

    /**
     * Офисные категории ESH 2023.
     * Криз — только если диастолическое выше 120 (в том числе вместе с систолическим выше 180).
     * Изолированное систолическое 181–219 при диастоле ≤ 120 — это 3-я степень, не криз.
     */
    fun classify(ctx: Context, sys: Int, dia: Int): Category {
        return when {
            dia > 120 -> Category(ctx.getString(R.string.cat_crisis), R.color.catCrisis)
            sys >= 180 || dia >= 110 -> Category(ctx.getString(R.string.cat_g3), R.color.catG3)
            sys >= 160 || dia >= 100 -> Category(ctx.getString(R.string.cat_g2), R.color.catG2)
            sys >= 140 || dia >= 90  -> Category(ctx.getString(R.string.cat_g1), R.color.catG1)
            sys >= 130 || dia >= 85  -> Category(ctx.getString(R.string.cat_high_normal), R.color.catHighNormal)
            sys >= 120 || dia >= 80  -> Category(ctx.getString(R.string.cat_normal), R.color.catNormal)
            else                     -> Category(ctx.getString(R.string.cat_optimal), R.color.catOptimal)
        }
    }

    /** null, если пульс ни разу не вводили (0 в записи — «не измеряли», не ноль ударов). */
    fun pulseAvg(records: List<BpRecord>): Int? {
        val pulses = records.map { it.pulse }.filter { it > 0 }
        return if (pulses.isEmpty()) null else pulses.average().toInt()
    }
}

object CsvExporter {

    fun build(ctx: Context, records: List<BpRecord>): String {
        val df = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val sb = StringBuilder("Дата;Систолическое;Диастолическое;Пульс;Категория;Заметка\n")
        records.forEach { r ->
            val cat = BpClassifier.classify(ctx, r.sys, r.dia).label
            sb.append(df.format(Date(r.time)))
                .append(';').append(r.sys)
                .append(';').append(r.dia)
                .append(';').append(r.pulse)
                .append(";\"").append(cat).append('"')
                .append(";\"").append(r.note.replace("\"", "'")).append('"')
                .append('\n')
        }
        return sb.toString()
    }
}
