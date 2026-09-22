package com.arena.bpdiary

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.arena.bpdiary.databinding.DialogRecordBinding
import com.arena.bpdiary.databinding.FragmentDiaryBinding
import com.arena.bpdiary.databinding.ItemTodayDoseBinding
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DiaryFragment : Fragment() {

    private var _b: FragmentDiaryBinding? = null
    private val b get() = _b!!
    private val store by lazy { Store(requireContext()) }
    private lateinit var adapter: RecordsAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentDiaryBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = RecordsAdapter(emptyList(), onClick = { openDialog(it) }, onLongClick = { confirmDelete(it) })
        b.records.layoutManager = LinearLayoutManager(requireContext())
        b.records.adapter = adapter
        b.fabAdd.setOnClickListener { openDialog(null) }
        b.btnExport.setOnClickListener { export() }
        b.btnPdf.setOnClickListener { exportPdf() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (_b != null) refresh()
    }

    private fun refresh() {
        try {
            val recs = store.records()
            adapter.submit(recs)
            b.emptyState.visibility = if (recs.isEmpty()) View.VISIBLE else View.GONE
            updateStats(recs)
            updateToday(recs)
            WidgetProvider.updateAll(requireContext())
        } catch (_: Exception) {
            b.tvStats.text = getString(R.string.stats_empty)
            b.cardToday.visibility = View.GONE
        }
    }

    private fun updateToday(recs: List<BpRecord>) {
        try {
            val day = TodayPlan.build(recs, store.meds(), store.logs())
            b.cardToday.visibility = View.VISIBLE
            bindPeriod(b.tvTodayMorning, day.morning, morning = true)
            bindPeriod(b.tvTodayEvening, day.evening, morning = false)
            b.todayDoses.removeAllViews()
            day.dueDoses.forEach { dose ->
                val row = ItemTodayDoseBinding.inflate(layoutInflater, b.todayDoses, false)
                val hm = String.format(Locale.getDefault(), "%02d:%02d", dose.hour, dose.minute)
                val label = if (dose.dose.isBlank()) dose.name else "${dose.name}, ${dose.dose}"
                row.tvDose.text = getString(R.string.today_dose_fmt, hm, label)
                row.btnTaken.setOnClickListener { markDose(dose, "taken") }
                row.btnSkip.setOnClickListener { markDose(dose, "skipped") }
                b.todayDoses.addView(row.root)
            }
            when {
                day.dueDoses.isNotEmpty() -> b.tvTodayMeds.visibility = View.GONE
                day.allDosesDone -> {
                    b.tvTodayMeds.visibility = View.VISIBLE
                    b.tvTodayMeds.text = getString(R.string.today_meds_done)
                }
                day.nextDoses.isNotEmpty() -> {
                    b.tvTodayMeds.visibility = View.VISIBLE
                    b.tvTodayMeds.text = day.nextDoses.joinToString("\n") { next ->
                        val hm = String.format(Locale.getDefault(), "%02d:%02d", next.hour, next.minute)
                        val label = if (next.dose.isBlank()) next.name else "${next.name}, ${next.dose}"
                        getString(R.string.today_next_dose, hm, label)
                    }
                }
                else -> b.tvTodayMeds.visibility = View.GONE
            }
            capTodayCard()
        } catch (_: Exception) {
            b.cardToday.visibility = View.GONE
        }
    }

    /** Иначе длинный список приёмов вытесняет измерения за край экрана. */
    private fun capTodayCard() {
        val scroll = b.todayScroll
        val lp = scroll.layoutParams
        if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            scroll.layoutParams = lp
        }
        scroll.post {
            if (_b == null) return@post
            val content = scroll.getChildAt(0)?.measuredHeight ?: return@post
            val max = (resources.displayMetrics.heightPixels * 0.46f).toInt()
            val target = content.coerceAtMost(max)
            if (target > 0 && scroll.layoutParams.height != target) {
                scroll.layoutParams.height = target
                scroll.requestLayout()
            }
        }
    }

    private fun bindPeriod(view: android.widget.TextView, period: TodayPlan.Period, morning: Boolean) {
        val hm = period.latest?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.time)) }
        view.text = when (period.state) {
            TodayPlan.State.DONE -> {
                val r = period.latest ?: return
                if (period.count > 1) {
                    getString(
                        if (morning) R.string.today_morning_done_more else R.string.today_evening_done_more,
                        r.sys, r.dia, hm, period.count
                    )
                } else {
                    getString(
                        if (morning) R.string.today_morning_done else R.string.today_evening_done,
                        r.sys, r.dia, hm
                    )
                }
            }
            TodayPlan.State.DUE -> getString(if (morning) R.string.today_morning_due else R.string.today_evening_due)
            TodayPlan.State.EARLY -> getString(if (morning) R.string.today_morning_early else R.string.today_evening_early)
            TodayPlan.State.MISSED -> getString(if (morning) R.string.today_morning_missed else R.string.today_evening_missed)
        }
        val color = when (period.state) {
            TodayPlan.State.DONE -> R.color.catOptimal
            TodayPlan.State.DUE -> R.color.primary
            else -> R.color.gray
        }
        view.setTextColor(ContextCompat.getColor(requireContext(), color))
    }

    private fun markDose(dose: TodayPlan.Dose, status: String) {
        val med = store.meds().firstOrNull { it.id == dose.medId } ?: return
        store.addLog(med.id, med.name, med.dose, status, dose.hour, dose.minute)
        refresh()
    }

    private fun updateStats(recs: List<BpRecord>) {
        if (recs.isEmpty()) {
            b.tvStats.text = getString(R.string.stats_empty)
            return
        }
        val week = recs.filter { it.time >= System.currentTimeMillis() - 7L * 86_400_000 }
        val avg = { l: List<BpRecord>, f: (BpRecord) -> Int ->
            if (l.isEmpty()) 0 else l.map(f).average().toInt()
        }
        val s7 = avg(week) { it.sys }
        val d7 = avg(week) { it.dia }
        val p7 = BpClassifier.pulseAvg(week)
        val sb = StringBuilder()
        sb.append(getString(R.string.stats_line_counts, recs.size, week.size)).append('\n')
        if (week.isNotEmpty()) {
            if (p7 == null) sb.append(getString(R.string.stats_line_avg_no_pulse, s7, d7))
            else sb.append(getString(R.string.stats_line_avg, s7, d7, p7))
            sb.append('\n')
        }
        if (week.size >= 2) {
            val hourOf = { t: Long ->
                java.util.Calendar.getInstance().apply { timeInMillis = t }.get(java.util.Calendar.HOUR_OF_DAY)
            }
            val mo = week.filter { hourOf(it.time) in 5..11 }
            val ev = week.filter { hourOf(it.time) in 17..22 }
            if (mo.isNotEmpty() && ev.isNotEmpty()) {
                sb.append(
                    getString(
                        R.string.stats_line_me,
                        avg(mo) { it.sys }, avg(mo) { it.dia }, avg(ev) { it.sys }, avg(ev) { it.dia }
                    )
                ).append('\n')
            }
        }
        val last = recs.first()
        val df = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        sb.append(getString(
            R.string.stats_line_last,
            df.format(Date(last.time)),
            last.sys, last.dia,
            BpClassifier.classify(requireContext(), last.sys, last.dia).label
        ))
        b.tvStats.text = sb.toString()
    }

    private fun openDialog(edit: BpRecord?) {
        val db = DialogRecordBinding.inflate(layoutInflater)
        var whenMillis = edit?.time ?: System.currentTimeMillis()
        val whenFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        fun showWhen() {
            db.btnWhen.text = getString(R.string.record_when_fmt, whenFmt.format(Date(whenMillis)))
        }
        showWhen()
        db.btnWhen.setOnClickListener {
            if (childFragmentManager.findFragmentByTag("record-date") != null) return@setOnClickListener
            pickWhen(whenMillis) {
                whenMillis = it
                showWhen()
            }
        }
        edit?.let {
            db.etSys.setText(it.sys.toString())
            db.etDia.setText(it.dia.toString())
            db.etPulse.setText(if (it.pulse > 0) it.pulse.toString() else "")
            db.etNote.setText(it.note)
        }
        val d = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (edit == null) R.string.add_record else R.string.edit_record)
            .setView(db.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val sys = db.etSys.text?.toString()?.toIntOrNull()
            val dia = db.etDia.text?.toString()?.toIntOrNull()
            val pulse = db.etPulse.text?.toString()?.toIntOrNull() ?: 0
            val note = db.etNote.text?.toString()?.trim() ?: ""

            var ok = true
            if (sys == null || sys !in 60..300) { db.etSys.error = getString(R.string.err_range_sys); ok = false }
            if (dia == null || dia !in 30..200) { db.etDia.error = getString(R.string.err_range_dia); ok = false }
            if (pulse != 0 && pulse !in 25..250) { db.etPulse.error = getString(R.string.err_range_pulse); ok = false }
            if (sys != null && dia != null && sys <= dia) { db.etDia.error = getString(R.string.err_sys_lt_dia); ok = false }
            if (whenMillis > System.currentTimeMillis() + 2L * 60L * 1000L) {
                Toast.makeText(requireContext(), R.string.err_future, Toast.LENGTH_LONG).show()
                ok = false
            }
            if (!ok) return@setOnClickListener

            if (edit == null) store.addRecord(sys!!, dia!!, pulse, note, whenMillis)
            else store.updateRecord(BpRecord(edit.id, whenMillis, sys!!, dia!!, pulse, note))
            refresh()
            d.dismiss()
        }
    }

    /** Сначала дата, потом время. Календарь Material считает сутки по UTC — переводим в местные. */
    private fun pickWhen(start: Long, onPicked: (Long) -> Unit) {
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.pick_date)
            .setSelection(utcMidnight(start))
            .build()
        datePicker.addOnPositiveButtonClickListener { utc ->
            val withDate = applyUtcDate(start, utc)
            val cal = Calendar.getInstance().apply { timeInMillis = withDate }
            val timePicker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(cal.get(Calendar.HOUR_OF_DAY))
                .setMinute(cal.get(Calendar.MINUTE))
                .setTitleText(R.string.pick_time)
                .build()
            timePicker.addOnPositiveButtonClickListener {
                onPicked(applyTime(withDate, timePicker.hour, timePicker.minute))
            }
            if (isAdded) timePicker.show(childFragmentManager, "record-time")
        }
        datePicker.show(childFragmentManager, "record-date")
    }

    private fun utcMidnight(localMillis: Long): Long {
        val local = Calendar.getInstance().apply { timeInMillis = localMillis }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun applyUtcDate(localMillis: Long, utcSelection: Long): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcSelection }
        return Calendar.getInstance().apply {
            timeInMillis = localMillis
            set(Calendar.YEAR, utc.get(Calendar.YEAR))
            set(Calendar.MONTH, utc.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    private fun applyTime(localMillis: Long, hour: Int, minute: Int): Long {
        return Calendar.getInstance().apply {
            timeInMillis = localMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun confirmDelete(r: BpRecord) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_record_title)
            .setMessage(R.string.delete_confirm_record)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.deleteRecord(r.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun export() {
        val csv = CsvExporter.build(requireContext(), store.records())
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_subject))
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(i, getString(R.string.export_chooser)))
    }

    private fun exportPdf() {
        val recs = store.records()
        if (recs.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), R.string.pdf_no_data, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val f = PdfExporter.build(requireContext(), recs) ?: return
        val uri = androidx.core.content.FileProvider.getUriForFile(
            requireContext(), "${requireContext().packageName}.fileprovider", f
        )
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.pdf_subject))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, getString(R.string.pdf_chooser)))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
