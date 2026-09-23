package com.arena.bpdiary

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
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
import androidx.core.content.ContextCompat
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
    private const val KEY_ADDRESS = "address"
    private const val KEY_EXTRA = "extra"
    private const val NUMBER = "103"

    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var voiceMissing = false
    private var pending: String? = null
    private var lastText = ""
    private val savedVolumes = HashMap<Int, Int>()

    fun address(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ADDRESS, "") ?: ""

    fun extra(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EXTRA, "") ?: ""

    fun save(ctx: Context, address: String, extra: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ADDRESS, address.trim())
            .putString(KEY_EXTRA, extra.trim())
            .commit()
    }

    fun hasAddress(ctx: Context) = address(ctx).isNotBlank()

    /** Текст, который телефон произнесёт. Адрес берётся из заранее сохранённой записи. */
    fun script(ctx: Context): String {
        val where = address(ctx).ifBlank { ctx.getString(R.string.sos_script_no_address) }
        val more = extra(ctx).let { if (it.isBlank()) "" else ctx.getString(R.string.sos_script_extra, it) }
        return ctx.getString(R.string.sos_script, where, more)
    }

    fun canCallDirectly(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CALL_PHONE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Сразу звонит на 103, если разрешение есть. Иначе открывает набор с уже введённым 103. */
    fun placeCall(activity: Activity): Boolean {
        val granted = canCallDirectly(activity)
        val action = if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:$NUMBER"))
        return try {
            activity.startActivity(intent)
            true
        } catch (_: SecurityException) {
            try {
                activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$NUMBER")))
                true
            } catch (_: Exception) {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun speak(ctx: Context, text: String) {
        lastText = text
        boost(ctx.applicationContext)
        val existing = engine
        if (existing != null && ready) {
            say(existing, text)
            return
        }
        pending = text
        if (existing != null) return
        voiceMissing = false
        val app = ctx.applicationContext
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
                main.post { restore(ctx) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { restore(ctx) }
            }
        })
        val queued = pending
        pending = null
        if (!queued.isNullOrBlank() && !voiceMissing) say(tts, queued)
    }

    fun stop(ctx: Context) {
        pending = null
        lastText = ""
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

    /** Окно, куда заранее вписывают адрес и приметы для звонка в скорую. */
    fun showDataDialog(fragment: Fragment, onClosed: (() -> Unit)? = null) {
        val activity = fragment.activity ?: return
        val binding = DialogSosDataBinding.inflate(fragment.layoutInflater)
        binding.etSosAddress.setText(address(activity))
        binding.etSosExtra.setText(extra(activity))
        fun preview() {
            val where = binding.etSosAddress.text?.toString()?.trim().orEmpty()
                .ifBlank { activity.getString(R.string.sos_script_no_address) }
            val more = binding.etSosExtra.text?.toString()?.trim().orEmpty()
                .let { if (it.isBlank()) "" else activity.getString(R.string.sos_script_extra, it) }
            binding.tvSosPreview.text = activity.getString(R.string.sos_script, where, more)
        }
        preview()
        val watch = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { preview() }
        }
        binding.etSosAddress.addTextChangedListener(watch)
        binding.etSosExtra.addTextChangedListener(watch)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(binding.root)
            .setCancelable(true)
            .create()
        binding.btnSosSave.setOnClickListener {
            save(
                activity,
                binding.etSosAddress.text?.toString().orEmpty(),
                binding.etSosExtra.text?.toString().orEmpty()
            )
            toast(activity, R.string.sos_saved)
            dialog.dismiss()
        }
        binding.btnSosClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { onClosed?.invoke() }
        dialog.show()
    }

    fun showAndSpeak(fragment: Fragment, text: String, onCall: () -> Unit) {
        val activity = fragment.activity ?: return
        val binding = DialogStrokeBinding.inflate(fragment.layoutInflater)
        binding.tvStrokeSpeech.text = text
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(binding.root)
            .setCancelable(true)
            .create()
        binding.btnStrokeAgain.setOnClickListener {
            speak(activity, text)
            if (voiceMissing) toast(activity, R.string.sos_no_voice)
        }
        binding.btnStrokeCall.setOnClickListener { onCall() }
        binding.btnStrokeClose.setOnClickListener {
            stop(activity)
            dialog.dismiss()
        }
        dialog.show()
        speak(activity, text)
        vibrate(activity)
        main.postDelayed({
            if (voiceMissing) toast(activity, R.string.sos_no_voice)
        }, 700)
    }

    private fun say(tts: TextToSpeech, text: String) {
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "stroke")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "stroke")
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
