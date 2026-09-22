package com.arena.bpdiary

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.arena.bpdiary.databinding.DialogRecordBinding
import com.arena.bpdiary.databinding.FragmentDiaryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private fun refresh() {
        val recs = store.records()
        adapter.submit(recs)
        b.emptyState.visibility = if (recs.isEmpty()) View.VISIBLE else View.GONE
        updateStats(recs)
        // обновить виджет на рабочем столе
        WidgetProvider.updateAll(requireContext())
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
            if (!ok) return@setOnClickListener

            if (edit == null) store.addRecord(sys!!, dia!!, pulse, note)
            else store.updateRecord(BpRecord(edit.id, edit.time, sys!!, dia!!, pulse, note))
            refresh()
            d.dismiss()
        }
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
