package com.arena.bpdiary

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.arena.bpdiary.databinding.DialogSosDataBinding
import com.arena.bpdiary.databinding.DialogStrokeBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/**
 * Звонок на 103 и озвучивание заранее сохранённого адреса.
 * Генератор речи — системный TextToSpeech телефона, чтобы адрес можно было записать самому.
 */
object Emergency {

    private const val PREFS = "bp_emergency"
    private const val KEY_NAME = "name"
    private const val KEY_CLINIC = "clinic"
    private const val KEY_ADDRESS = "address"
    private const val KEY_EXTRA = "extra"
    private const val KEY_PHONE = "phone"
    private const val DEFAULT_PHONE = "103"

    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var voiceMissing = false
    private var pending: String? = null
    private var lastText = ""
    private var placeGen = 0
    private var speechView: android.widget.TextView? = null
    private var restoreSpeaker: Boolean? = null
    private var speakGen = 0
    private var audioCtx: Context? = null
    private val savedVolumes = HashMap<Int, Int>()
    private val releaseAudio = Runnable {
        val ctx = audioCtx ?: return@Runnable
        restore(ctx)
    }

    fun patientName(ctx: Context): String = pref(ctx, KEY_NAME)

    fun clinic(ctx: Context): String = pref(ctx, KEY_CLINIC)

    fun address(ctx: Context): String = pref(ctx, KEY_ADDRESS)

    fun extra(ctx: Context): String = pref(ctx, KEY_EXTRA)

    /** Сохранённый номер скорой. Пустая запись и неверный текст дают 103. */
    fun phone(ctx: Context): String = acceptedPhone(pref(ctx, KEY_PHONE)) ?: DEFAULT_PHONE

    fun callLabel(ctx: Context): String = ctx.getString(R.string.sos_call_fmt, phone(ctx))

    /**
     * Номер для звонилки: цифры и необязательный «+».
     * Пустое поле — 103. Буквы и слишком короткий номер — null, запись не затирается.
     */
    fun acceptedPhone(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return DEFAULT_PHONE
        val cleaned = buildString {
            for (ch in trimmed) {
                if (ch == ' ' || ch == '-' || ch == '(' || ch == ')' || ch == '.') continue
                append(ch)
            }
        }
        val digits = if (cleaned.startsWith("+")) cleaned.drop(1) else cleaned
        if (digits.length !in 2..15 || digits.any { !it.isDigit() }) return null
        return if (cleaned.startsWith("+")) "+$digits" else digits
    }

