package com.arena.bpdiary

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object Notifications {

    const val CHANNEL_ID = "bp_reminders"
    const val MED_CHANNEL_ID = "med_reminders"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = ctx.getString(R.string.channel_desc) }
            nm.createNotificationChannel(ch)

            val ch2 = NotificationChannel(
                MED_CHANNEL_ID,
                ctx.getString(R.string.med_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = ctx.getString(R.string.med_channel_desc) }
            nm.createNotificationChannel(ch2)
        }
    }

    private fun contentIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun build(ctx: Context, channelId: String, title: String, text: String) =
        NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(ctx))
            .build()

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, id: Int, title: String, text: String) {
        try {
            ensureChannel(ctx)
            androidx.core.app.NotificationManagerCompat.from(ctx)
                .notify(id, build(ctx, CHANNEL_ID, title, text))
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    fun showMed(ctx: Context, id: Int, name: String, dose: String) {
        try {
            ensureChannel(ctx)
            val title = if (dose.isNotBlank()) "$name ($dose)" else name
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(
                id,
                build(
                    ctx, MED_CHANNEL_ID,
                    ctx.getString(R.string.notif_med_title_fmt, title),
                    ctx.getString(R.string.notif_med_text)
                )
            )
        } catch (_: Exception) {
        }
    }
}
