package com.arena.bpdiary

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.arena.bpdiary.databinding.DialogPatientBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Перед PDF спрашивает ФИО и возраст, запоминает их и открывает отправку. */
object PdfShare {

    fun start(fragment: Fragment, records: List<BpRecord>, periodNote: String?) {
        if (!fragment.isAdded) return
        val ctx = fragment.requireContext()
        if (records.isEmpty()) {
            Toast.makeText(ctx, R.string.pdf_no_data, Toast.LENGTH_SHORT).show()
            return
        }
        val store = Store(ctx)
        val form = DialogPatientBinding.inflate(fragment.layoutInflater)
        form.etName.setText(store.patientName())
        val savedAge = store.patientAge()
        if (savedAge in 1..120) form.etAge.setText(savedAge.toString())

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.pdf_patient_title)
            .setView(form.root)
            .setPositiveButton(R.string.pdf_make, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        val refreshName = {
            val typed = form.etName.text?.toString()?.trim().orEmpty()
            val sample = if (typed.length >= 2) typed else "Иванов Иван Иванович"
            if (fragment.isAdded) {
                dialog.setMessage(fragment.getString(R.string.pdf_patient_msg, PdfExporter.reportTitle(sample)))
            }
        }
        form.etName.doAfterTextChanged { refreshName() }
        refreshName()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = form.etName.text?.toString()?.trim().orEmpty()
            val age = form.etAge.text?.toString()?.toIntOrNull()
            var ok = true
            if (name.length < 2) {
                form.etName.error = fragment.getString(R.string.err_patient_name)
                ok = false
            } else form.etName.error = null
            if (age == null || age !in 1..120) {
                form.etAge.error = fragment.getString(R.string.err_patient_age)
                ok = false
            } else form.etAge.error = null
            if (!ok || !fragment.isAdded) return@setOnClickListener
            store.savePatient(name, age!!)
            val file = PdfExporter.build(ctx, records, periodNote, name, age) ?: return@setOnClickListener
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val title = file.nameWithoutExtension
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(title, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            fragment.startActivity(Intent.createChooser(send, fragment.getString(R.string.pdf_chooser)))
            dialog.dismiss()
        }
    }
}
