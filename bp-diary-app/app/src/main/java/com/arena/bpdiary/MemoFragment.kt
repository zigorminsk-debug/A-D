package com.arena.bpdiary

import android.net.Uri
import android.os.Bundle
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

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentMemoBinding.inflate(i, c, false)
        return _b!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.btnAbout.setOnClickListener { (activity as? MainActivity)?.openAbout() }
        b.btnBackup.setOnClickListener { saveBackup.launch("bp-diary-backup.json") }
        b.btnRestore.setOnClickListener { openBackup.launch(arrayOf("*/*")) }
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
