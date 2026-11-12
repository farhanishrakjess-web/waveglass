package com.farhan.cava3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service managing audio capture (System Audio via AudioPlaybackCapture or Microphone).
 *
 * Implements:
 * - MediaProjection foreground service type for Android 14+ compliance.
 * - MediaProjection.Callback registration (mandatory on API 34+).
 * - Matching AudioAttributes: USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN.
 * - Clean source switching with monotonic session ID guarding against onDestroy races.
 * - Non-blocking audio capture loop with high-performance FFT processing.
 */
class AudioCaptureService : Service() {

    enum class AudioSourceType {
        SYSTEM_PLAYBACK,
        MICROPHONE
    }

    interface Listener {
        fun onAudioFrame(normData: FloatArray, peakFreq: Float, peakDb: Float, hasSignal: Boolean)
        fun onCaptureError(errorMessage: String)
        fun onSourceChanged(newSource: AudioSourceType)
    }

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val CHANNEL_ID = "cava3_audio_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_SYSTEM = "com.farhan.cava3.START_SYSTEM"
        const val ACTION_START_MIC = "com.farhan.cava3.START_MIC"
        const val ACTION_STOP = "com.farhan.cava3.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        // Audio format: 44.1 kHz mono 16-bit PCM
        const val SAMPLE_RATE = AudioProcessor.SAMPLE_RATE
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    val processor = AudioProcessor()

    var listener: Listener? = null
    var currentSource: AudioSourceType = AudioSourceType.SYSTEM_PLAYBACK
        private set

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var activeMediaProjection: MediaProjection? = null
    private var activeAudioRecord: AudioRecord? = null

    private var captureThread: Thread? = null
    private val isCapturing = AtomicBoolean(false)
    private val currentSessionId = AtomicLong(0)

    // Cached MediaProjection intent data for re-starting system capture
    private var lastResultCode = 0
    private var lastResultData: Intent? = null

    inner class LocalBinder : Binder() {
        fun getService(): AudioCaptureService = this@AudioCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()

        val action = intent?.action ?: return START_STICKY

        when (action) {
            ACTION_START_SYSTEM -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != 0 && resultData != null) {
                    lastResultCode = resultCode
                    lastResultData = resultData
                    startSystemCapture(resultCode, resultData)
                } else if (lastResultCode != 0 && lastResultData != null) {
                    startSystemCapture(lastResultCode, lastResultData!!)
                } else {
                    notifyError("No MediaProjection permission granted")
                }
            }
            ACTION_START_MIC -> {
                startMicCapture()
            }
            ACTION_STOP -> {
                stopCurrentCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_text)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = notificationBuilder
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Start System Audio capture using AudioPlaybackCapture API.
     */
    fun startSystemCapture(resultCode: Int, resultData: Intent) {
        val sessionId = currentSessionId.incrementAndGet()
        stopCurrentCapture()

        currentSource = AudioSourceType.SYSTEM_PLAYBACK
        mainHandler.post { listener?.onSourceChanged(AudioSourceType.SYSTEM_PLAYBACK) }

        try {
            val mp = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            if (mp == null) {
                notifyError("Failed to obtain MediaProjection")
                return
            }
            activeMediaProjection = mp

            // Android 14+ (API 34) strictly requires registering MediaProjection.Callback
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system")
                    if (currentSessionId.get() == sessionId) {
                        notifyError("System audio capture stopped")
                    }
                }
            }, mainHandler)

            // AudioPlaybackCapture configuration matching media, games, and unknown usages
            val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING)
            val bufferSize = maxOf(minBufferSize, AudioProcessor.HOP_SIZE * 4)

            val audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                notifyError("AudioRecord initialization failed")
                return
            }

            activeAudioRecord = audioRecord
            startCaptureLoop(audioRecord, sessionId)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting system capture", e)
            notifyError("System capture error: ${e.message}")
        }
    }

    /**
     * Start Microphone audio capture using standard AudioRecord.
     */
    fun startMicCapture() {
        val sessionId = currentSessionId.incrementAndGet()
        stopCurrentCapture()

        currentSource = AudioSourceType.MICROPHONE
        mainHandler.post { listener?.onSourceChanged(AudioSourceType.MICROPHONE) }

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING)
            val bufferSize = maxOf(minBufferSize, AudioProcessor.HOP_SIZE * 4)

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_ENCODING,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                notifyError("Microphone initialization failed")
                return
            }

            activeAudioRecord = audioRecord
            startCaptureLoop(audioRecord, sessionId)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting mic capture", e)
            notifyError("Mic capture error: ${e.message}")
        }
    }

    /**
     * Shared audio capture loop running in background thread.
     */
    private fun startCaptureLoop(record: AudioRecord, sessionId: Long) {
        isCapturing.set(true)

        val thread = Thread {
            val pcmBuffer = ShortArray(AudioProcessor.HOP_SIZE)

            try {
                record.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting recording", e)
                if (currentSessionId.get() == sessionId) {
                    notifyError("Record start error: ${e.message}")
                }
                return@Thread
            }

            while (isCapturing.get() && currentSessionId.get() == sessionId) {
                val read = record.read(pcmBuffer, 0, pcmBuffer.size, AudioRecord.READ_BLOCKING)
                if (read > 0 && currentSessionId.get() == sessionId) {
                    val frameReady = processor.processSamples(pcmBuffer, read)
                    if (frameReady) {
                        val norm = processor.smoothedNorm
                        val peakFreq = processor.peakFrequency
                        val peakDb = processor.peakDb
                        val hasSignal = processor.hasSignal

                        mainHandler.post {
                            if (currentSessionId.get() == sessionId) {
                                listener?.onAudioFrame(norm, peakFreq, peakDb, hasSignal)
                            }
                        }
                    }
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord read error: $read")
                    break
                }
            }

            try {
                record.stop()
                record.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioRecord", e)
            }
        }

        captureThread = thread
        thread.start()
    }

    fun stopCurrentCapture() {
        isCapturing.set(false)
        try {
            captureThread?.interrupt()
            captureThread = null
        } catch (_: Exception) {}

        try {
            activeAudioRecord?.let {
                if (it.state == AudioRecord.STATE_INITIALIZED) {
                    it.stop()
                }
                it.release()
            }
            activeAudioRecord = null
        } catch (_: Exception) {}

        try {
            activeMediaProjection?.stop()
            activeMediaProjection = null
        } catch (_: Exception) {}
    }

    private fun notifyError(message: String) {
        mainHandler.post {
            listener?.onCaptureError(message)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCurrentCapture()
    }
}
