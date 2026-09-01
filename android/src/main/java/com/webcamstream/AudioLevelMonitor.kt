package com.webcamstream

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Lightweight microphone level monitor for the React Native audio meter.
 *
 * This does not feed stream audio. It only samples the currently selected
 * microphone enough to provide a stable level/decibel UI signal.
 */
class AudioLevelMonitor(
    private val context: Context,
    private val onLevel: (level: Double, decibels: Double) -> Unit
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.w(TAG, "Microphone permission not granted; audio meter disabled")
            return
        }

        running = true
        thread = HandlerThread("BrowserCamAudioLevel").also { worker ->
            worker.start()
            handler = Handler(worker.looper)
            handler?.post { readLoop() }
        }
    }

    fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        handler = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
            // The recorder may already be stopped if initialization failed.
        }
        audioRecord?.release()
        audioRecord = null

        thread?.quitSafely()
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun readLoop() {
        val sampleRate = SAMPLE_RATE
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            Log.w(TAG, "Audio meter unavailable: invalid minimum buffer size $minBufferSize")
            running = false
            return
        }

        val bufferSize = (minBufferSize * 2).coerceAtLeast(sampleRate / 5)
        val buffer = ShortArray(bufferSize / 2)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (error: Exception) {
            Log.w(TAG, "Audio meter could not create AudioRecord", error)
            running = false
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "Audio meter could not initialize AudioRecord")
            recorder.release()
            running = false
            return
        }

        audioRecord = recorder

        try {
            recorder.startRecording()
        } catch (error: Exception) {
            Log.w(TAG, "Audio meter could not start AudioRecord", error)
            recorder.release()
            audioRecord = null
            running = false
            return
        }

        while (running) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read > 0) {
                emitLevel(buffer, read)
            }

            try {
                Thread.sleep(EMIT_INTERVAL_MS)
            } catch (_: InterruptedException) {
                running = false
            }
        }
    }

    private fun emitLevel(buffer: ShortArray, samples: Int) {
        var sumSquares = 0.0
        for (index in 0 until samples) {
            val normalized = buffer[index] / Short.MAX_VALUE.toDouble()
            sumSquares += normalized * normalized
        }

        val rms = sqrt(sumSquares / samples.coerceAtLeast(1))
        if (rms <= MIN_VALID_LEVEL) {
            return
        }

        val level = rms.coerceIn(0.0, 1.0)
        val decibels = (20.0 * log10(level)).coerceIn(MIN_DECIBELS, 0.0)
        onLevel(level, decibels)
    }

    companion object {
        private const val TAG = "AudioLevelMonitor"
        private const val SAMPLE_RATE = 44_100
        private const val EMIT_INTERVAL_MS = 80L
        private const val MIN_VALID_LEVEL = 0.000_5
        private const val MIN_DECIBELS = -60.0
    }
}
