package com.wizmusicsync

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val DEFAULT_BULB_IP = "192.168.0.106"
        private const val MAX_DELAY_MS = 500
        private const val DELAY_STEP_MS = 10
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_PROJECTION = 2
    }

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private lateinit var bulbIp: EditText
    private lateinit var delayLabel: TextView
    private lateinit var delay: SeekBar
    private lateinit var toggle: Button
    private lateinit var statusView: TextView
    private var message: String? = null

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateUi()
            ui.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bulbIp = findViewById(R.id.bulbIp)
        delayLabel = findViewById(R.id.delayLabel)
        delay = findViewById(R.id.delay)
        toggle = findViewById(R.id.toggle)
        statusView = findViewById(R.id.status)

        bulbIp.setText(prefs.getString("bulbIp", DEFAULT_BULB_IP))

        val savedDelay = prefs.getInt("delayMs", 0)
        SyncService.delayMs = savedDelay
        delay.max = MAX_DELAY_MS / DELAY_STEP_MS
        delay.progress = savedDelay / DELAY_STEP_MS
        showDelay(savedDelay)
        delay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val ms = progress * DELAY_STEP_MS
                SyncService.delayMs = ms
                showDelay(ms)
                prefs.edit().putInt("delayMs", ms).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        toggle.setOnClickListener {
            message = null
            if (SyncService.isRunning) {
                startService(Intent(this, SyncService::class.java).setAction(SyncService.ACTION_STOP))
            } else {
                requestStart()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    private fun showDelay(ms: Int) {
        delayLabel.text = "Speaker delay: $ms ms"
    }

    private fun updateUi() {
        val running = SyncService.isRunning
        toggle.text = if (running) "Stop sync" else "Start sync"
        bulbIp.isEnabled = !running
        statusView.text = if (running) SyncService.status else message ?: SyncService.status
    }

    private fun requestStart() {
        if (bulbIp.text.toString().isBlank()) {
            message = "Enter the bulb's IP address first."
            return
        }
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            askForCapture()
        } else {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            askForCapture()
        } else {
            message = "Android lists audio capture under the microphone permission. " +
                "It is needed to read the music other apps play; the mic itself is never used."
        }
    }

    private fun askForCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Whole-screen capture: single-app sharing would only capture that one app's audio
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            message = "Capture permission was declined."
            return
        }
        val ip = bulbIp.text.toString().trim()
        prefs.edit().putString("bulbIp", ip).apply()
        startForegroundService(
            Intent(this, SyncService::class.java)
                .setAction(SyncService.ACTION_START)
                .putExtra(SyncService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(SyncService.EXTRA_RESULT_DATA, data)
                .putExtra(SyncService.EXTRA_BULB_IP, ip)
        )
    }
}
