package com.example.myrecorder

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.myrecorder.databinding.ActivityMainBinding
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var vb: ActivityMainBinding
    private val uiHandler = Handler(Looper.getMainLooper())
    private var ticking = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_STATUS) return
            val isRec = intent.getBooleanExtra(EXTRA_IS_RECORDING, false)
            val start = intent.getLongExtra(EXTRA_START_TIME, 0L)
            val uri = intent.getStringExtra(EXTRA_SAVED_URI)
            val err = intent.getStringExtra(EXTRA_ERROR)

            if (isRec) {
                if (start > 0) RecorderState.startTimeMs = start
                RecorderState.isRecording = true
                vb.btnStop.isEnabled = true
                setStatus(true)
            } else {
                RecorderState.isRecording = false
                vb.btnStop.isEnabled = false
                setStatus(false)
                if (!err.isNullOrBlank()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Recording stopped")
                        .setMessage(err)
                        .setPositiveButton("OK", null)
                        .show()
                } else if (!uri.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, "Saved: $uri", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        vb.btnStop.isEnabled = false

        vb.btnStart.setOnClickListener {
            if (!hasAllNeededPermissions()) {
                requestAllNeededPermissions()
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("Start recording?")
                .setMessage("This will start recording and show a persistent notification until you stop.")
                .setPositiveButton("Start") { _, _ ->
                    startRecordingFlow(vb.cbAudio.isChecked)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        vb.btnStop.setOnClickListener { stopRecording() }

        vb.btnPip.setOnClickListener {
            if (RecorderState.isRecording) {
                // Bring recorder to front; it will PiP automatically on Home/Back
                startActivity(Intent(this, RecorderActivity::class.java).apply {
                    putExtra(RecorderActivity.EXTRA_WITH_AUDIO, vb.cbAudio.isChecked)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                })
            } else {
                Toast.makeText(this, "Start recording first, then use PiP.", Toast.LENGTH_SHORT).show()
            }
        }

        startTicking()
        syncUiFromState()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(receiver, IntentFilter(ACTION_STATUS))
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(receiver)
    }

    private fun startRecordingFlow(withAudio: Boolean) {
        // Set state immediately so timer starts even if broadcast is delayed
        RecorderState.isRecording = true
        RecorderState.startTimeMs = System.currentTimeMillis()

        // Start foreground notification service (policy compliance)
        ContextCompat.startForegroundService(
            this,
            Intent(this, RecordingService::class.java).apply { action = RecordingService.ACTION_START }
        )

        // Open recorder UI (Samsung-friendly: keep camera in use; supports PiP)
        startActivity(Intent(this, RecorderActivity::class.java).apply {
            putExtra(RecorderActivity.EXTRA_WITH_AUDIO, withAudio)
        })

        vb.btnStop.isEnabled = true
        setStatus(true)
    }

    private fun stopRecording() {
        startService(Intent(this, RecordingService::class.java).apply { action = RecordingService.ACTION_STOP })
    }

    private fun syncUiFromState() {
        vb.btnStop.isEnabled = RecorderState.isRecording
        setStatus(RecorderState.isRecording)
    }

    private fun setStatus(isRecording: Boolean) {
        vb.tvStatus.text = "Status: " + if (isRecording) "Recording" else "Stopped"
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        uiHandler.post(tickRunnable)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking) return
            if (RecorderState.isRecording && RecorderState.startTimeMs > 0) {
                val elapsed = System.currentTimeMillis() - RecorderState.startTimeMs
                vb.tvTimer.text = "Elapsed: ${formatMmSs(elapsed)}"
            } else {
                vb.tvTimer.text = "Elapsed: 00:00"
            }
            uiHandler.postDelayed(this, 500)
        }
    }

    private fun formatMmSs(ms: Long): String {
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun hasAllNeededPermissions(): Boolean {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (vb.cbAudio.isChecked) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        return needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestAllNeededPermissions() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (vb.cbAudio.isChecked) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!ok) Toast.makeText(this, "Permissions denied. Cannot record.", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQ_PERMS = 10
        const val ACTION_STATUS = "com.example.myrecorder.action.STATUS"
        const val EXTRA_IS_RECORDING = "extra_is_recording"
        const val EXTRA_START_TIME = "extra_start_time"
        const val EXTRA_SAVED_URI = "extra_saved_uri"
        const val EXTRA_ERROR = "extra_error"
    }
}
