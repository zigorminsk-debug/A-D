package com.arena.bpdiary

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.arena.bpdiary.databinding.ActivityReminderBannerBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Баннер напоминания о событии: всплывает на рабочем столе / поверх других приложений.
 * Исчезает по нажатию:
 *  - «Открыть» — открывает нужный экран дневника (добавление давления или отметка лекарства);
 *  - «Отложить на 30 минут» — закрывает баннер и планирует повтор через 30 минут со звуком.
 */
class ReminderBannerActivity : AppCompatActivity() {

    private lateinit var b: ActivityReminderBannerBinding

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FontScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeScreen()

        b = ActivityReminderBannerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val id = intent.getIntExtra(EXTRA_ID, 0)
        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_MEASURE
        val hour = intent.getIntExtra(EXTRA_HOUR, 8)
        val minute = intent.getIntExtra(EXTRA_MINUTE, 0)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val dose = intent.getStringExtra(EXTRA_DOSE).orEmpty()

        val timeStr = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
        b.tvTime.text = timeStr

        if (type == TYPE_MED) {
            b.tvBadge.text = getString(R.string.reminder_banner_badge_med)
            b.ivIcon.setImageResource(R.drawable.ic_pill)
            val titleText = if (dose.isNotBlank()) "$name ($dose)" else name
            b.tvTitle.text = getString(R.string.notif_med_title_fmt, titleText)
            b.tvActionHint.text = getString(R.string.reminder_banner_action_med)
        } else {
            b.tvBadge.text = getString(R.string.reminder_banner_badge_measure)
            b.ivIcon.setImageResource(R.drawable.ic_heart)
            b.tvTitle.text = getString(R.string.notify_title)
            b.tvActionHint.text = getString(R.string.reminder_banner_action_measure)
        }

        b.btnOpen.setOnClickListener {
            // Закрываем уведомление в шторке
            try {
                androidx.core.app.NotificationManagerCompat.from(this).cancel(id)
            } catch (_: Exception) {
            }

            val main = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (type == TYPE_MED) {
                    putExtra(MainActivity.EXTRA_OPEN_TAB, R.id.action_meds)
                    putExtra(MainActivity.EXTRA_MED_ID, id)
                } else {
                    putExtra(MainActivity.EXTRA_OPEN_TAB, R.id.action_diary)
                    putExtra(MainActivity.EXTRA_OPEN_NEW_RECORD, true)
                }
            }
            startActivity(main)
            // Закрываем баннер мгновенно и без анимации задержки
            finishAndRemoveTask()
        }

        b.btnSnooze.setOnClickListener {
            try {
                androidx.core.app.NotificationManagerCompat.from(this).cancel(id)
            } catch (_: Exception) {
            }

            ReminderScheduler.scheduleSnooze(
                ctx = applicationContext,
                type = type,
                id = id,
                hour = hour,
                minute = minute,
                name = name,
                dose = dose,
                delayMinutes = 30
            )
            Toast.makeText(this, R.string.reminder_banner_snoozed_toast, Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
        }

        // Клик по фону вне карточки также закрывает баннер
        b.root.setOnClickListener { finishAndRemoveTask() }
        b.cardReminder.setOnClickListener { /* не закрывать при клике на саму карточку */ }
    }

    private fun wakeScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    companion object {
        const val EXTRA_TYPE = "type"
        const val EXTRA_ID = "id"
        const val EXTRA_HOUR = "hour"
        const val EXTRA_MINUTE = "minute"
        const val EXTRA_NAME = "name"
        const val EXTRA_DOSE = "dose"

        const val TYPE_MEASURE = "measure"
        const val TYPE_MED = "med"

        fun createIntent(
            ctx: Context,
            type: String,
            id: Int,
            hour: Int,
            minute: Int,
            name: String = "",
            dose: String = ""
        ): Intent = Intent(ctx, ReminderBannerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_HOUR, hour)
            putExtra(EXTRA_MINUTE, minute)
            putExtra(EXTRA_NAME, name)
            putExtra(EXTRA_DOSE, dose)
        }
    }
}
