package com.example.myrecorder

import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import com.example.myrecorder.databinding.ActivityRecorderBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderActivity : AppCompatActivity() {

    private lateinit var vb: ActivityRecorderBinding
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var withAudio: Boolean = true
    private var isLocked: Boolean = false
    private var recordingStarted: Boolean = false

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stopRecording()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityRecorderBinding.inflate(layoutInflater)
        setContentView(vb.root)

        // Make PiP visually minimal while keeping camera "in use"
        vb.previewView.alpha = 0.08f

        withAudio = intent.getBooleanExtra(EXTRA_WITH_AUDIO, true)

        registerReceiver(stopReceiver, IntentFilter(RecordingService.ACTION_STOP_BROADCAST))

        setupLockUi()
        setupBackBehavior()

        startCameraAndRecord()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(stopReceiver) } catch (_: Throwable) {}
    }

    private fun setupLockUi() {
        vb.btnLock.setOnClickListener {
            setLocked(true)
        }
        vb.btnUnlock.setOnClickListener {
            setLocked(false)
        }
        setLocked(false)
    }

    private fun setLocked(lock: Boolean) {
        isLocked = lock
        vb.lockOverlay.visibility = if (lock) View.VISIBLE else View.GONE
        vb.btnLock.visibility = if (lock) View.GONE else View.VISIBLE
        vb.tvOverlay.text = if (lock) "Recording… (Locked) Use notification STOP to end." else "Recording… Use PiP to minimize."
    }

    private fun setupBackBehavior() {
        // Back should NOT close recording; it should PiP instead.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (RecorderState.isRecording) {
                    enterPipIfPossible()
                } else {
                    // If not recording, allow normal back
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // User pressed Home or switched apps -> PiP to keep preview visible and avoid Samsung stopping frames
        if (RecorderState.isRecording) {
            enterPipIfPossible()
        }
    }

    private fun enterPipIfPossible() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Throwable) {
                // If PiP fails, just keep activity visible
            }
        }
    }

    override fun finish() {
        // Prevent accidental closing while recording; suggest PiP or STOP
        if (RecorderState.isRecording) {
            AlertDialog.Builder(this)
                .setTitle("Recording in progress")
                .setMessage("Closing the preview will stop recording on many Samsung devices. Use PiP to minimize, or press STOP to end recording.")
                .setPositiveButton("Minimize (PiP)") { _, _ -> enterPipIfPossible() }
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Stop Recording") { _, _ -> stopRecording() }
                .show()
            return
        }
        super.finish()
    }

    private fun startCameraAndRecord() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(vb.previewView.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.SD, Quality.HD, Quality.FHD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    videoCapture
                )
            } catch (t: Throwable) {
                Toast.makeText(this, "Camera bind failed: ${t.message}", Toast.LENGTH_LONG).show()
                super.finish()
                return@addListener
            }

            startRecordingInternal()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecordingInternal() {
        val vc = videoCapture ?: return

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(createVideoContentValues()).build()

        val pending = vc.output.prepareRecording(this, outputOptions).apply {
            if (withAudio) withAudioEnabled()
        }

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordingStarted = true
                    // Auto-minimize to smallest PiP (best effort) for Samsung
                    vb.previewView.postDelayed({ enterPipIfPossible() }, 700)
                    sendBroadcast(Intent(MainActivity.ACTION_STATUS).apply {
                        putExtra(MainActivity.EXTRA_IS_RECORDING, true)
                        putExtra(MainActivity.EXTRA_START_TIME, RecorderState.startTimeMs)
                    })
                }
                is VideoRecordEvent.Finalize -> {
                    val uri = event.outputResults.outputUri
                    if (event.hasError() || uri == Uri.EMPTY) {
                        val msg = "Recording error (${event.error}): ${event.cause?.message ?: "No data produced"}"
                        sendBroadcast(Intent(MainActivity.ACTION_STATUS).apply {
                            putExtra(MainActivity.EXTRA_IS_RECORDING, false)
                            putExtra(MainActivity.EXTRA_ERROR, msg + "\nKeep the preview visible or in PiP while recording on Samsung devices.")
                        })
                    } else {
                        sendBroadcast(Intent(MainActivity.ACTION_STATUS).apply {
                            putExtra(MainActivity.EXTRA_IS_RECORDING, false)
                            putExtra(MainActivity.EXTRA_SAVED_URI, uri.toString())
                        })
                    }
                    stopFgs()
                    // Allow close now
                    RecorderState.isRecording = false
                    super.finish()
                }
                else -> Unit
            }
        }
    }

    private fun stopRecording() {
        try { recording?.stop() } catch (_: Throwable) {}
        recording = null
        stopFgs()
        RecorderState.isRecording = false
        super.finish()
    }

    private fun stopFgs() {
        startService(Intent(this, RecordingService::class.java).apply { action = RecordingService.ACTION_STOP })
    }

    private fun createVideoContentValues(): android.content.ContentValues {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "VID_${ts}.mp4"
        return android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Movies/MyRecorder")
        }
    }

    companion object {
        const val EXTRA_WITH_AUDIO = "extra_with_audio"
    }
}
