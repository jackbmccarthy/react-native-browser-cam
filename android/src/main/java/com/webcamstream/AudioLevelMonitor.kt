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
    private val stateLock = Any()
    private val recorderLock = Any()
    private var generation = 0L
    private var running = false

    fun start() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.w(TAG, "Microphone permission not granted; audio meter disabled")
            return
        }

        synchronized(stateLock) {
            if (running) return
            running = true
            val workerGeneration = ++generation
            val worker = HandlerThread("BrowserCamAudioLevel")
            thread = worker
            worker.start()
            handler = Handler(worker.looper)
            if (handler?.post { readLoop(worker, workerGeneration) } != true) {
                running = false
                handler = null
                thread = null
                worker.quitSafely()
            }
        }
    }

    fun stop() {
        synchronized(stateLock) {
            running = false
            generation++
            handler?.removeCallbacksAndMessages(null)
            handler = null
            thread?.quitSafely()
            thread = null
        }
    }

    private fun isCurrent(worker: HandlerThread, workerGeneration: Long): Boolean =
        synchronized(stateLock) { running && thread === worker && generation == workerGeneration }

    @SuppressLint("MissingPermission")
    private fun readLoop(worker: HandlerThread, workerGeneration: Long) {
        // A replacement worker waits until the previous worker releases its local recorder.
        synchronized(recorderLock) {
            var recorder: AudioRecord? = null
            try {
                if (!isCurrent(worker, workerGeneration)) return
                val sampleRate = SAMPLE_RATE
                val minBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBufferSize <= 0) {
                    Log.w(TAG, "Audio meter unavailable: invalid minimum buffer size $minBufferSize")
                    return
                }

                val bufferSize = (minBufferSize * 2).coerceAtLeast(sampleRate / 5)
                val buffer = ShortArray(bufferSize / 2)
                val activeRecorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                recorder = activeRecorder
                if (activeRecorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "Audio meter could not initialize AudioRecord")
                    return
                }

                synchronized(stateLock) {
                    if (!isCurrent(worker, workerGeneration)) return
                    activeRecorder.startRecording()
                }

                while (isCurrent(worker, workerGeneration)) {
                    val read = activeRecorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    if (read < 0) {
                        Log.w(TAG, "Audio meter read failed")
                        break
                    }
                    if (read > 0 && isCurrent(worker, workerGeneration)) emitLevel(buffer, read)
                    Thread.sleep(EMIT_INTERVAL_MS)
                }
            } catch (_: Exception) {
                Log.w(TAG, "Audio meter acquisition or read failed")
            } finally {
                recorder?.let { owned ->
                    try {
                        if (owned.recordingState == AudioRecord.RECORDSTATE_RECORDING) owned.stop()
                    } catch (_: Exception) {
                        Log.w(TAG, "Audio meter stop failed")
                    }
                    try {
                        owned.release()
                    } catch (_: Exception) {
                        Log.w(TAG, "Audio meter release failed")
                    }
                }
                synchronized(stateLock) {
                    if (thread === worker && generation == workerGeneration) {
                        running = false
                        handler = null
                        thread = null
                    }
                }
                worker.quitSafely()
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
