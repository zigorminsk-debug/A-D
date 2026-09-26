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
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.arena.bpdiary.databinding.FragmentRecordEditBinding
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Отдельная полноэкранная страница ввода показателей давления (вместо всплывающего диалога/плашки).
 * Поддерживает:
 *  - Одиночный ввод показателей (быстрое добавление замера или редактирование существующего);
 *  - Двукратный клинический замер с таймером отдыха и расчётом среднего.
 */
class RecordEditFragment : Fragment() {

    private var _b: FragmentRecordEditBinding? = null
    private val b get() = _b!!
    private val store by lazy { Store(requireContext()) }

    private var editRecordId: Long? = null
    private var isPairMode: Boolean = false

    private var whenMillis: Long = System.currentTimeMillis()
    private var whenTouched: Boolean = false
    private val whenFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    // Состояние двойного замера
    private data class Reading(val sys: Int, val dia: Int, val pulse: Int)
    private var firstReading: Reading? = null
    private var pairPhase: Int = 0 // 1: первый замер, 2: таймер отдыха, 3: второй замер
    private var pairTimer: CountDownTimer? = null
    private val pairHandler = Handler(Looper.getMainLooper())
    private var pairTone: ToneGenerator? = null

    companion object {
        private const val ARG_RECORD_ID = "record_id"
        private const val ARG_IS_PAIR = "is_pair"

        fun newInstance(recordId: Long? = null, isPair: Boolean = false): RecordEditFragment {
            return RecordEditFragment().apply {
                arguments = Bundle().apply {
                    if (recordId != null) putLong(ARG_RECORD_ID, recordId)
                    putBoolean(ARG_IS_PAIR, isPair)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = arguments
        if (args != null && args.containsKey(ARG_RECORD_ID)) {
            editRecordId = args.getLong(ARG_RECORD_ID)
        }
        isPairMode = args?.getBoolean(ARG_IS_PAIR, false) ?: false
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentRecordEditBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.toolbar.setNavigationOnClickListener { handleBack() }

        val existing = editRecordId?.let { id -> store.records().firstOrNull { it.id == id } }

        if (existing != null) {
            b.toolbar.setTitle(R.string.edit_record)
            whenMillis = existing.time
            b.etSys.setText(existing.sys.toString())
            b.etDia.setText(existing.dia.toString())
            b.etPulse.setText(if (existing.pulse > 0) existing.pulse.toString() else "")
            b.etNote.setText(existing.note)
            setupSingleMode(existing)
        } else if (isPairMode) {
            b.toolbar.setTitle(R.string.pair_rest_title)
            setupPairMode()
        } else {
            b.toolbar.setTitle(R.string.add_record)
            setupSingleMode(null)
        }

        updateWhenButton()
        b.btnWhen.setOnClickListener {
            if (childFragmentManager.findFragmentByTag("record-date") != null) return@setOnClickListener
            whenTouched = true
            pickWhen(whenMillis) { picked ->
                whenMillis = picked
                updateWhenButton()
            }
        }

        // Автофокус на поле верхнего давления при открытии
        b.etSys.post {
            b.etSys.requestFocus()
            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(b.etSys, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun updateWhenButton() {
        if (_b == null) return
        b.btnWhen.text = getString(R.string.record_when_fmt, whenFmt.format(Date(whenMillis)))
    }

    // =========================================================================
    // Одиночный ввод показателей (стандартная страница)
    // =========================================================================

    private fun setupSingleMode(existing: BpRecord?) {
        b.singleActions.visibility = View.VISIBLE
        b.pairActions.visibility = View.GONE
        b.tvStep.visibility = View.GONE
        b.tvWarn.visibility = View.GONE
        b.tvTimer.visibility = View.GONE
        b.tvWait.visibility = View.GONE
        b.tvPair.visibility = View.GONE

        b.btnCancel.setOnClickListener { handleBack() }

        b.etNote.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                saveSingleDirect(existing)
                true
            } else false
        }

        b.btnSave.setOnClickListener {
            hideKeyboard()
            saveSingleDirect(existing)
        }
    }

    private fun saveSingleDirect(existing: BpRecord?) {
        val sys = b.etSys.text?.toString()?.toIntOrNull()
        val dia = b.etDia.text?.toString()?.toIntOrNull()
        val pulse = b.etPulse.text?.toString()?.toIntOrNull() ?: 0
        val note = b.etNote.text?.toString()?.trim().orEmpty()

        var ok = true
        if (sys == null || sys !in 60..300) {
            b.etSys.error = getString(R.string.err_range_sys)
            ok = false
        }
        if (dia == null || dia !in 30..200) {
            b.etDia.error = getString(R.string.err_range_dia)
            ok = false
        }
        if (pulse != 0 && pulse !in 25..250) {
            b.etPulse.error = getString(R.string.err_range_pulse)
            ok = false
        }
        if (sys != null && dia != null && sys <= dia) {
            b.etDia.error = getString(R.string.err_sys_lt_dia)
            ok = false
        }
        if (whenMillis > System.currentTimeMillis() + 2L * 60L * 1000L) {
            Toast.makeText(requireContext(), R.string.err_future, Toast.LENGTH_LONG).show()
            ok = false
        }
        if (!ok) return

        if (existing == null) {
            store.addRecord(sys!!, dia!!, pulse, note, whenMillis)
            Toast.makeText(requireContext(), R.string.record_added, Toast.LENGTH_SHORT).show()
        } else {
            store.updateRecord(BpRecord(existing.id, whenMillis, sys!!, dia!!, pulse, note))
            Toast.makeText(requireContext(), R.string.record_updated, Toast.LENGTH_SHORT).show()
        }

        (activity as? MainActivity)?.closeRecordEdit()
    }

    // =========================================================================
    // Парный ввод показателей (протокол 2 замеров с минутой отдыха)
    // =========================================================================

    private fun setupPairMode() {
        b.singleActions.visibility = View.GONE
        b.pairActions.visibility = View.VISIBLE

        fun field(edit: View, show: Boolean) {
            val parent = edit.parent as? View
            val box = parent?.parent as? View
            val target = if (box is com.google.android.material.textfield.TextInputLayout) box else parent
            target?.visibility = if (show) View.VISIBLE else View.GONE
        }
        fun showNumbers(show: Boolean) {
            field(b.etSys, show)
            field(b.etDia, show)
            field(b.etPulse, show)
        }

        fun readingOrNull(): Reading? {
            val sys = b.etSys.text?.toString()?.toIntOrNull()
            val dia = b.etDia.text?.toString()?.toIntOrNull()
            val pulse = b.etPulse.text?.toString()?.toIntOrNull() ?: 0
            var ok = true
            if (sys == null || sys !in 60..300) {
                b.etSys.error = getString(R.string.err_range_sys)
                ok = false
            } else b.etSys.error = null
            if (dia == null || dia !in 30..200) {
                b.etDia.error = getString(R.string.err_range_dia)
                ok = false
            } else b.etDia.error = null
            if (pulse != 0 && pulse !in 25..250) {
                b.etPulse.error = getString(R.string.err_range_pulse)
                ok = false
            } else b.etPulse.error = null
            if (sys != null && dia != null && sys <= dia) {
                b.etDia.error = getString(R.string.err_sys_lt_dia)
                ok = false
            }
            if (!ok || sys == null || dia == null) return null
            return Reading(sys, dia, pulse)
        }

        fun preview() {
            val a = firstReading ?: return
            val sys = b.etSys.text?.toString()?.toIntOrNull()
            val dia = b.etDia.text?.toString()?.toIntOrNull()
            b.tvPair.text = if (sys == null || dia == null) {
                getString(R.string.pair_first_fmt, a.sys, a.dia)
            } else {
                getString(R.string.pair_avg_fmt, a.sys, a.dia, mean(a.sys, sys), mean(a.dia, dia))
            }
        }

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
            b.btnPrimary.backgroundTintList = ColorStateList.valueOf(bg)
            b.btnPrimary.setTextColor(fg)
            val stroke = if (mode == 2) (resources.displayMetrics.density).toInt().coerceAtLeast(1) else 0
            b.btnPrimary.strokeWidth = stroke
            b.btnPrimary.strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.primary)
            )
        }

        fun showActions() {
            b.pairActions.visibility = View.VISIBLE
            when (pairPhase) {
                1 -> {
                    b.btnPrimary.text = getString(R.string.pair_next)
                    paintPrimary(0)
                    b.btnSingle.visibility = View.VISIBLE
                    b.tvWarn.visibility = View.VISIBLE
                }
                2 -> {
                    b.btnPrimary.text = getString(R.string.pair_skip_wait)
                    paintPrimary(2)
                    b.btnSingle.visibility = View.GONE
                    b.tvWarn.visibility = View.GONE
                }
                else -> {
                    b.btnPrimary.text = getString(R.string.pair_save_avg)
                    paintPrimary(1)
                    b.btnSingle.visibility = View.GONE
                    b.tvWarn.visibility = View.GONE
                }
            }
        }

        fun saveSingle(reading: Reading) {
            val time = if (whenTouched) whenMillis else System.currentTimeMillis()
            if (timeIsFuture(time)) return
            val note = b.etNote.text?.toString()?.trim().orEmpty()
            store.addRecord(reading.sys, reading.dia, reading.pulse, note, time)
            Toast.makeText(
                requireContext(),
                getString(R.string.pair_single_toast, reading.sys, reading.dia),
                Toast.LENGTH_LONG
            ).show()
            (activity as? MainActivity)?.closeRecordEdit()
        }

        fun step1() {
            pairPhase = 1
            b.tvStep.visibility = View.VISIBLE
            b.tvStep.text = getString(R.string.pair_step1)
            b.tvTimer.visibility = View.GONE
            b.tvWait.visibility = View.GONE
            b.tvPair.visibility = View.GONE
            showNumbers(true)
            showActions()
        }

        fun step3() {
            stopPairSession()
            pairPhase = 3
            b.tvStep.visibility = View.VISIBLE
            b.tvStep.text = getString(R.string.pair_step2)
            b.tvTimer.visibility = View.GONE
            b.tvWait.visibility = View.GONE
            b.tvPair.visibility = View.VISIBLE
            preview()
            showNumbers(true)
            b.etSys.setText("")
            b.etDia.setText("")
            b.etPulse.setText("")
            b.etSys.error = null
            b.etDia.error = null
            b.etPulse.error = null
            showActions()
            b.etSys.requestFocus()
        }

        fun startTimer() {
            pairPhase = 2
            b.tvStep.visibility = View.GONE
            b.tvPair.visibility = View.VISIBLE
            preview()
            b.tvTimer.visibility = View.VISIBLE
            b.tvWait.visibility = View.VISIBLE
            b.tvWait.text = getString(R.string.pair_wait_hint)
            showNumbers(false)
            showActions()

            pairTimer?.cancel()
            val total = 60_000L
            pairTimer = object : CountDownTimer(total, 250L) {
                override fun onTick(left: Long) {
                    val sec = ((left + 999L) / 1000L).coerceIn(0, 60)
                    b.tvTimer.text = String.format(Locale.getDefault(), "%02d:%02d", sec / 60, sec % 60)
                }
                override fun onFinish() {
                    b.tvTimer.text = "00:00"
                    playMeasureSignal()
                    step3()
                }
            }.start()
        }

        b.btnPrimary.setOnClickListener {
            hideKeyboard()
            when (pairPhase) {
                1 -> {
                    val r = readingOrNull() ?: return@setOnClickListener
                    firstReading = r
                    startTimer()
                }
                2 -> {
                    stopPairSession()
                    step3()
                }
                3 -> {
                    val second = readingOrNull() ?: return@setOnClickListener
                    val a = firstReading ?: return@setOnClickListener
                    val time = if (whenTouched) whenMillis else System.currentTimeMillis()
                    if (timeIsFuture(time)) return@setOnClickListener
                    val sys = mean(a.sys, second.sys)
                    val dia = mean(a.dia, second.dia)
                    val pulse = when {
                        a.pulse > 0 && second.pulse > 0 -> mean(a.pulse, second.pulse)
                        a.pulse > 0 -> a.pulse
                        else -> second.pulse
                    }
                    val userNote = b.etNote.text?.toString()?.trim().orEmpty()
                    val raw = getString(R.string.pair_saved_note, a.sys, a.dia, second.sys, second.dia)
                    val note = if (userNote.isBlank()) raw else "$userNote · $raw"
                    store.addRecord(sys, dia, pulse, note, time)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.pair_saved_toast, sys, dia),
                        Toast.LENGTH_LONG
                    ).show()
                    (activity as? MainActivity)?.closeRecordEdit()
                }
            }
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (pairPhase == 3) preview()
            }
        }
        b.etSys.addTextChangedListener(watcher)
        b.etDia.addTextChangedListener(watcher)

        b.btnCancelPair.setOnClickListener { handleBack() }

        b.btnSingle.setOnClickListener {
            if (pairPhase != 1) return@setOnClickListener
            val reading = readingOrNull() ?: return@setOnClickListener
            hideKeyboard()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pair_single_title)
                .setMessage(R.string.pair_single_msg)
                .setPositiveButton(R.string.pair_single_back, null)
                .setNegativeButton(R.string.pair_single_anyway) { _, _ -> saveSingle(reading) }
                .show()
        }

        step1()
    }

    fun handleBack() {
        hideKeyboard()
        if (isPairMode && firstReading != null) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pair_cancel_title)
                .setMessage(R.string.pair_cancel_msg)
                .setPositiveButton(R.string.pair_cancel_stay, null)
                .setNegativeButton(R.string.pair_cancel_leave) { _, _ ->
                    stopPairSession()
                    (activity as? MainActivity)?.closeRecordEdit()
                }
                .show()
        } else {
            stopPairSession()
            (activity as? MainActivity)?.closeRecordEdit()
        }
    }

    private fun hideKeyboard() {
        val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(b.root.windowToken, 0)
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
                cal.set(Calendar.HOUR_OF_DAY, timePicker.hour)
                cal.set(Calendar.MINUTE, timePicker.minute)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                onPicked(cal.timeInMillis)
            }
            timePicker.show(childFragmentManager, "record-time")
        }
        datePicker.show(childFragmentManager, "record-date")
    }

    private fun utcMidnight(localMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = localMillis }
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH)
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y, m, d)
        }.timeInMillis
    }

    private fun applyUtcDate(localStart: Long, utcSelected: Long): Long {
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcSelected }
        val localCal = Calendar.getInstance().apply { timeInMillis = localStart }
        localCal.set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
        localCal.set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
        localCal.set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
        return localCal.timeInMillis
    }

    override fun onDestroyView() {
        stopPairSession()
        super.onDestroyView()
        _b = null
    }
}
