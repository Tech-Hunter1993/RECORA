package com.example.myrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_ID = "recording_channel"
    private const val CHANNEL_NAME = "Video Recording"
    const val NOTIF_ID = 1001

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows ongoing recording status"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    fun buildRecordingNotification(ctx: Context): Notification {
        val stopIntent = Intent(ctx, RecordingService::class.java).apply { action = RecordingService.ACTION_STOP }
        val stopPi = PendingIntent.getService(
            ctx, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(ctx, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            ctx, 1, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Recording video…")
            .setContentText("Tap to open. Use STOP to end recording.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "STOP", stopPi)
            .build()
    }
}
