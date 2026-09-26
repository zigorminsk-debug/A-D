package com.arena.bpdiary

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

object Notifications {

    const val CHANNEL_ID = "bp_reminders"

    /**
     * Новый id: звук канала нельзя поменять у уже созданного «med_reminders».
     * Иначе обновление осталось бы без сигнала.
     */
    const val MED_CHANNEL_ID = "med_reminders_v2"
    private const val OLD_MED_CHANNEL_ID = "med_reminders"

    private val medVibration = longArrayOf(0, 400, 180, 400, 180, 600)

    fun medSoundUri(ctx: Context): Uri =
        Uri.parse("android.resource://${ctx.packageName}/${R.raw.med_signal}")

    private fun alarmAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.getString(R.string.channel_desc)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)

            nm.deleteNotificationChannel(OLD_MED_CHANNEL_ID)
            val med = NotificationChannel(
                MED_CHANNEL_ID,
                ctx.getString(R.string.med_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.getString(R.string.med_channel_desc)
                setSound(medSoundUri(ctx), alarmAttributes())
                enableVibration(true)
                vibrationPattern = medVibration
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(med)
        } catch (_: Exception) {
        }
    }

    private fun openPendingIntent(ctx: Context, type: String, id: Int): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (type == ReminderBannerActivity.TYPE_MED) {
                putExtra(MainActivity.EXTRA_OPEN_TAB, R.id.action_meds)
                putExtra(MainActivity.EXTRA_MED_ID, id)
            } else {
                putExtra(MainActivity.EXTRA_OPEN_TAB, R.id.action_diary)
                putExtra(MainActivity.EXTRA_OPEN_NEW_RECORD, true)
            }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(ctx, id, intent, flags)
    }

    private fun snoozePendingIntent(
        ctx: Context,
        type: String,
        id: Int,
        hour: Int,
        minute: Int,
        name: String,
        dose: String
    ): PendingIntent {
        val intent = Intent(ctx, SnoozeReceiver::class.java).apply {
            putExtra(ReminderBannerActivity.EXTRA_TYPE, type)
            putExtra(ReminderBannerActivity.EXTRA_ID, id)
            putExtra(ReminderBannerActivity.EXTRA_HOUR, hour)
            putExtra(ReminderBannerActivity.EXTRA_MINUTE, minute)
            putExtra(ReminderBannerActivity.EXTRA_NAME, name)
            putExtra(ReminderBannerActivity.EXTRA_DOSE, dose)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val requestCode = if (id != 0) kotlin.math.abs(id) + 50000 else 59999
        return PendingIntent.getBroadcast(ctx, requestCode, intent, flags)
    }

    private fun bannerPendingIntent(
        ctx: Context,
        type: String,
        id: Int,
        hour: Int,
        minute: Int,
        name: String,
        dose: String
    ): PendingIntent {
        val intent = ReminderBannerActivity.createIntent(ctx, type, id, hour, minute, name, dose)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val requestCode = if (id != 0) kotlin.math.abs(id) + 10000 else 19999
        return PendingIntent.getActivity(ctx, requestCode, intent, flags)
    }

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, id: Int, title: String, text: String, hour: Int = 8, minute: Int = 0) {
        try {
            ensureChannel(ctx)
            val openPi = openPendingIntent(ctx, ReminderBannerActivity.TYPE_MEASURE, id)
            val snoozePi = snoozePendingIntent(ctx, ReminderBannerActivity.TYPE_MEASURE, id, hour, minute, "", "")
            val bannerPi = bannerPendingIntent(ctx, ReminderBannerActivity.TYPE_MEASURE, id, hour, minute, "", "")

            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openPi)
                .setFullScreenIntent(bannerPi, true)
                .addAction(
                    R.drawable.ic_open_in_new,
                    ctx.getString(R.string.reminder_banner_open),
                    openPi
                )
                .addAction(
                    R.drawable.ic_snooze,
                    ctx.getString(R.string.reminder_banner_snooze),
                    snoozePi
                )

            androidx.core.app.NotificationManagerCompat.from(ctx).notify(id, builder.build())
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    fun showMed(ctx: Context, id: Int, name: String, dose: String, hour: Int = 8, minute: Int = 0) {
        try {
            ensureChannel(ctx)
            val title = if (dose.isNotBlank()) "$name ($dose)" else name
            val text = ctx.getString(R.string.notif_med_text)

            val openPi = openPendingIntent(ctx, ReminderBannerActivity.TYPE_MED, id)
            val snoozePi = snoozePendingIntent(ctx, ReminderBannerActivity.TYPE_MED, id, hour, minute, name, dose)
            val bannerPi = bannerPendingIntent(ctx, ReminderBannerActivity.TYPE_MED, id, hour, minute, name, dose)

            val builder = NotificationCompat.Builder(ctx, MED_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.notif_med_title_fmt, title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openPi)
                .setFullScreenIntent(bannerPi, true)
                .addAction(
                    R.drawable.ic_open_in_new,
                    ctx.getString(R.string.reminder_banner_open),
                    openPi
                )
                .addAction(
                    R.drawable.ic_snooze,
                    ctx.getString(R.string.reminder_banner_snooze),
                    snoozePi
                )

            if (Build.VERSION.SDK_INT < 26) {
                @Suppress("DEPRECATION")
                builder.setSound(medSoundUri(ctx), AudioManager.STREAM_ALARM)
                builder.setVibrate(medVibration)
            }
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(id, builder.build())
        } catch (_: Exception) {
        }
    }

    /**
     * Проверка сигнала с экрана «Таблетки». true, если громкость будильника на нуле.
     * Само напоминание звучит через канал уведомления, тем же файлом.
     */
    fun playMedSignal(ctx: Context): Boolean {
        val silent = try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getStreamVolume(AudioManager.STREAM_ALARM) == 0
        } catch (_: Exception) {
            false
        }
        try {
            val ringtone: Ringtone? = RingtoneManager.getRingtone(ctx.applicationContext, medSoundUri(ctx))
            ringtone?.audioAttributes = alarmAttributes()
            ringtone?.play()
        } catch (_: Exception) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java)
                    ?.defaultVibrator
                    ?.vibrate(VibrationEffect.createWaveform(medVibration, -1))
            } else {
                val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(medVibration, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(medVibration, -1)
                }
            }
        } catch (_: Exception) {
        }
        return silent
    }
}
