package com.example.sol.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object SolNotificationHelper {
    const val CHANNEL_REMINDERS = "sol_reminders_channel"
    const val CHANNEL_BACKGROUND = "sol_background_channel"
    const val CHANNEL_READER = "sol_reader_channel"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channels = listOf(
            NotificationChannel(
                CHANNEL_REMINDERS,
                "یادآورها و روتین‌های Sol",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "اعلان‌های یادآوری و روتین‌های زمان‌بندی‌شده Sol"
            },
            NotificationChannel(
                CHANNEL_BACKGROUND,
                "کارهای پس‌زمینه Sol",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "گزارش اجرای وظایف پس‌زمینه مانند پشتیبان‌گیری و پایش باتری"
            },
            NotificationChannel(
                CHANNEL_READER,
                "روخوانی صوتی Sol",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "سرویس خواندن صوتی کتاب‌ها و گزارش‌ها"
            },
        )
        channels.forEach { nm.createNotificationChannel(it) }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun postNotification(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        body: String,
    ) {
        ensureChannels(context)
        if (!hasNotificationPermission(context)) return

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentPending)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Permission may be revoked on Android 13+; fail safely without crashing.
        }
    }
}
