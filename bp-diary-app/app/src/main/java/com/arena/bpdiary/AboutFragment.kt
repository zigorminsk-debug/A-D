package com.arena.bpdiary

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.arena.bpdiary.databinding.FragmentAboutBinding

class AboutFragment : Fragment() {

    private var _b: FragmentAboutBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentAboutBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    (activity as? MainActivity)?.closeAbout()
                }
            }
        )
        b.btnBack.setOnClickListener { (activity as? MainActivity)?.closeAbout() }
        bindFont()
        b.btnDevCall.setOnClickListener {
            openExternal(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+375293371412")), R.string.about_dev_call_fail)
        }
        b.btnDevMail.setOnClickListener {
            openExternal(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:ziv@csl.by")), R.string.about_dev_mail_fail)
        }
        b.btnUpdate.setOnClickListener {
            (activity as? androidx.appcompat.app.AppCompatActivity)?.let { AppUpdater.start(it, manual = true) }
        }
        b.btnUpdateToken.setOnClickListener {
            (activity as? androidx.appcompat.app.AppCompatActivity)?.let { AppUpdater.showTokenDialog(it) }
        }
        try {
            val p = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else {
                @Suppress("DEPRECATION")
                p.versionCode.toLong()
            }
            b.tvVersion.text = getString(R.string.about_version, p.versionName ?: "", code)
        } catch (_: Exception) {
        }
    }

    private fun bindFont() {
        val step = FontScale.step(requireContext())
        val buttons = listOf(b.btnFont0, b.btnFont1, b.btnFont2, b.btnFont3)
        val primary = ContextCompat.getColor(requireContext(), R.color.primary)
        val onPrimary = ContextCompat.getColor(requireContext(), R.color.onPrimary)
        val surface = ContextCompat.getColor(requireContext(), R.color.white)
        buttons.forEachIndexed { index, btn ->
            val on = index == step
            val label = getString(FontScale.labelIds[index])
            btn.text = if (on) "✓ $label" else label
            btn.backgroundTintList = ColorStateList.valueOf(if (on) primary else surface)
            btn.setTextColor(if (on) onPrimary else primary)
            btn.strokeWidth = if (on) 0 else resources.displayMetrics.density.toInt().coerceAtLeast(1)
            btn.strokeColor = ColorStateList.valueOf(primary)
            btn.setOnClickListener {
                if (FontScale.step(requireContext()) == index) return@setOnClickListener
                FontScale.setStep(requireContext(), index)
                activity?.recreate()
            }
        }
    }

    private fun openExternal(intent: Intent, fail: Int) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), fail, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(requireContext(), fail, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
