package com.example.myrecorder

import android.content.Intent
import androidx.lifecycle.LifecycleService

class RecordingService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!RecorderState.isRecording) {
                    RecorderState.isRecording = true
                    RecorderState.startTimeMs = System.currentTimeMillis()
                }
                startForeground(NotificationHelper.NOTIF_ID, NotificationHelper.buildRecordingNotification(this))
            }
            ACTION_STOP -> {
                sendBroadcast(Intent(ACTION_STOP_BROADCAST))
                RecorderState.isRecording = false
                RecorderState.startTimeMs = 0L
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    companion object {
        const val ACTION_START = "com.example.myrecorder.action.START_FGS"
        const val ACTION_STOP = "com.example.myrecorder.action.STOP_FGS"
        const val ACTION_STOP_BROADCAST = "com.example.myrecorder.action.STOP_RECORDING"
    }
}
