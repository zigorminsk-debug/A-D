package com.arena.bpdiary

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arena.bpdiary.databinding.DialogMedBinding
import com.arena.bpdiary.databinding.FragmentMedsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MedsFragment : Fragment() {

    private var _b: FragmentMedsBinding? = null
    private val b get() = _b!!
    private val store by lazy { Store(requireContext()) }
    private lateinit var medsAdapter: MedsAdapter
    private lateinit var logsAdapter: LogsAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentMedsBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        medsAdapter = MedsAdapter(
            items = emptyList(),
            onToggle = { med, enabled ->
                val updated = med.copy(enabled = enabled)
                store.updateMed(updated)
                if (enabled) ReminderScheduler.scheduleMedAll(requireContext(), updated)
                else ReminderScheduler.cancelMed(requireContext(), med)
                refresh()
            },
            onTap = { med -> showActions(med) },
            onLong = { med -> confirmDelete(med) }
        )
        b.medsList.layoutManager = LinearLayoutManager(requireContext())
        b.medsList.adapter = medsAdapter
        b.medsList.isNestedScrollingEnabled = false

        logsAdapter = LogsAdapter(
            items = emptyList(),
            onLong = { log ->
            store.deleteLog(log.id)
            refresh()
        })
        b.logsList.layoutManager = LinearLayoutManager(requireContext())
        b.logsList.adapter = logsAdapter
        b.logsList.isNestedScrollingEnabled = false

        b.fabAddMed.setOnClickListener { showMedDialog(null) }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val meds = store.meds()
        medsAdapter.submit(meds)
        b.emptyMeds.visibility = if (meds.isEmpty()) View.VISIBLE else View.GONE

        val logs = store.logs().take(20)
        logsAdapter.submit(logs)
        b.logsList.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
        b.historyTitle.visibility = b.logsList.visibility
        b.emptyLogs.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showActions(med: Med) {
        val items = arrayOf(
            getString(R.string.mark_taken),
            getString(R.string.mark_skipped),
            getString(R.string.med_edit),
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (med.dose.isBlank()) med.name else "${med.name} (${med.dose})")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { store.addLog(med.id, med.name, med.dose, "taken"); refresh() }
                    1 -> { store.addLog(med.id, med.name, med.dose, "skipped"); refresh() }
                    2 -> showMedDialog(med)
                    3 -> confirmDelete(med)
                }
            }
            .show()
    }

    private fun confirmDelete(med: Med) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_record_title)
            .setMessage(R.string.med_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                ReminderScheduler.cancelMed(requireContext(), med)
                store.deleteMed(med.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- Диалог препарата ----------

    private fun showMedDialog(edit: Med?) {
        val d = DialogMedBinding.inflate(layoutInflater)
        val times = ArrayList<MedTime>()

        edit?.let {
            d.etMedName.setText(it.name)
            d.etMedDose.setText(it.dose)
            times.addAll(it.times)
        }

        fun renderTimes() {
            d.timesContainer.removeAllViews()
            times.forEach { t ->
                val tv = TextView(requireContext()).apply {
                    text = String.format("%02d:%02d   ✕", t.hour, t.minute)
                    textSize = 15f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
                    setPadding(0, 12, 0, 12)
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener {
                        times.remove(t)
                        renderTimes()
                    }
                }
                d.timesContainer.addView(tv)
            }
            if (times.isEmpty()) {
                val hint = TextView(requireContext()).apply {
                    text = getString(R.string.med_no_times)
                    textSize = 13f
                    setTextColor(Color.GRAY)
                    setPadding(0, 8, 0, 8)
                }
                d.timesContainer.addView(hint)
            }
        }
        renderTimes()

        d.btnAddTime.setOnClickListener {
            val now = java.util.Calendar.getInstance()
            val tp = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(now.get(java.util.Calendar.HOUR_OF_DAY))
                .setMinute(now.get(java.util.Calendar.MINUTE))
                .setTitleText(R.string.pick_time)
                .build()
            tp.addOnPositiveButtonClickListener {
                times.add(MedTime(0, tp.hour, tp.minute))
                times.sortWith(compareBy({ it.hour }, { it.minute }))
                renderTimes()
            }
            tp.show(childFragmentManager, "tp")
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (edit == null) R.string.add_med else R.string.edit_med)
            .setView(d.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = d.etMedName.text?.toString()?.trim().orEmpty()
            val dose = d.etMedDose.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                d.etMedName.error = getString(R.string.err_empty)
                return@setOnClickListener
            }
            if (times.isEmpty()) {
                Toast.makeText(requireContext(), R.string.med_no_times, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (edit == null) {
                val med = store.addMed(name, dose, times.map { it.hour to it.minute })
                ReminderScheduler.scheduleMedAll(requireContext(), med)
            } else {
                ReminderScheduler.cancelMed(requireContext(), edit)
                val reserved = HashSet<Int>()
                val slots = times.map { t ->
                    val id = if (t.id != 0 && reserved.add(t.id)) t.id
                    else store.allocateId().also { reserved.add(it) }
                    t.copy(id = id)
                }
                val updated = edit.copy(name = name, dose = dose, times = slots)
                store.updateMed(updated)
                ReminderScheduler.scheduleMedAll(requireContext(), updated)
            }
            refresh()
            dialog.dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}

// ================= Адаптеры =================

class MedsAdapter(
    private var items: List<Med>,
    private val onToggle: (Med, Boolean) -> Unit,
    private val onTap: (Med) -> Unit,
    private val onLong: (Med) -> Unit
) : RecyclerView.Adapter<MedsAdapter.VH>() {

    class VH(val b: com.arena.bpdiary.databinding.ItemMedBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = com.arena.bpdiary.databinding.ItemMedBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val m = items[position]
        h.b.tvName.text = m.name
        h.b.tvDose.text = m.dose
        h.b.tvDose.visibility = if (m.dose.isBlank()) View.GONE else View.VISIBLE
        h.b.tvTimes.text = m.times.joinToString(" • ") {
            String.format("%02d:%02d", it.hour, it.minute)
        }
        h.b.tvTimes.visibility = if (m.times.isEmpty()) View.GONE else View.VISIBLE
        h.b.tvName.alpha = if (m.enabled) 1f else 0.45f
        // Сначала снимаем слушатель: иначе при переиспользовании ячейки isChecked
        // вызывает callback предыдущего препарата и гасит чужой будильник.
        h.b.swOn.setOnCheckedChangeListener(null)
        h.b.swOn.isChecked = m.enabled
        h.b.swOn.setOnCheckedChangeListener { _, checked ->
            if (checked != m.enabled) onToggle(m, checked)
        }
        h.b.root.setOnClickListener { onTap(m) }
        h.b.root.setOnLongClickListener { onLong(m); true }
    }

    fun submit(list: List<Med>) {
        items = list
        notifyDataSetChanged()
    }
}

class LogsAdapter(
    private var items: List<MedLog>,
    private val onLong: (MedLog) -> Unit
) : RecyclerView.Adapter<LogsAdapter.VH>() {

    class VH(val b: com.arena.bpdiary.databinding.ItemMedlogBinding) : RecyclerView.ViewHolder(b.root)

    private val df = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = com.arena.bpdiary.databinding.ItemMedlogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val l = items[position]
        val ctx = h.b.root.context
        h.b.tvWhen.text = df.format(Date(l.time))
        h.b.tvWhat.text = if (l.dose.isBlank()) l.medName else "${l.medName} (${l.dose})"
        if (l.status == "taken") {
            h.b.tvStatus.text = ctx.getString(R.string.log_taken_fmt)
            h.b.tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.catOptimal))
        } else {
            h.b.tvStatus.text = ctx.getString(R.string.log_skipped_fmt)
            h.b.tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.catCrisis))
        }
        h.b.root.setOnLongClickListener { onLong(l); true }
    }

    fun submit(list: List<MedLog>) {
        items = list
        notifyDataSetChanged()
    }
}
