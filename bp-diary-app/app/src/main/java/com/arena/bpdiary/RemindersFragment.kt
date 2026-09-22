package com.arena.bpdiary

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.arena.bpdiary.databinding.FragmentRemindersBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Calendar

class RemindersFragment : Fragment() {

    private var _b: FragmentRemindersBinding? = null
    private val b get() = _b!!
    private val store by lazy { Store(requireContext()) }
    private lateinit var adapter: RemindersAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentRemindersBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = RemindersAdapter(
            items = emptyList(),
            onClickToggle = { r, enabled ->
                val updated = r.copy(enabled = enabled)
                store.updateReminder(updated)
                if (enabled) ReminderScheduler.schedule(requireContext(), updated)
                else ReminderScheduler.cancel(requireContext(), updated.id)
                refresh()
            },
            onLongClick = { r -> confirmDelete(r) }
        )
        b.reminders.layoutManager = LinearLayoutManager(requireContext())
        b.reminders.adapter = adapter
        b.fabAddReminder.setOnClickListener { pickTime() }
        b.btnExactSettings.setOnClickListener { openExactAlarmSettings() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = store.reminders()
        adapter.submit(list)
        b.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val am = context?.getSystemService(android.content.Context.ALARM_SERVICE) as? android.app.AlarmManager
        val exactOk = try {
            Build.VERSION.SDK_INT < 31 || am?.canScheduleExactAlarms() == true
        } catch (_: Exception) {
            false
        }
        b.cardExact.visibility = if (exactOk) View.GONE else View.VISIBLE
    }

    private fun confirmDelete(r: Reminder) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_record_title)
            .setMessage(R.string.delete_confirm_reminder)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.deleteReminder(r.id)
                ReminderScheduler.cancel(requireContext(), r.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openExactAlarmSettings() {
        val pkg = Uri.parse("package:${requireContext().packageName}")
        val intents = listOf(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // на части прошивок отдельного экрана точных будильников нет
            }
        }
        Toast.makeText(requireContext(), R.string.exact_open_fail, Toast.LENGTH_LONG).show()
    }

    private fun pickTime() {
        val now = Calendar.getInstance()
        val tp = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(now.get(Calendar.HOUR_OF_DAY))
            .setMinute(now.get(Calendar.MINUTE))
            .setTitleText(R.string.pick_time)
            .build()
        tp.addOnPositiveButtonClickListener {
            val r = store.addReminder(tp.hour, tp.minute, getString(R.string.reminder_label))
            ReminderScheduler.schedule(requireContext(), r)
            refresh()
        }
        tp.show(childFragmentManager, "tp")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
