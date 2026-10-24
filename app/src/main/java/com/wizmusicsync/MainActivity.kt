package com.wizmusicsync

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.pow

class MainActivity : Activity() {

    companion object {
        private const val DEFAULT_BULB_IP = "192.168.0.106"
        private const val MAX_DELAY_MS = 500
        private const val DELAY_STEP_MS = 10
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_PROJECTION = 2

        /** Rows 0-3 are the theme's tiers, 4 is the bass-drop flash, 5 is the idle white. */
        private val ROW_NAMES = listOf(
            "Quiet / background",
            "Vocals / mids",
            "Synths / drive",
            "High intensity",
            "Sudden bass kick",
            "Paused or silent",
        )
        private const val DROP_ROW = 4
        private const val IDLE_ROW = 5
    }

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var bulbIp: EditText
    private lateinit var scan: Button
    private lateinit var delayLabel: TextView
    private lateinit var delay: SeekBar
    private lateinit var colorRows: LinearLayout
    private lateinit var toggle: Button
    private lateinit var statusView: TextView
    private var message: String? = null
    private var scanning = false

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
        scan = findViewById(R.id.scan)
        delayLabel = findViewById(R.id.delayLabel)
        delay = findViewById(R.id.delay)
        colorRows = findViewById(R.id.colorRows)
        toggle = findViewById(R.id.toggle)
        statusView = findViewById(R.id.status)

        ThemeStore.load(this)
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

        scan.setOnClickListener { scanForBulbs() }

