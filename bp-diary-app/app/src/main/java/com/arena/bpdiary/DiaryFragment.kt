package com.arena.bpdiary

import android.content.Context
import android.content.res.ColorStateList
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlin.math.roundToInt

class DiaryFragment : Fragment() {

    private var _b: FragmentDiaryBinding? = null

    companion object {
        private const val PAIR_GAP_MS = 60_000L
        private const val REST_MS = 5L * 60L * 1000L
    }
    private val b get() = _b!!
    private val store by lazy { Store(requireContext()) }
    private lateinit var adapter: RecordsAdapter
    private val pairHandler = Handler(Looper.getMainLooper())
    private var pairTimer: CountDownTimer? = null
    private var pairTone: ToneGenerator? = null
    private var pairDialog: AlertDialog? = null
    private var afterCallPermission: (() -> Unit)? = null
    private val callPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            val next = afterCallPermission
            afterCallPermission = null
            next?.invoke()
        }
    private var afterLocation: (() -> Unit)? = null
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val next = afterLocation
            afterLocation = null
            next?.invoke()
        }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentDiaryBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = RecordsAdapter(emptyList(), onClick = { openDialog(it) }, onLongClick = { confirmDelete(it) })
        b.records.layoutManager = LinearLayoutManager(requireContext())
        b.records.adapter = adapter
        b.fabAdd.setOnClickListener { openPairDialog() }
        b.btnCall103.setOnClickListener { callAmbulance() }
        b.btnStroke.setOnClickListener { speakStrokeAndCall() }
        b.btnSosData.setOnClickListener {
            Emergency.showDataDialog(this, onClosed = { refreshCallLabel() }, onLocate = { deliver ->
                withLocation { Emergency.locateAndDescribe(requireContext(), deliver) }
            })
        }
        refreshCallLabel()
        if (PlaceFinder.shouldAsk(requireContext())) {
            PlaceFinder.markAsked(requireContext())
            locationPermission.launch(PlaceFinder.PERMISSIONS)
        }
        b.btnPdf.setOnClickListener { exportPdf() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (_b != null) refresh()
    }

    private fun withLocation(then: () -> Unit) {
        if (!isAdded) return
        if (PlaceFinder.hasPermission(requireContext())) {
            then()
        } else {
            afterLocation = then
            locationPermission.launch(PlaceFinder.PERMISSIONS)
        }
    }

    private fun callAmbulance() {
        Emergency.watchPlace(this, stroke = false)
        dialAmbulance()
    }

    private fun dialAmbulance() {
        val act = activity ?: return
        if (!Emergency.placeCall(act)) {
            Toast.makeText(act, getString(R.string.sos_call_fail, Emergency.phone(act)), Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshCallLabel() {
        if (_b == null) return
        b.btnCall103.text = Emergency.callLabel(requireContext())
    }

    private fun speakStrokeAndCall() {
        if (!Emergency.hasAddress(requireContext())) {
            Toast.makeText(requireContext(), R.string.sos_need_address, Toast.LENGTH_LONG).show()
        }
        Emergency.showAndSpeak(this, Emergency.script(requireContext())) { dialAmbulance() }
        Emergency.watchPlace(this, stroke = true)
        dialAmbulance()
    }

    private fun refresh() {
        try {
            val recs = store.records()
            adapter.submit(recs)
            b.emptyState.visibility = if (recs.isEmpty()) View.VISIBLE else View.GONE
            updateStats(recs)
            updateToday(recs)
            refreshCallLabel()
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
            capHeader()
        } catch (_: Exception) {
            b.cardToday.visibility = View.GONE
        }
    }

    /** Поля прокручиваются, кнопки «Дальше» и «Сохранить среднее» остаются на экране. */
    private fun capForm(scroll: View) {
        val scale = resources.configuration.fontScale
        val fraction = if (scale >= 1.3f) 0.34f else 0.46f
        val max = (resources.displayMetrics.heightPixels * fraction).toInt()
        val lp = scroll.layoutParams
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        scroll.layoutParams = lp
        scroll.post {
            if (!isAdded) return@post
            val content = (scroll as? ViewGroup)?.getChildAt(0)?.measuredHeight ?: return@post
            val target = content.coerceAtMost(max)
            if (target > 0 && scroll.layoutParams.height != target) {
                scroll.layoutParams.height = target
                scroll.requestLayout()
            }
        }
    }

    /** Сводка и «Сегодня» прокручиваются, список измерений и «+» остаются на экране. */
    private fun capHeader() {
        val scroll = b.headerScroll
        val scale = resources.configuration.fontScale
        val fraction = if (scale >= 1.3f) 0.46f else 0.55f
        val parentH = (scroll.parent as? View)?.height?.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        val max = (parentH * fraction).toInt()
        val lp = scroll.layoutParams
        if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            scroll.layoutParams = lp
        }
        scroll.post {
            if (_b == null) return@post
            val content = scroll.getChildAt(0)?.measuredHeight ?: return@post
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

    /** Два замера с минутной паузой. В историю пишется среднее, оба числа остаются в заметке. */
    private fun openPairDialog() {
        val db = DialogRecordBinding.inflate(layoutInflater)
        var whenMillis = System.currentTimeMillis()
        var whenTouched = false
        val whenFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        fun showWhen() {
            db.btnWhen.text = getString(R.string.record_when_fmt, whenFmt.format(Date(whenMillis)))
        }
        showWhen()
        db.btnWhen.setOnClickListener {
            if (childFragmentManager.findFragmentByTag("record-date") != null) return@setOnClickListener
            whenTouched = true
            pickWhen(whenMillis) {
                whenMillis = it
                showWhen()
            }
        }

        data class Reading(val sys: Int, val dia: Int, val pulse: Int)
        var first: Reading? = null
        var phase = 0
        var waiting = false

        fun field(edit: View, show: Boolean) {
            val parent = edit.parent as? View
            val box = parent?.parent as? View
            val target = if (box is com.google.android.material.textfield.TextInputLayout) box else parent
            target?.visibility = if (show) View.VISIBLE else View.GONE
        }
        fun showNumbers(show: Boolean) {
            field(db.etSys, show)
            field(db.etDia, show)
            field(db.etPulse, show)
        }

        fun readingOrNull(): Reading? {
            val sys = db.etSys.text?.toString()?.toIntOrNull()
            val dia = db.etDia.text?.toString()?.toIntOrNull()
            val pulse = db.etPulse.text?.toString()?.toIntOrNull() ?: 0
            var ok = true
            if (sys == null || sys !in 60..300) {
                db.etSys.error = getString(R.string.err_range_sys)
                ok = false
            } else db.etSys.error = null
            if (dia == null || dia !in 30..200) {
                db.etDia.error = getString(R.string.err_range_dia)
                ok = false
            } else db.etDia.error = null
            if (pulse != 0 && pulse !in 25..250) {
                db.etPulse.error = getString(R.string.err_range_pulse)
                ok = false
            } else db.etPulse.error = null
            if (sys != null && dia != null && sys <= dia) {
                db.etDia.error = getString(R.string.err_sys_lt_dia)
                ok = false
            }
            if (!ok || sys == null || dia == null) return null
            return Reading(sys, dia, pulse)
        }

        fun preview() {
            val a = first ?: return
            val sys = db.etSys.text?.toString()?.toIntOrNull()
            val dia = db.etDia.text?.toString()?.toIntOrNull()
            db.tvPair.text = if (sys == null || dia == null) {
                getString(R.string.pair_first_fmt, a.sys, a.dia)
            } else {
                getString(R.string.pair_avg_fmt, a.sys, a.dia, mean(a.sys, sys), mean(a.dia, dia))
            }
        }

        val d = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pair_rest_title)
            .setView(db.root)
            .create()
        d.setCanceledOnTouchOutside(false)
        d.setCancelable(false)
        var leaveAsk: AlertDialog? = null
        var singleAsk: AlertDialog? = null

        fun hideKeyboard() {
            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(db.root.windowToken, 0)
        }

        /** 0 — синяя «Дальше», 1 — зелёная «Сохранить среднее», 2 — контур «Пропустить». */
        fun paintPrimary(mode: Int) {
            val bg = when (mode) {
                1 -> ContextCompat.getColor(requireContext(), R.color.catOptimal)
                2 -> ContextCompat.getColor(requireContext(), R.color.white)
                else -> ContextCompat.getColor(requireContext(), R.color.primary)
            }
            val fg = if (mode == 2) {
                ContextCompat.getColor(requireContext(), R.color.primary)
            } else {
                ContextCompat.getColor(requireContext(), R.color.onPrimary)
            }
            db.btnPrimary.backgroundTintList = ColorStateList.valueOf(bg)
            db.btnPrimary.setTextColor(fg)
            val stroke = if (mode == 2) (resources.displayMetrics.density).toInt().coerceAtLeast(1) else 0
            db.btnPrimary.strokeWidth = stroke
            db.btnPrimary.strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.primary)
            )
        }

        fun showActions() {
            db.pairActions.visibility = View.VISIBLE
            when (phase) {
                1 -> {
                    db.btnPrimary.text = getString(R.string.pair_next)
                    paintPrimary(0)
                    db.btnSingle.visibility = View.VISIBLE
                    db.tvWarn.visibility = View.VISIBLE
                }
                2 -> {
                    db.btnPrimary.text = getString(R.string.pair_skip_wait)
                    paintPrimary(2)
                    db.btnSingle.visibility = View.GONE
                    db.tvWarn.visibility = View.GONE
                }
                else -> {
                    db.btnPrimary.text = getString(R.string.pair_save_avg)
                    paintPrimary(1)
                    db.btnSingle.visibility = View.GONE
                    db.tvWarn.visibility = View.GONE
                }
            }
        }

        fun saveSingle(reading: Reading) {
            val time = if (whenTouched) whenMillis else System.currentTimeMillis()
            if (timeIsFuture(time)) return
            val note = db.etNote.text?.toString()?.trim().orEmpty()
            store.addRecord(reading.sys, reading.dia, reading.pulse, note, time)
            Toast.makeText(
                requireContext(),
                getString(R.string.pair_single_toast, reading.sys, reading.dia),
                Toast.LENGTH_LONG
            ).show()
            refresh()
            d.dismiss()
        }

        fun tryLeave() {
            if (!d.isShowing) return
            if (singleAsk?.isShowing == true) {
                singleAsk?.dismiss()
                return
            }
            if (phase == 1 || first == null) {
                d.dismiss()
                return
            }
            if (leaveAsk?.isShowing == true) return
            leaveAsk = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pair_leave_title)
                .setMessage(R.string.pair_leave_msg)
                .setPositiveButton(R.string.pair_stay, null)
                .setNegativeButton(R.string.pair_leave) { _, _ -> d.dismiss() }
                .show()
        }

        fun clock(sec: Int) = String.format(Locale.getDefault(), "%d:%02d", sec / 60, sec % 60)

        fun showFirst() {
            phase = 1
            waiting = false
            pairTimer?.cancel()
            pairTimer = null
            d.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            d.setTitle(R.string.pair_first_title)
            db.btnWhen.visibility = View.VISIBLE
            db.tvStep.visibility = View.VISIBLE
            db.tvStep.text = getString(R.string.pair_step_first)
            db.tvTimer.visibility = View.GONE
            db.tvWait.visibility = View.GONE
            db.tvPair.visibility = View.GONE
            showNumbers(true)
            field(db.etNote, true)
            showActions()
            capForm(db.formScroll)
            db.etSys.requestFocus()
        }

        fun startRest() {
            phase = 0
            waiting = true
            hideKeyboard()
            d.setTitle(R.string.pair_rest_title)
            db.btnWhen.visibility = View.GONE
            db.tvStep.visibility = View.GONE
            db.tvWarn.visibility = View.GONE
            db.tvPair.visibility = View.GONE
            showNumbers(false)
            field(db.etNote, false)
            db.tvTimer.visibility = View.VISIBLE
            db.tvWait.visibility = View.VISIBLE
            db.tvWait.text = getString(R.string.pair_rest)
            val totalSec = (REST_MS / 1000).toInt()
            db.tvTimer.text = clock(totalSec)
            db.btnPrimary.text = getString(R.string.pair_skip_rest)
            paintPrimary(2)
            db.btnSingle.visibility = View.GONE
            db.pairActions.visibility = View.VISIBLE
            capForm(db.formScroll)
            d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            pairTimer?.cancel()
            pairTimer = object : CountDownTimer(REST_MS, 250) {
                override fun onTick(ms: Long) {
                    if (!waiting || phase != 0) return
                    val sec = ((ms + 999) / 1000).toInt()
                    db.tvTimer.text = clock(sec)
                }

                override fun onFinish() {
                    if (!waiting || phase != 0) return
                    waiting = false
                    playMeasureSignal()
                    showFirst()
                }
            }.start()
        }

        fun showSecond() {
            if (phase == 3 || !d.isShowing) return
            phase = 3
            waiting = false
            pairTimer?.cancel()
            pairTimer = null
            d.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            d.setTitle(R.string.pair_second_title)
            db.tvStep.visibility = View.VISIBLE
            db.tvStep.text = getString(R.string.pair_step_second)
            db.tvTimer.visibility = View.GONE
            db.tvWait.visibility = View.GONE
            db.tvPair.visibility = View.VISIBLE
            showNumbers(true)
            field(db.etNote, true)
            db.etSys.setText("")
            db.etDia.setText("")
            db.etPulse.setText("")
            db.etSys.error = null
            db.etDia.error = null
            db.etPulse.error = null
            preview()
            showActions()
            capForm(db.formScroll)
            db.etSys.requestFocus()
        }

        fun startWait(reading: Reading) {
            first = reading
            phase = 2
            waiting = true
            hideKeyboard()
            d.setTitle(R.string.pair_wait_title)
            db.tvStep.visibility = View.GONE
            showNumbers(false)
            field(db.etNote, false)
            db.tvPair.visibility = View.GONE
            db.tvTimer.visibility = View.VISIBLE
            db.tvWait.visibility = View.VISIBLE
            db.tvWait.text = getString(R.string.pair_wait)
            val totalSec = (PAIR_GAP_MS / 1000).toInt()
            db.tvTimer.text = clock(totalSec)
            showActions()
            capForm(db.formScroll)
            d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            pairTimer?.cancel()
            pairTimer = object : CountDownTimer(PAIR_GAP_MS, 250) {
                override fun onTick(ms: Long) {
                    if (!waiting) return
                    val sec = ((ms + 999) / 1000).toInt()
                    db.tvTimer.text = clock(sec)
                }

                override fun onFinish() {
                    if (!waiting) return
                    waiting = false
                    playMeasureSignal()
                    showSecond()
                }
            }.start()
        }

        d.setOnDismissListener {
            waiting = false
            stopPairSession()
            try {
                leaveAsk?.dismiss()
            } catch (_: Exception) {
            }
            try {
                singleAsk?.dismiss()
            } catch (_: Exception) {
            }
            if (pairDialog === d) pairDialog = null
        }
        d.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                tryLeave()
                true
            } else {
                false
            }
        }
        d.setOnShowListener { startRest() }
        pairDialog = d
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (phase == 3) preview()
            }
        }
        db.etSys.addTextChangedListener(watcher)
        db.etDia.addTextChangedListener(watcher)

        db.btnPrimary.setOnClickListener {
            when (phase) {
                0 -> showFirst()
                1 -> {
                    val reading = readingOrNull() ?: return@setOnClickListener
                    startWait(reading)
                }
                2 -> showSecond()
                3 -> {
                    val second = readingOrNull() ?: return@setOnClickListener
                    val a = first ?: return@setOnClickListener
                    val time = if (whenTouched) whenMillis else System.currentTimeMillis()
                    if (timeIsFuture(time)) return@setOnClickListener
                    val sys = mean(a.sys, second.sys)
                    val dia = mean(a.dia, second.dia)
                    val pulse = when {
                        a.pulse > 0 && second.pulse > 0 -> mean(a.pulse, second.pulse)
                        a.pulse > 0 -> a.pulse
                        else -> second.pulse
                    }
                    val userNote = db.etNote.text?.toString()?.trim().orEmpty()
                    val raw = getString(R.string.pair_saved_note, a.sys, a.dia, second.sys, second.dia)
                    val note = if (userNote.isBlank()) raw else "$userNote · $raw"
                    store.addRecord(sys, dia, pulse, note, time)
                    Toast.makeText(requireContext(), getString(R.string.pair_saved_toast, sys, dia), Toast.LENGTH_LONG).show()
                    refresh()
                    d.dismiss()
                }
            }
        }
        db.btnCancelPair.setOnClickListener { tryLeave() }
        db.btnSingle.setOnClickListener {
            if (phase != 1) return@setOnClickListener
            val reading = readingOrNull() ?: return@setOnClickListener
            if (singleAsk?.isShowing == true) return@setOnClickListener
            hideKeyboard()
            singleAsk = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pair_single_title)
                .setMessage(R.string.pair_single_msg)
                .setPositiveButton(R.string.pair_single_back, null)
                .setNegativeButton(R.string.pair_single_anyway) { _, _ -> saveSingle(reading) }
                .show()
        }
        d.show()
    }

    private fun timeIsFuture(whenMillis: Long): Boolean {
        if (whenMillis > System.currentTimeMillis() + 2L * 60L * 1000L) {
            Toast.makeText(requireContext(), R.string.err_future, Toast.LENGTH_LONG).show()
            return true
        }
        return false
    }

    private fun mean(a: Int, b: Int) = ((a + b) / 2.0).roundToInt()

    private fun playMeasureSignal() {
        val ctx = context?.applicationContext ?: return
        try {
            pairTone?.release()
        } catch (_: Exception) {
        }
        val tone = try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (_: Exception) {
            try {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            } catch (_: Exception) {
                null
            }
        }
        pairTone = tone
        try {
            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
            pairHandler.postDelayed({
                try {
                    tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
                } catch (_: Exception) {
                }
            }, 650)
            pairHandler.postDelayed({
                try {
                    tone?.release()
                } catch (_: Exception) {
                }
                if (pairTone === tone) pairTone = null
            }, 1400)
        } catch (_: Exception) {
        }
        try {
            val pattern = longArrayOf(0, 350, 180, 350)
            if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java)
                    ?.defaultVibrator
                    ?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun stopPairSession() {
        pairTimer?.cancel()
        pairTimer = null
        pairHandler.removeCallbacksAndMessages(null)
        try {
            pairTone?.release()
        } catch (_: Exception) {
        }
        pairTone = null
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
        d.setOnShowListener { capForm(db.formScroll) }
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

    private fun exportPdf() {
        PdfShare.start(this, store.records(), null)
    }

    override fun onDestroyView() {
        val dialog = pairDialog
        pairDialog = null
        dialog?.setOnDismissListener(null)
        try {
            dialog?.dismiss()
        } catch (_: Exception) {
        }
        stopPairSession()
        super.onDestroyView()
        _b = null
    }
}