    /** false — номер не сохранился, остальные поля записаны. */
    fun save(
        ctx: Context,
        name: String,
        clinic: String,
        address: String,
        extra: String,
        phone: String? = null
    ): Boolean {
        val editor = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, name.trim())
            .putString(KEY_CLINIC, clinic.trim())
            .putString(KEY_ADDRESS, address.trim())
            .putString(KEY_EXTRA, extra.trim())
        var phoneOk = true
        if (phone != null) {
            val normalized = acceptedPhone(phone)
            if (normalized == null) phoneOk = false
            else editor.putString(KEY_PHONE, normalized)
        }
        editor.commit()
        return phoneOk
    }

    private fun pref(ctx: Context, key: String): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "") ?: ""

    fun hasAddress(ctx: Context) = address(ctx).isNotBlank()

    /** Текст, который телефон произнесёт. ФИО, поликлиника и адрес — из сохранённой записи. */
    fun script(ctx: Context, place: String = ""): String =
        scriptFrom(ctx, patientName(ctx), clinic(ctx), address(ctx), extra(ctx), place)

    fun scriptFrom(
        ctx: Context,
        name: String,
        clinic: String,
        address: String,
        extra: String,
        place: String = ""
    ): String {
        val where = address.trim().ifBlank { ctx.getString(R.string.sos_script_no_address) }
        val sb = StringBuilder()
        sb.append(ctx.getString(R.string.sos_script_lead))
        val who = name.trim()
        if (who.isNotBlank()) sb.append(ctx.getString(R.string.sos_script_patient, who))
        val poly = clinic.trim()
        if (poly.isNotBlank()) sb.append(ctx.getString(R.string.sos_script_clinic, poly))
        sb.append(ctx.getString(R.string.sos_script_address, where))
        val more = extra.trim()
        if (more.isNotBlank()) sb.append(ctx.getString(R.string.sos_script_extra, more))
        if (place.isNotBlank()) sb.append(place.trim()).append(' ')
        sb.append(ctx.getString(R.string.sos_script_repeat, where))
        return sb.toString()
    }

    /**
     * Текст звонка, если место не совпало с записью.
     * Адрес назначения и повтор — ближайшее здание. Записанный адрес называется один раз и не повторяется.
     */
    fun scriptReplaced(ctx: Context, spokenAddress: String, placeNote: String): String {
        val where = spokenAddress.trim().ifBlank { ctx.getString(R.string.sos_script_no_address) }
        val sb = StringBuilder()
        sb.append(ctx.getString(R.string.sos_script_lead))
        val who = patientName(ctx).trim()
        if (who.isNotBlank()) sb.append(ctx.getString(R.string.sos_script_patient, who))
        val poly = clinic(ctx).trim()
        if (poly.isNotBlank()) sb.append(ctx.getString(R.string.sos_script_clinic, poly))
        sb.append(ctx.getString(R.string.sos_script_address, where))
        if (placeNote.isNotBlank()) sb.append(placeNote.trim()).append(' ')
        val saved = address(ctx).trim()
        if (saved.isNotBlank() && saved != where) {
            sb.append(ctx.getString(R.string.sos_script_saved_once, saved))
        }
        sb.append(ctx.getString(R.string.sos_script_repeat, where))
        return sb.toString()
    }

    /** Всегда звонилка телефона по умолчанию. Приложение само трубку не снимает. */
    fun placeCall(activity: Activity): Boolean {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phone(activity)}"))
        return try {
            activity.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun speak(ctx: Context, text: String, queued: Boolean = false) {
        val spoken = text.trim()
        if (spoken.isBlank()) return
        lastText = if (queued && lastText.isNotBlank()) "$lastText $spoken" else spoken
        speechView?.text = lastText
        val app = ctx.applicationContext
        audioCtx = app
        val gen = ++speakGen
        main.removeCallbacks(releaseAudio)
        speaker(app, true)
        boost(app)
        val existing = engine
        if (existing != null && ready) {
            say(existing, spoken, if (queued) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, "stroke-$gen")
            return
        }
        pending = lastText
        if (existing != null) return
        voiceMissing = false
        engine = TextToSpeech(app) { status ->
            if (engine == null) {
                main.post { onEngineReady(app, status) }
            } else {
                onEngineReady(app, status)
            }
        }
    }

    private fun onEngineReady(ctx: Context, status: Int) {
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            voiceMissing = true
            pending = null
            return
        }
        ready = true
        voiceMissing = !applyLanguage(tts)
        tts.setSpeechRate(0.85f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                main.post { scheduleRestore(utteranceId) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { scheduleRestore(utteranceId) }
            }
        })
        val queued = pending
        pending = null
        if (!queued.isNullOrBlank() && !voiceMissing) say(tts, queued, TextToSpeech.QUEUE_FLUSH, "stroke-$speakGen")
    }

    fun stop(ctx: Context) {
        speakGen++
        pending = null
        lastText = ""
        main.removeCallbacks(releaseAudio)
        try {
            engine?.stop()
        } catch (_: Exception) {
        }
        restore(ctx.applicationContext)
    }

    fun shutdown() {
        try {
            engine?.shutdown()
        } catch (_: Exception) {
        }
        engine = null
        ready = false
    }

    /** Окно, куда заранее вписывают ФИО, поликлинику и адрес для звонка в скорую. */
    fun showDataDialog(
        fragment: Fragment,
        onClosed: (() -> Unit)? = null,
        onLocate: ((String) -> Unit) -> Unit = { deliver ->
            deliver(fragment.getString(R.string.sos_place_need_perm))
        }
    ) {
        val activity = fragment.activity ?: return
        val binding = DialogSosDataBinding.inflate(fragment.layoutInflater)
        binding.etSosPhone.setText(phone(activity))
        binding.etSosName.setText(patientName(activity))
        binding.etSosClinic.setText(clinic(activity))
        binding.etSosAddress.setText(address(activity))
        binding.etSosExtra.setText(extra(activity))
        fun preview() {
            binding.tvSosPreview.text = scriptFrom(
                activity,
                binding.etSosName.text?.toString().orEmpty(),
                binding.etSosClinic.text?.toString().orEmpty(),
                binding.etSosAddress.text?.toString().orEmpty(),
                binding.etSosExtra.text?.toString().orEmpty()
            )
        }
        preview()
        val watch = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { preview() }
        }
        binding.etSosName.addTextChangedListener(watch)
        binding.etSosClinic.addTextChangedListener(watch)
        binding.etSosAddress.addTextChangedListener(watch)
        binding.etSosExtra.addTextChangedListener(watch)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(binding.root)
            .setCancelable(true)
            .create()
        fun persistDialog(): Boolean {
            val ok = save(
                activity,
                binding.etSosName.text?.toString().orEmpty(),
                binding.etSosClinic.text?.toString().orEmpty(),
                binding.etSosAddress.text?.toString().orEmpty(),
                binding.etSosExtra.text?.toString().orEmpty(),
                binding.etSosPhone.text?.toString().orEmpty()
            )
            binding.etSosPhone.error = if (ok) null else activity.getString(R.string.sos_phone_bad)
            if (!ok) toast(activity, R.string.sos_phone_bad)
            return ok
        }
        binding.btnSosPlace.setOnClickListener {
            persistDialog()
            onLocate { text -> binding.tvSosPlace.text = text }
        }
        binding.btnSosSave.setOnClickListener {
            if (!persistDialog()) return@setOnClickListener
            toast(activity, R.string.sos_saved)
            dialog.dismiss()
        }
        binding.btnSosClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { onClosed?.invoke() }
        dialog.show()
    }

    /**
     * После набора 103 сверяет GPS, Wi‑Fi и сотовую сеть с записанным адресом.
     * Если место другое — произносит ближайшее здание и координаты.
     */
    fun watchPlace(fragment: Fragment, stroke: Boolean) {
        val activity = fragment.activity ?: return
        if (!PlaceFinder.hasPermission(activity)) return
        if (!PlaceFinder.sensorsOn(activity)) {
            if (stroke) speak(activity, activity.getString(R.string.sos_place_off), queued = true)
            return
        }
        val my = ++placeGen
        var said = ""
        PlaceFinder.locate(activity) { fix ->
            if (my != placeGen || fragment.activity == null) return@locate
            if (fix == null) {
                if (stroke) speak(activity, activity.getString(R.string.sos_script_no_fix), queued = true)
                return@locate
            }
            PlaceFinder.describe(activity, fix, address(activity)) { report ->
                if (my != placeGen || fragment.activity == null) return@describe
                if (!report.shouldSpeak) {
                    if (stroke) speechView?.text = script(activity)
                    return@describe
                }
                if (report.speech == said) return@describe
                said = report.speech
                val text = if (report.replaceAddress && report.spokenAddress.isNotBlank()) {
                    scriptReplaced(activity, report.spokenAddress, report.speech)
                } else {
                    script(activity, report.speech)
                }
                if (stroke && speechView != null) {
                    speechView?.text = text
                    // Сброс очереди: иначе в конце снова звучит записанный адрес.
                    speak(activity, text, queued = false)
                } else if (fragment.isAdded) {
                    showAndSpeak(fragment, text) { }
                }
            }
        }
    }

    fun locateAndDescribe(ctx: Context, onText: (String) -> Unit) {
        if (!PlaceFinder.hasPermission(ctx)) {
            onText(ctx.getString(R.string.sos_place_need_perm))
            return
        }
        if (!PlaceFinder.sensorsOn(ctx)) {
            onText(ctx.getString(R.string.sos_place_off))
            return
        }
        onText(ctx.getString(R.string.sos_place_checking))
        PlaceFinder.locate(ctx) { fix ->
            if (fix == null) {
                onText(ctx.getString(R.string.sos_script_no_fix))
                return@locate
            }
            PlaceFinder.describe(ctx, fix, address(ctx)) { report -> onText(report.screen) }
        }
    }

    fun showAndSpeak(fragment: Fragment, text: String, onCall: () -> Unit) {
        val activity = fragment.activity ?: return
        val binding = DialogStrokeBinding.inflate(fragment.layoutInflater)
        binding.tvStrokeSpeech.text = text
        binding.btnStrokeCall.text = callLabel(activity)
        speechView = binding.tvStrokeSpeech
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(binding.root)
            .setCancelable(true)
            .create()
        binding.btnStrokeAgain.setOnClickListener {
            speak(activity, binding.tvStrokeSpeech.text?.toString().orEmpty().ifBlank { text })
            if (voiceMissing) toast(activity, R.string.sos_no_voice)
        }
        binding.btnStrokeCall.setOnClickListener { onCall() }
        binding.btnStrokeClose.setOnClickListener {
            stop(activity)
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            if (speechView === binding.tvStrokeSpeech) speechView = null
        }
        dialog.show()
        speak(activity, text)
        vibrate(activity)
        main.postDelayed({
            if (voiceMissing) toast(activity, R.string.sos_no_voice)
        }, 700)
    }

    private fun say(tts: TextToSpeech, text: String, mode: Int, utteranceId: String) {
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        tts.speak(text, mode, params, utteranceId)
    }

    private fun scheduleRestore(utteranceId: String?) {
        val gen = utteranceId?.substringAfter('-', "")?.toIntOrNull() ?: return
        if (gen != speakGen) return
        main.removeCallbacks(releaseAudio)
        main.postDelayed(releaseAudio, 1_200)
    }

    private fun applyLanguage(tts: TextToSpeech): Boolean {
        val locales = listOf(Locale("ru", "BY"), Locale("ru", "RU"), Locale("ru"))
        for (locale in locales) {
            val result = tts.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                return true
            }
        }
        return false
    }

    private fun boost(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        for (stream in intArrayOf(AudioManager.STREAM_ALARM, AudioManager.STREAM_MUSIC)) {
            if (stream !in savedVolumes) savedVolumes[stream] = am.getStreamVolume(stream)
            val max = am.getStreamMaxVolume(stream)
            val target = (max * 0.85f).toInt().coerceAtLeast(1)
            if (am.getStreamVolume(stream) < target) {
                try {
                    am.setStreamVolume(stream, target, 0)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun restore(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        for ((stream, volume) in savedVolumes) {
            try {
                am.setStreamVolume(stream, volume, 0)
            } catch (_: Exception) {
            }
        }
        savedVolumes.clear()
        speaker(ctx, false)
    }

    private fun speaker(ctx: Context, on: Boolean) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (on) {
            if (restoreSpeaker == null) restoreSpeaker = am.isSpeakerphoneOn
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    val device = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (device != null) am.setCommunicationDevice(device)
                }
            } catch (_: Exception) {
            }
            try {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            } catch (_: Exception) {
            }
        } else {
            val prev = restoreSpeaker ?: return
            restoreSpeaker = null
            try {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            } catch (_: Exception) {
            }
            try {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = prev
            } catch (_: Exception) {
            }
        }
    }

    private fun vibrate(ctx: Context) {
        try {
            val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(180)
            }
        } catch (_: Exception) {
        }
    }

    private fun toast(ctx: Context, message: Int) {
        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
    }
}
