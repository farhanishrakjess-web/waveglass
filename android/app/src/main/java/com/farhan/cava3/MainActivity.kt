package com.farhan.cava3

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * Main Activity for cava3 real-time audio visualizer.
 *
 * Implements:
 * - Plain Android framework Activity (zero AndroidX dependencies).
 * - System Audio capture (AudioPlaybackCapture) default with fallback to Microphone.
 * - Runtime permissions for RECORD_AUDIO and POST_NOTIFICATIONS (API 33+).
 * - Orientation cycling: Auto-rotate -> Portrait -> Landscape with persistence.
 * - FLAG_KEEP_SCREEN_ON for continuous visualizer display.
 * - Seamless configChanges handling without audio capture restarts.
 * - Gain control slider (-20 dB to +30 dB) with live label and persistence.
 */
class MainActivity : Activity(), AudioCaptureService.Listener {

    companion object {
        private const val REQUEST_PERMISSIONS = 101
        private const val REQUEST_MEDIA_PROJECTION = 102

        private const val PREFS_NAME = "cava3_prefs"
        private const val KEY_ORIENTATION = "key_orientation"
        private const val KEY_GAIN = "key_gain"

        private const val ORIENTATION_AUTO = 0
        private const val ORIENTATION_PORTRAIT = 1
        private const val ORIENTATION_LANDSCAPE = 2
    }

    private lateinit var visualizerView: VisualizerView
    private lateinit var btnSource: Button
    private lateinit var btnView: Button
    private lateinit var tvGain: TextView
    private lateinit var sbGain: SeekBar
    private lateinit var prefs: SharedPreferences

    private var captureService: AudioCaptureService? = null
    private var isBound = false
    private var orientationMode = ORIENTATION_AUTO
    private var currentGainDb = 12

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioCaptureService.LocalBinder
            val s = binder.getService()
            captureService = s
            isBound = true
            s.listener = this@MainActivity
            s.processor.userGainDb = currentGainDb.toFloat()

            // Update UI with initial service source
            updateSourceButton(s.currentSource)

            // Start capture if permissions are ready
            ensureCaptureStarted()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureService?.listener = null
            captureService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on continuously while cava3 visualizer is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        visualizerView = findViewById(R.id.visualizerView)
        btnSource = findViewById(R.id.btnSource)
        btnView = findViewById(R.id.btnView)
        tvGain = findViewById(R.id.tvGain)
        sbGain = findViewById(R.id.sbGain)

        // Restore persisted orientation and gain
        loadPreferences()

        setupControls()
        checkAndRequestPermissions()
        bindCaptureService()
    }

    private fun loadPreferences() {
        orientationMode = prefs.getInt(KEY_ORIENTATION, ORIENTATION_AUTO)
        applyOrientation(orientationMode)

        currentGainDb = prefs.getInt(KEY_GAIN, 12)
        // SeekBar range: 0..50 maps to -20 dB .. +30 dB
        val progress = currentGainDb + 20
        sbGain.progress = progress
        tvGain.text = getString(R.string.gain_format, currentGainDb)
    }

    private fun setupControls() {
        // Source toggle button: System audio <-> Microphone
        btnSource.setOnClickListener {
            val service = captureService ?: return@setOnClickListener
            if (service.currentSource == AudioCaptureService.AudioSourceType.SYSTEM_PLAYBACK) {
                // Switch to Microphone
                service.startMicCapture()
            } else {
                // Switch back to System Audio
                requestSystemCaptureProjection()
            }
        }

        // View toggle button: Auto-rotate -> Portrait -> Landscape
        btnView.setOnClickListener {
            orientationMode = (orientationMode + 1) % 3
            prefs.edit().putInt(KEY_ORIENTATION, orientationMode).apply()
            applyOrientation(orientationMode)
        }

        // Gain slider (-20 dB to +30 dB, default +12 dB)
        sbGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val gainDb = progress - 20
                currentGainDb = gainDb
                tvGain.text = getString(R.string.gain_format, gainDb)
                captureService?.processor?.userGainDb = gainDb.toFloat()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit().putInt(KEY_GAIN, currentGainDb).apply()
            }
        })
    }

    private fun applyOrientation(mode: Int) {
        when (mode) {
            ORIENTATION_AUTO -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                btnView.text = getString(R.string.view_auto)
            }
            ORIENTATION_PORTRAIT -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                btnView.text = getString(R.string.view_portrait)
            }
            ORIENTATION_LANDSCAPE -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                btnView.text = getString(R.string.view_landscape)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val neededPermissions = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (neededPermissions.isNotEmpty()) {
            requestPermissions(neededPermissions.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val recordGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (recordGranted) {
                ensureCaptureStarted()
            } else {
                Toast.makeText(this, "RECORD_AUDIO permission is required for visualizer", Toast.LENGTH_LONG).show()
                visualizerView.statusMessage = "Microphone / audio permission denied"
                visualizerView.postInvalidate()
            }
        }
    }

    private fun bindCaptureService() {
        val intent = Intent(this, AudioCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun ensureCaptureStarted() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        // Start default: System Audio
        requestSystemCaptureProjection()
    }

    private fun requestSystemCaptureProjection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (projectionManager != null) {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        } else {
            fallbackToMic("System audio projection unavailable on device")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                visualizerView.statusMessage = null
                captureService?.startSystemCapture(resultCode, data)
                updateSourceButton(AudioCaptureService.AudioSourceType.SYSTEM_PLAYBACK)
            } else {
                fallbackToMic("System capture permission denied: falling back to Microphone")
            }
        }
    }

    private fun fallbackToMic(reason: String) {
        Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
        captureService?.startMicCapture()
        updateSourceButton(AudioCaptureService.AudioSourceType.MICROPHONE)
    }

    private fun updateSourceButton(source: AudioCaptureService.AudioSourceType) {
        if (source == AudioCaptureService.AudioSourceType.SYSTEM_PLAYBACK) {
            btnSource.text = getString(R.string.source_system)
        } else {
            btnSource.text = getString(R.string.source_mic)
        }
    }

    // AudioCaptureService.Listener callbacks
    override fun onAudioFrame(normData: FloatArray, peakFreq: Float, peakDb: Float, hasSignal: Boolean) {
        visualizerView.updateData(normData, peakFreq, peakDb, hasSignal)
    }

    override fun onCaptureError(errorMessage: String) {
        visualizerView.statusMessage = errorMessage
        visualizerView.postInvalidate()
    }

    override fun onSourceChanged(newSource: AudioCaptureService.AudioSourceType) {
        updateSourceButton(newSource)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
