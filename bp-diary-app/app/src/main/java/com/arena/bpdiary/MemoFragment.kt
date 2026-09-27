package com.arena.bpdiary

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.arena.bpdiary.databinding.FragmentMemoBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MemoFragment : Fragment() {

    private var _b: FragmentMemoBinding? = null
    private val b get() = _b!!

    // Сохранение копии: системный диалог «Сохранить как»
    private val saveBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri != null) writeBackup(uri)
        }

    // Восстановление: системный выбор файла
    private val openBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) askRestore(uri)
        }

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
        _b = FragmentMemoBinding.inflate(i, c, false)
        return _b!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.etSosPhone.setText(Emergency.phone(requireContext()))
        b.etSosName.setText(Emergency.patientName(requireContext()))
        b.etSosClinic.setText(Emergency.clinic(requireContext()))
        b.etSosAddress.setText(Emergency.address(requireContext()))
        b.etSosExtra.setText(Emergency.extra(requireContext()))
        b.tvSosPreview.text = Emergency.script(requireContext())
        refreshCallLabels()
        val watch = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshSosPreview() }
        }
        b.etSosPhone.addTextChangedListener(watch)
        b.etSosName.addTextChangedListener(watch)
        b.etSosClinic.addTextChangedListener(watch)
        b.etSosAddress.addTextChangedListener(watch)
        b.etSosExtra.addTextChangedListener(watch)
        b.btnSosSave.setOnClickListener { persistSos(announce = true) }
        b.btnSosListen.setOnClickListener {
            if (!persistSos(announce = true)) return@setOnClickListener
            Emergency.showAndSpeak(this, Emergency.script(requireContext())) { callAmbulance() }
        }
        b.btnSosCall.setOnClickListener { callAmbulance() }
        b.btnSosStroke.setOnClickListener { speakStrokeAndCall() }
        b.btnSosData.setOnClickListener {
            Emergency.showDataDialog(this, onClosed = { reloadSos() }, onLocate = { deliver ->
                withLocation { Emergency.locateAndDescribe(requireContext(), deliver) }
            })
        }
        b.btnSosVoice.setOnClickListener { Emergency.showVoiceDialog(this) }
        b.btnRedCall.setOnClickListener { callAmbulance() }
        b.btnRedStroke.setOnClickListener { speakStrokeAndCall() }
        b.btnAbout.setOnClickListener { (activity as? MainActivity)?.openAbout() }
        b.btnExportCsv.setOnClickListener { exportCsv() }
        b.btnBackup.setOnClickListener { saveBackup.launch("bp-diary-backup.json") }
                b.btnRestore.setOnClickListener { openBackup.launch(arrayOf("*/*")) }
        b.btnTestBanner.setOnClickListener { showTestBannerDialog() }
                b.btnSoundSettings.setOnClickListener { showSoundSettingsDialog() }
        refreshCurrentSoundLabel()
        b.btnUpdate.setOnClickListener {
            (activity as? androidx.appcompat.app.AppCompatActivity)?.let { AppUpdater.start(it, manual = true) }
        }
        b.btnUpdateToken.setOnClickListener {
            (activity as? androidx.appcompat.app.AppCompatActivity)?.let { AppUpdater.showTokenDialog(it) }
        }
        try {
            val p = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            b.txtUpdateNow.text = getString(R.string.update_current, p.versionName ?: "")
        } catch (_: Exception) {
        }
    }

    private fun reloadSos() {
        if (_b == null) return
        b.etSosPhone.setText(Emergency.phone(requireContext()))
        b.etSosName.setText(Emergency.patientName(requireContext()))
        b.etSosClinic.setText(Emergency.clinic(requireContext()))
        b.etSosAddress.setText(Emergency.address(requireContext()))
        b.etSosExtra.setText(Emergency.extra(requireContext()))
        b.tvSosPreview.text = Emergency.script(requireContext())
        refreshCallLabels()
    }

    private fun persistSos(announce: Boolean = false): Boolean {
        if (_b == null) return false
        val ok = Emergency.save(
            requireContext(),
            b.etSosName.text?.toString().orEmpty(),
            b.etSosClinic.text?.toString().orEmpty(),
            b.etSosAddress.text?.toString().orEmpty(),
            b.etSosExtra.text?.toString().orEmpty(),
            b.etSosPhone.text?.toString().orEmpty()
        )
        b.etSosPhone.error = if (ok) null else getString(R.string.sos_phone_bad)
        b.tvSosPreview.text = Emergency.script(requireContext())
        refreshCallLabels()
        if (announce) {
            Toast.makeText(
                requireContext(),
                if (ok) R.string.sos_saved else R.string.sos_phone_bad,
                Toast.LENGTH_LONG
            ).show()
        }
        return ok
    }

    private fun refreshCallLabels() {
        if (_b == null) return
        val label = Emergency.callLabel(requireContext())
        b.tvSosTitle.text = getString(R.string.sos_title_fmt, Emergency.phone(requireContext()))
        b.btnSosCall.text = label
        b.btnRedCall.text = label
    }

    private fun refreshSosPreview() {
        persistSos()
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

    private fun speakStrokeAndCall() {
        persistSos()
        if (!Emergency.hasAddress(requireContext())) {
            Toast.makeText(requireContext(), R.string.sos_need_address, Toast.LENGTH_LONG).show()
        }
        Emergency.showAndSpeak(this, Emergency.script(requireContext())) { dialAmbulance() }
        Emergency.watchPlace(this, stroke = true)
        dialAmbulance()
    }

    /** Тот же CSV, что раньше был на вкладке «Дневник»: все измерения одной таблицей. */
    private fun exportCsv() {
        val records = Store(requireContext()).records()
        if (records.isEmpty()) {
            Toast.makeText(requireContext(), R.string.pdf_no_data, Toast.LENGTH_SHORT).show()
            return
        }
        val csv = CsvExporter.build(requireContext(), records)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_subject))
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(i, getString(R.string.export_chooser)))
    }

    private fun showTestBannerDialog() {
        val options = arrayOf(
            getString(R.string.test_banner_opt_measure),
            getString(R.string.test_banner_opt_med)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.test_banner_dialog_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> triggerTestBanner(isMed = false)
                    1 -> triggerTestBanner(isMed = true)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun triggerTestBanner(isMed: Boolean) {
        val ctx = requireContext().applicationContext
        val sampleName = if (isMed) getString(R.string.test_med_sample_name) else ""
        val sampleDose = if (isMed) getString(R.string.test_med_sample_dose) else ""
        val type = if (isMed) ReminderBannerActivity.TYPE_MED else ReminderBannerActivity.TYPE_MEASURE

        // Планируем через AlarmManager с системным приоритетом будильника (AlarmClockInfo).
        // Это позволяет протестировать поведение на заблокированном телефоне!
        ReminderScheduler.scheduleTest(
            ctx = ctx,
            type = type,
            delaySeconds = 10,
            name = sampleName,
            dose = sampleDose
        )

        Toast.makeText(requireContext(), R.string.test_banner_scheduled_toast, Toast.LENGTH_LONG).show()
    }

    private fun refreshCurrentSoundLabel() {
        if (_b == null) return
        val current = AlertSettings.getSelectedSound(requireContext())
        val catName = if (current.isPiercing) "Категория: Для пожилых" else "Категория: Лёгкие / релакс"
        val lightParts = mutableListOf<String>()
        if (AlertSettings.isTorchFlashEnabled(requireContext())) lightParts.add("фонарик")
        if (AlertSettings.isScreenFlashEnabled(requireContext())) lightParts.add("экран")
        val lightStr = if (lightParts.isNotEmpty()) lightParts.joinToString("+") else "выкл"
        
        b.tvCurrentSound.text = "$catName\nСигнал: ${current.title}\nСвет: $lightStr"
    }

    private var previewPlayer: android.media.MediaPlayer? = null

        private fun showSoundSettingsDialog() {
        val ctx = requireContext()
        val db = com.arena.bpdiary.databinding.DialogSoundSettingsBinding.inflate(layoutInflater)

        db.switchTorch.isChecked = AlertSettings.isTorchFlashEnabled(ctx)
        db.switchScreen.isChecked = AlertSettings.isScreenFlashEnabled(ctx)

        db.switchTorch.setOnCheckedChangeListener { _, isChecked ->
            AlertSettings.setTorchFlashEnabled(ctx, isChecked)
            refreshCurrentSoundLabel()
            if (isChecked) {
                FlashlightHelper.flash(ctx, count = 3, onMs = 150L, offMs = 120L)
            }
        }

        db.switchScreen.setOnCheckedChangeListener { _, isChecked ->
            AlertSettings.setScreenFlashEnabled(ctx, isChecked)
        }

        fun playPreview(resId: Int) {
            try {
                previewPlayer?.stop()
                previewPlayer?.release()
            } catch (_: Exception) {
            }
            try {
                previewPlayer = android.media.MediaPlayer.create(ctx, resId).apply {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    start()
                }
            } catch (_: Exception) {
            }
        }

        var isPierceSelected = AlertSettings.getSelectedSound(ctx).isPiercing

        fun renderSoundList() {
            db.rgSounds.removeAllViews()
            val sounds = if (isPierceSelected) AlertSettings.PIERCING_SOUNDS else AlertSettings.CALM_SOUNDS
            db.tvCategoryDesc.text = if (isPierceSelected) {
                getString(R.string.sound_category_pierce_desc)
            } else {
                getString(R.string.sound_category_calm_desc)
            }
            val curId = AlertSettings.getSelectedSoundId(ctx)

            sounds.forEach { opt ->
                val rb = android.widget.RadioButton(ctx).apply {
                    id = View.generateViewId()
                    text = "${opt.title}\n${opt.desc}"
                    tag = opt.id
                    textSize = 14f
                    setPadding(16, 12, 16, 12)
                    isChecked = opt.id == curId
                    setOnClickListener {
                        AlertSettings.setSelectedSoundId(ctx, opt.id)
                        refreshCurrentSoundLabel()
                        playPreview(opt.rawResId)
                        if (db.switchTorch.isChecked && isPierceSelected) {
                            FlashlightHelper.flash(ctx, count = 3, onMs = 150L, offMs = 100L)
                        }
                    }
                }
                db.rgSounds.addView(rb)
            }
        }

        if (isPierceSelected) {
            db.toggleCategory.check(R.id.btnCatPierce)
        } else {
            db.toggleCategory.check(R.id.btnCatCalm)
        }
        renderSoundList()

        db.toggleCategory.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newPierce = checkedId == R.id.btnCatPierce
                if (newPierce != isPierceSelected) {
                    isPierceSelected = newPierce
                    val defaultOption = if (isPierceSelected) AlertSettings.PIERCING_SOUNDS.first() else AlertSettings.CALM_SOUNDS.first()
                    AlertSettings.setSelectedSoundId(ctx, defaultOption.id)
                    refreshCurrentSoundLabel()
                    renderSoundList()
                    playPreview(defaultOption.rawResId)
                }
            }
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.sound_dialog_title)
            .setView(db.root)
            .setPositiveButton(R.string.about_back) { _, _ -> }
            .create()

        dialog.setOnDismissListener {
            try {
                previewPlayer?.stop()
                previewPlayer?.release()
            } catch (_: Exception) {
            }
            previewPlayer = null
            FlashlightHelper.stop()
            Notifications.ensureChannel(ctx)
        }

        dialog.show()
    }

    private fun writeBackup(uri: Uri) {
        try {
            val json = Store(requireContext()).exportJson()
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(requireContext(), R.string.backup_ok, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.backup_fail, Toast.LENGTH_LONG).show()
        }
    }

    private fun askRestore(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_restore)
            .setMessage(R.string.restore_confirm)
            .setPositiveButton(R.string.save) { _, _ ->
                try {
                    val text = requireContext().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                    val store = Store(requireContext())
                    // снять будильники старого набора, иначе после замены данных они продолжают звонить
                    ReminderScheduler.cancelAll(requireContext())
                    val ok = store.importJson(text)
                    if (ok) store.repairAlarmIds()
                    ReminderScheduler.rescheduleAll(requireContext())
                    WidgetProvider.updateAll(requireContext())
                    Toast.makeText(
                        requireContext(),
                        if (ok) R.string.backup_restored else R.string.backup_bad,
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    ReminderScheduler.rescheduleAll(requireContext())
                    Toast.makeText(requireContext(), R.string.backup_fail, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
