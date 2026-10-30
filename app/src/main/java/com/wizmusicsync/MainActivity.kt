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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : Activity() {

    companion object {
        private const val MAX_DELAY_MS = 500
        private const val DELAY_STEP_MS = 10
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_PROJECTION = 2
        private const val REQUEST_NOTIFICATIONS = 3
        /** The schedule list is re-read every this many UI ticks (200 ms each) to show fired timers. */
        private const val SCHEDULE_REFRESH_TICKS = 25
        /** Minimum gap between bulb updates while dragging the fixed-colour picker. */
        private const val FIXED_PUSH_INTERVAL_MS = 100L

        /** Rows 0-3 are the theme's tiers, 4 is the bass-drop flash, 5 is the idle light. */
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

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var bulbIp: EditText
    private lateinit var scan: Button
    private lateinit var mode: RadioGroup
    private lateinit var fixedSection: View
    private lateinit var fixedPicker: LightPickerView
    private lateinit var syncSection: View
    private lateinit var delayLabel: TextView
    private lateinit var delay: SeekBar
    private lateinit var sensitivityLabel: TextView
    private lateinit var sensitivity: SeekBar
    private lateinit var keepColourOnPause: Switch
    private lateinit var colorRows: LinearLayout
    private lateinit var toggle: Button
    private lateinit var statusView: TextView
    private lateinit var powerState: TextView
    private lateinit var scheduleRows: LinearLayout
    private lateinit var scheduleWarning: View
    private lateinit var scheduleWarningText: TextView
    private lateinit var scheduleWarningFix: Button
    private var message: String? = null
    private var scanning = false
    /** True while views are being filled from [Settings], so their listeners don't write back. */
    private var binding = false
    private var shownSchedules: List<Schedule>? = null
    /** IP the app already auto-scanned for on opening, so a bulb that's off isn't searched for repeatedly. */
    private var autoScannedIp: String? = null
    private var ticks = 0

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateUi()
            // Timers fire in the background; pick up removed/finished ones while the screen is open
            if (++ticks % SCHEDULE_REFRESH_TICKS == 0) refreshScheduleRows()
            ui.postDelayed(this, 200)
        }
    }

    // Fixed colour: send the latest picked colour, at most every FIXED_PUSH_INTERVAL_MS
    private var pendingFixed: BulbLight? = null
    private var lastFixedPush = 0L
    private val pushFixed = object : Runnable {
        override fun run() {
            val light = pendingFixed ?: return
            pendingFixed = null
            lastFixedPush = SystemClock.uptimeMillis()
            sendToBulb(light)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bulbIp = findViewById(R.id.bulbIp)
        scan = findViewById(R.id.scan)
        mode = findViewById(R.id.mode)
        fixedSection = findViewById(R.id.fixedSection)
        fixedPicker = findViewById(R.id.fixedPicker)
        syncSection = findViewById(R.id.syncSection)
        delayLabel = findViewById(R.id.delayLabel)
        delay = findViewById(R.id.delay)
        sensitivityLabel = findViewById(R.id.sensitivityLabel)
        sensitivity = findViewById(R.id.sensitivity)
        keepColourOnPause = findViewById(R.id.keepColourOnPause)
        colorRows = findViewById(R.id.colorRows)
        toggle = findViewById(R.id.toggle)
        statusView = findViewById(R.id.status)
        powerState = findViewById(R.id.powerState)
        scheduleRows = findViewById(R.id.scheduleRows)
        scheduleWarning = findViewById(R.id.scheduleWarning)
        scheduleWarningText = findViewById(R.id.scheduleWarningText)
        scheduleWarningFix = findViewById(R.id.scheduleWarningFix)

        Settings.load(this)

        findViewById<Button>(R.id.powerOn).setOnClickListener { setPower(true) }
        findViewById<Button>(R.id.powerOff).setOnClickListener { setPower(false) }
        findViewById<Button>(R.id.addSchedule).setOnClickListener {
            showAddScheduleDialog { schedule ->
                val saved = Scheduler.add(this, schedule)
                val action = if (saved.turnOn) "on" else "off"
                message = saved.nextTrigger()?.let { "The bulb will turn $action at ${formatTime(this, it)}." }
                refreshScheduleRows()
                askForNotifications()
            }
        }

        bulbIp.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                if (!binding) Settings.setBulbIp(this@MainActivity, s.toString().trim())
            }

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
        })
        scan.setOnClickListener { scanForBulbs() }

        mode.setOnCheckedChangeListener { _, checkedId ->
            if (binding) return@setOnCheckedChangeListener
            switchMode(fixed = checkedId == R.id.modeFixed)
        }
        fixedPicker.onChange = { light ->
            Settings.setFixedLight(this, light)
            queueFixed(light)
        }

        delay.max = MAX_DELAY_MS / DELAY_STEP_MS
        delay.setOnSeekBarChangeListener(seekListener { progress ->
            val ms = progress * DELAY_STEP_MS
            showDelay(ms)
            if (!binding) Settings.setDelayMs(this, ms)
        })
        sensitivity.max = 100
        sensitivity.setOnSeekBarChangeListener(seekListener { value ->
            showSensitivity(value)
            if (!binding) Settings.setSensitivity(this, value)
        })
        keepColourOnPause.setOnCheckedChangeListener { _, checked ->
            if (!binding) Settings.setKeepColourOnPause(this, checked)
        }

        findViewById<Button>(R.id.resetColors).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Reset all light colours to the defaults?")
                .setPositiveButton("Reset") { _, _ ->
                    Settings.resetTheme(this)
                    refreshColorRows()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<Button>(R.id.resetAll).setOnClickListener { confirmResetAll() }

        toggle.setOnClickListener {
            message = null
            if (SyncService.isRunning) {
                startService(Intent(this, SyncService::class.java).setAction(SyncService.ACTION_STOP))
            } else {
                requestStart()
            }
        }

        bindFromSettings()
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
        refreshPowerState()
        refreshScheduleRows()
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(pushFixed)
        io.shutdown()
        super.onDestroy()
    }

    /** Fills every control from [Settings] (on start and after Reset all). */
    private fun bindFromSettings() {
        binding = true
        bulbIp.setText(Settings.bulbIp)
        mode.check(if (Settings.fixedMode) R.id.modeFixed else R.id.modeSync)
        fixedPicker.setLight(Settings.fixedLight)
        delay.progress = Settings.delayMs / DELAY_STEP_MS
        showDelay(Settings.delayMs)
        sensitivity.progress = Settings.sensitivity
        showSensitivity(Settings.sensitivity)
        keepColourOnPause.isChecked = Settings.keepColourOnPause
        binding = false
        showSections()
        refreshColorRows()
        refreshScheduleRows()
    }

    // ----------------- POWER -----------------
    /** @param retry false on the second attempt after an auto-scan, so a failure never scans again in a loop */
    private fun setPower(on: Boolean, retry: Boolean = true) {
        val ip = currentIp()
        if (ip.isEmpty()) {
            message = "Enter the bulb's IP address first, or tap Scan."
            return
        }
        // Off ends the light show too, so the next beat doesn't switch the bulb back on
        val stopSync = !on && SyncService.isRunning
        if (stopSync) SyncService.stopWithoutRestore()
        message = if (on) "Turning the bulb on..." else "Turning the bulb off..."
        io.execute {
            if (stopSync) Thread.sleep(600)
            val ok = WizBulb.setPower(ip, on, timeoutMs = 4000)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    ok -> {
                        message = null
                        showPowerState(on)
                    }
                    retry -> autoScan("The bulb at $ip didn't answer.") { setPower(on, retry = false) }
                    else -> message = "The bulb didn't answer. Check it's switched on and this device is on its Wi-Fi."
                }
            }
        }
    }

    /** @param allowAutoScan false after an auto-scan, so an unreachable bulb is only searched for once */
    private fun refreshPowerState(allowAutoScan: Boolean = true) {
        val ip = currentIp()
        if (ip.isEmpty()) return
        io.execute {
            val on = WizBulb.isOn(ip)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showPowerState(on)
                // Search once per IP per visit: the bulb may simply be switched off at the wall
                if (on == null && allowAutoScan && autoScannedIp != ip && !SyncService.isRunning) {
                    autoScannedIp = ip
                    autoScan("The bulb at $ip didn't answer.") { refreshPowerState(allowAutoScan = false) }
                }
            }
        }
    }

    /**
     * Called when the saved bulb doesn't answer: scans the Wi-Fi and, if exactly one bulb answers,
     * switches to it and runs [then] with its IP. Several bulbs show the pick list ([then] runs on a
     * pick); none keeps the old IP, which may be fine once the bulb is switched back on.
     */
    private fun autoScan(reason: String, then: (String) -> Unit) {
        if (scanning) return
        scanning = true
        scan.text = "Scanning..."
        message = "$reason Looking for it on this Wi-Fi..."
        val appContext = applicationContext
        io.execute {
            val bulbs = WizBulb.discover(appContext)
            runOnUiThread {
                scanning = false
                scan.text = "Scan"
                if (isFinishing || isDestroyed) return@runOnUiThread
                val old = currentIp()
                when {
                    bulbs.isEmpty() -> message = "$reason No WiZ bulb answered the scan either. " +
                        "Check it's switched on and this device is on its Wi-Fi; the IP was kept."
                    bulbs.size == 1 -> {
                        val ip = bulbs[0].ip
                        if (ip != old) {
                            autoScannedIp = ip
                            bulbIp.setText(ip)
                        }
                        message = if (ip != old) "Bulb found at a new address: now using $ip (was $old)." else null
                        then(ip)
                    }
                    else -> {
                        message = "$reason Found ${bulbs.size} bulbs; pick yours."
                        showScanResults(bulbs, then)
                    }
                }
            }
        }
    }

    private fun showPowerState(on: Boolean?) {
        powerState.text = when (on) {
            true -> "Bulb is on"
            false -> "Bulb is off"
            null -> "Bulb not reachable"
        }
    }

    // ----------------- SCHEDULES -----------------
    private fun refreshScheduleRows() {
        val schedules = Scheduler.all(this)
            .sortedWith(compareBy<Schedule> { !it.enabled }.thenBy { it.nextTrigger() ?: Long.MAX_VALUE })
        updateScheduleWarning(schedules.any { it.enabled })
        if (schedules == shownSchedules) return
        shownSchedules = schedules
        scheduleRows.removeAllViews()

        for (schedule in schedules) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                alpha = if (schedule.enabled) 1f else 0.5f
            }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = scheduleTitle(this@MainActivity, schedule)
                    setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
                })
                addView(TextView(this@MainActivity).apply {
                    text = scheduleDetail(schedule)
                    setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // Timers run once and are simply deleted; time-of-day schedules can be paused
            if (!schedule.isTimer) {
                row.addView(Switch(this).apply {
                    isChecked = schedule.enabled
                    contentDescription = "Enable schedule"
                    setOnCheckedChangeListener { _, checked ->
                        Scheduler.update(this@MainActivity, schedule.copy(enabled = checked))
                        refreshScheduleRows()
                    }
                })
            }
            row.addView(ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                background = null
                contentDescription = "Delete schedule"
                setOnClickListener {
                    Scheduler.delete(this@MainActivity, schedule.id)
                    refreshScheduleRows()
                }
            })
            scheduleRows.addView(row)
        }
    }

    /** Explains (with a fix button) anything that could make schedules fire late or not at all. */
    private fun updateScheduleWarning(hasActive: Boolean) {
        val power = getSystemService(PowerManager::class.java)
        when {
            !hasActive -> scheduleWarning.visibility = View.GONE
            !Scheduler.canFireOnTime(this) && Build.VERSION.SDK_INT >= 31 -> {
                scheduleWarningText.text = "Allow \"Alarms & reminders\" so schedules run on the minute."
                scheduleWarningFix.text = "Allow"
                scheduleWarningFix.setOnClickListener {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                    )
                }
                scheduleWarning.visibility = View.VISIBLE
            }
            !power.isIgnoringBatteryOptimizations(packageName) -> {
                scheduleWarningText.text = "Battery saving can delay or skip schedules. " +
                    "Set this app to \"Unrestricted\" / \"Don't optimise\" in battery settings."
                scheduleWarningFix.text = "Battery settings"
                scheduleWarningFix.setOnClickListener {
                    startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
                scheduleWarning.visibility = View.VISIBLE
            }
            else -> scheduleWarning.visibility = View.GONE
        }
    }

    /** Needed so a schedule that can't reach the bulb can say so. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun showSections() {
        fixedSection.visibility = if (Settings.fixedMode) View.VISIBLE else View.GONE
        syncSection.visibility = if (Settings.fixedMode) View.GONE else View.VISIBLE
    }

    private fun switchMode(fixed: Boolean) {
        message = null
        Settings.setFixedMode(this, fixed)
        showSections()
        if (fixed) {
            if (SyncService.isRunning) {
                // Stop capturing without the usual "back to white" so the fixed colour takes over cleanly
                startService(
                    Intent(this, SyncService::class.java)
                        .setAction(SyncService.ACTION_STOP)
                        .putExtra(SyncService.EXTRA_SKIP_RESTORE, true)
                )
                // Give the service a moment to drop its queued colours before we set ours
                queueFixed(Settings.fixedLight, delayMs = 400)
            } else {
                queueFixed(Settings.fixedLight, delayMs = 0)
            }
        }
    }

    private fun queueFixed(light: BulbLight, delayMs: Long? = null) {
        pendingFixed = light
        if (ui.hasCallbacks(pushFixed)) return
        val wait = delayMs ?: max(0L, FIXED_PUSH_INTERVAL_MS - (SystemClock.uptimeMillis() - lastFixedPush))
        ui.postDelayed(pushFixed, wait)
    }

    private fun sendToBulb(light: BulbLight) {
        val ip = currentIp()
        if (ip.isEmpty()) return
        val payload = WizBulb.payload(light)
        io.execute { WizBulb.sendOnce(ip, payload) }
    }

    private fun confirmResetAll() {
        AlertDialog.Builder(this)
            .setTitle("Reset all settings?")
            .setMessage(
                "Bulb IP, speaker delay, sensitivity, mode, fixed colour and all light colours " +
                    "go back to how they were when the app was installed."
            )
            .setPositiveButton("Reset all") { _, _ ->
                Settings.resetAll(this)
                bindFromSettings()
                message = "All settings are back to their defaults."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDelay(ms: Int) {
        delayLabel.text = "Speaker delay: $ms ms"
    }

    private fun showSensitivity(value: Int) {
        val word = when {
            value < 20 -> "Very low"
            value < 45 -> "Low"
            value <= 55 -> "Normal"
            value <= 80 -> "High"
            else -> "Very high"
        }
        sensitivityLabel.text = "Sensitivity: $word"
    }

    private fun updateUi() {
        val running = SyncService.isRunning
        toggle.text = if (running) "Stop sync" else "Start sync"
        bulbIp.isEnabled = !running
        scan.isEnabled = !running && !scanning
        statusView.text = when {
            running -> SyncService.status
            message != null -> message
            Settings.fixedMode -> "Fixed colour: ${describe(Settings.fixedLight)}"
            else -> SyncService.status
        }
    }

    private fun currentIp() = bulbIp.text.toString().trim()

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = onChange(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    }

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

    /** @param onPicked runs with the chosen IP after the user picks a bulb */
    private fun showScanResults(bulbs: List<WizBulb.Found>, onPicked: ((String) -> Unit)? = null) {
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
                autoScannedIp = ip
                bulbIp.setText(ip)
                message = "Bulb set to $ip"
                onPicked?.invoke(ip)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ----------------- LIGHT COLOURS -----------------
    private fun refreshColorRows() {
        colorRows.removeAllViews()
        val lights = Settings.theme
        val rowBackground = TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId

        ROW_NAMES.forEachIndexed { index, name ->
            val light = lightFor(lights, index)
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
                    setColor(swatchColor(light))
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
                    text = describe(light)
                    setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
                })
            })
            colorRows.addView(row)
        }
    }

    private fun lightFor(theme: LightTheme, index: Int): BulbLight = when (index) {
        IDLE_ROW -> theme.idle
        DROP_ROW -> theme.drop
        else -> theme.tiers[index]
    }

    private fun openEditor(index: Int) {
        val picker = LightPickerView(this).apply {
            setPadding(dp(24), dp(16), dp(24), 0)
            setLight(lightFor(Settings.theme, index))
        }
        var tried = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(ROW_NAMES[index])
            .setView(android.widget.ScrollView(this).apply { addView(picker) })
            .setPositiveButton("Save") { _, _ ->
                val edited = picker.light
                val theme = Settings.theme
                Settings.setTheme(
                    this, when (index) {
                        IDLE_ROW -> theme.copy(idle = edited)
                        DROP_ROW -> theme.copy(drop = edited)
                        else -> theme.copy(tiers = theme.tiers.toMutableList().also { it[index] = edited })
                    }
                )
                refreshColorRows()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Try on bulb", null)
            .create()
        dialog.setOnShowListener {
            // Keep the dialog open when trying a colour
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                tried = true
                sendToBulb(picker.light)
            }
        }
        dialog.setOnDismissListener {
            // Put the bulb back after a try, unless the sync is driving it
            if (tried && !SyncService.isRunning) sendToBulb(Settings.theme.idle)
        }
        dialog.show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ----------------- START / STOP -----------------
    /** @param bulbChecked true once the bulb answered (or was just found by an auto-scan) */
    private fun requestStart(bulbChecked: Boolean = false) {
        val ip = currentIp()
        if (ip.isEmpty()) {
            message = "Enter the bulb's IP address first, or tap Scan."
            return
        }
        if (!bulbChecked) {
            // Make sure the light show has a bulb to talk to before asking for capture permission
            message = "Checking the bulb..."
            io.execute {
                val reachable = WizBulb.isOn(ip, timeoutMs = 1000) != null
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (reachable) {
                        message = null
                        requestStart(bulbChecked = true)
                    } else {
                        autoScan("The bulb at $ip didn't answer.") { requestStart(bulbChecked = true) }
                    }
                }
            }
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
        startForegroundService(
            Intent(this, SyncService::class.java)
                .setAction(SyncService.ACTION_START)
                .putExtra(SyncService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(SyncService.EXTRA_RESULT_DATA, data)
                .putExtra(SyncService.EXTRA_BULB_IP, currentIp())
        )
    }
}
