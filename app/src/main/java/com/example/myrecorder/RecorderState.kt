package com.example.myrecorder

object RecorderState {
    @Volatile var isRecording: Boolean = false
    @Volatile var startTimeMs: Long = 0L
}