        refreshColorRows()
        findViewById<Button>(R.id.resetColors).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Reset all light colours to the defaults?")
                .setPositiveButton("Reset") { _, _ ->
                    ThemeStore.reset(this)
                    refreshColorRows()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

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

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun showDelay(ms: Int) {
        delayLabel.text = "Speaker delay: $ms ms"
    }

    private fun updateUi() {
        val running = SyncService.isRunning
        toggle.text = if (running) "Stop sync" else "Start sync"
        bulbIp.isEnabled = !running
        scan.isEnabled = !running && !scanning
        statusView.text = if (running) SyncService.status else message ?: SyncService.status
    }

    private fun currentIp() = bulbIp.text.toString().trim()

    // ----------------- BULB SCAN -----------------
    private fun scanForBulbs() {
        scanning = true
        scan.text = "Scanning..."
        message = "Looking for WiZ bulbs on this Wi-Fi..."
        val appContext = applicationContext
        io.execute {
            val bulbs = WizBulb.discover(appContext)
            runOnUiThread {
                scanning = false
                scan.text = "Scan"
                if (isFinishing || isDestroyed) return@runOnUiThread
                message = null
                showScanResults(bulbs)
            }
        }
    }

    private fun showScanResults(bulbs: List<WizBulb.Found>) {
        if (bulbs.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No bulbs found")
                .setMessage("No WiZ bulb answered. Make sure this device is on the same Wi-Fi as the bulb and the bulb is switched on, then scan again.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = bulbs.map { bulb ->
            val current = if (bulb.ip == currentIp()) "  (current)" else ""
            "${bulb.ip}$current\n${bulb.mac}${bulb.model?.let { " · $it" }.orEmpty()}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (bulbs.size == 1) "Found 1 bulb" else "Found ${bulbs.size} bulbs")
            .setItems(labels) { _, which ->
                val ip = bulbs[which].ip
                bulbIp.setText(ip)
                prefs.edit().putString("bulbIp", ip).apply()
                message = "Bulb set to $ip"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ----------------- LIGHT COLOURS -----------------
    private fun refreshColorRows() {
        colorRows.removeAllViews()
        val lights = ThemeStore.current
        val rowBackground = TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId

        ROW_NAMES.forEachIndexed { index, name ->
            val (swatch, detail) = when (index) {
                IDLE_ROW -> kelvinToColor(lights.idle.temp) to "${lights.idle.temp} K white · ${lights.idle.dimming}%"
                DROP_ROW -> describe(lights.drop)
                else -> describe(lights.tiers[index])
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                setBackgroundResource(rowBackground)
                setOnClickListener { openEditor(index) }
            }
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(swatch)
                    setStroke(dp(1), Color.GRAY)
                }
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(16) })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = name
                    setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
                })
                addView(TextView(this@MainActivity).apply {
                    text = detail
                    setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
                })
            })
            colorRows.addView(row)
        }
    }

    private fun describe(c: LightColor) =
        Color.rgb(c.r, c.g, c.b) to "#%02X%02X%02X · %d%%".format(c.r, c.g, c.b, c.dimming)

    private fun openEditor(index: Int) {
        val theme = ThemeStore.current
        val isIdle = index == IDLE_ROW
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
        }
        val preview = View(this)
        val previewShape = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
        preview.background = previewShape
        box.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))

        // Editor state; returns the edited colour or white for "Save" and "Try on bulb"
        val build: () -> Any
        if (isIdle) {
            var temp = theme.idle.temp
            var dimming = theme.idle.dimming
            fun update() = previewShape.setColor(kelvinToColor(temp))
            addSlider(box, "Warmth", 22, 65, temp / 100, { "${it * 100} K" }) { temp = it * 100; update() }
            addSlider(box, "Brightness", 10, 100, dimming, { "$it%" }) { dimming = it }
            update()
            build = { WhiteLight(temp, dimming) }
        } else {
            val start = if (index == DROP_ROW) theme.drop else theme.tiers[index]
            var rgb = Color.rgb(start.r, start.g, start.b)
            var dimming = start.dimming
            val hsv = FloatArray(3).also { Color.colorToHSV(rgb, it) }
            fun update() = previewShape.setColor(rgb)
            // Only re-derive RGB once a colour slider is touched, so unedited colours stay exact
            fun fromHsv() {
                rgb = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))
                update()
            }
            addSlider(box, "Colour", 0, 359, hsv[0].toInt(), { "$it°" }) { hsv[0] = it.toFloat(); fromHsv() }
            addSlider(box, "Saturation", 0, 100, (hsv[1] * 100).toInt(), { "$it%" }) { hsv[1] = it / 100f; fromHsv() }
            addSlider(box, "Brightness", 10, 100, dimming, { "$it%" }) { dimming = it }
            update()
            build = { LightColor(Color.red(rgb), Color.green(rgb), Color.blue(rgb), dimming) }
        }

        var tried = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(ROW_NAMES[index])
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val edited = build()
                val updated = when {
                    edited is WhiteLight -> theme.copy(idle = edited)
                    index == DROP_ROW -> theme.copy(drop = edited as LightColor)
                    else -> theme.copy(tiers = theme.tiers.toMutableList().also { it[index] = edited as LightColor })
                }
                ThemeStore.save(this, updated)
                refreshColorRows()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Try on bulb", null)
            .create()
        dialog.setOnShowListener {
            // Keep the dialog open when trying a colour
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val ip = currentIp()
                if (ip.isEmpty()) return@setOnClickListener
                tried = true
                val payload = when (val edited = build()) {
                    is WhiteLight -> WizBulb.white(edited)
                    else -> WizBulb.pilot(edited as LightColor)
                }
                io.execute { WizBulb.sendOnce(ip, payload) }
            }
        }
        dialog.setOnDismissListener {
            // Put the bulb back to normal white after a try, unless the sync is driving it
            if (tried && !SyncService.isRunning) {
                val ip = currentIp()
                val payload = WizBulb.white(ThemeStore.current.idle)
                io.execute { WizBulb.sendOnce(ip, payload) }
            }
        }
        dialog.show()
    }

    private fun addSlider(
        parent: LinearLayout,
        label: String,
        min: Int,
        max: Int,
        value: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit,
    ) {
        val title = TextView(this).apply {
            text = "$label: ${format(value)}"
            setPadding(0, dp(16), 0, 0)
        }
        val slider = SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    title.text = "$label: ${format(progress)}"
                    if (fromUser) onChange(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        parent.addView(title)
        parent.addView(slider)
    }

    /** Approximate on-screen colour of white light at [kelvin] (Tanner Helland's fit). */
    private fun kelvinToColor(kelvin: Int): Int {
        val t = kelvin / 100.0
        val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val b = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> 138.5177312231 * ln(t - 10) - 305.0447927307
        }
        return Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ----------------- START / STOP -----------------
    private fun requestStart() {
        if (currentIp().isEmpty()) {
            message = "Enter the bulb's IP address first, or tap Scan."
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
        val ip = currentIp()
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
