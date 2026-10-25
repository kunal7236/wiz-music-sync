package com.wizmusicsync

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "07:00" or "7:00 AM", following the device's 12/24-hour setting. */
fun formatTime(context: Context, time: LocalTime): String =
    time.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"))

fun formatTime(context: Context, millis: Long): String =
    formatTime(context, Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime())

/** Main line of a schedule row, e.g. "Off at 23:40". */
fun scheduleTitle(context: Context, s: Schedule): String {
    val action = if (s.turnOn) "On" else "Off"
    val at = s.atMillis?.let { formatTime(context, it) } ?: formatTime(context, LocalTime.of(s.hour, s.minute))
    return "$action at $at"
}

/** Second line of a schedule row, e.g. "Timer", "Weekdays" or "Once · done". */
fun scheduleDetail(s: Schedule): String = when {
    s.isTimer -> "Timer"
    s.isOneOff && !s.enabled -> "Once · done"
    !s.enabled -> "${repeatLabel(s.days)} · paused"
    else -> repeatLabel(s.days)
}

/** Dialog for a new schedule: on/off, then either a delay or a time of day with repeat days. */
fun Activity.showAddScheduleDialog(onSave: (Schedule) -> Unit) {
    val density = resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    fun radio(text: String) = RadioButton(this).apply { this.text = text; id = View.generateViewId() }
    fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, dp(16), 0, dp(4))
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
    }

    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), 0)
    }

    // ---- On / Off ----
    val turnOn = radio("Turn on")
    val turnOff = radio("Turn off")
    box.addView(label("Action"))
    box.addView(RadioGroup(this).apply {
        orientation = RadioGroup.HORIZONTAL
        addView(turnOn)
        addView(turnOff)
        check(turnOff.id)
    })

    // ---- When ----
    val afterDelay = radio("After a delay")
    val atTime = radio("At a time")
    val kind = RadioGroup(this).apply {
        orientation = RadioGroup.HORIZONTAL
        addView(afterDelay)
        addView(atTime)
    }
    box.addView(label("When"))
    box.addView(kind)

    // Delay panel
    val hours = NumberPicker(this).apply { minValue = 0; maxValue = 23; value = 0 }
    val minutes = NumberPicker(this).apply { minValue = 0; maxValue = 59; value = 30 }
    val delayPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    delayPanel.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(hours)
        addView(TextView(this@showAddScheduleDialog).apply { text = "h"; setPadding(dp(8), 0, dp(24), 0) })
        addView(minutes)
        addView(TextView(this@showAddScheduleDialog).apply { text = "min"; setPadding(dp(8), 0, 0, 0) })
    })
    delayPanel.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((text, total) in listOf("10 min" to 10, "30 min" to 30, "1 h" to 60, "2 h" to 120)) {
            addView(Button(this@showAddScheduleDialog).apply {
                this.text = text
                setOnClickListener {
                    hours.value = total / 60
                    minutes.value = total % 60
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    })
    box.addView(delayPanel)

    // Time panel: default to the next full hour
    var time = LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
    val timePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val timeButton = Button(this).apply { text = formatTime(context, time) }
    timeButton.setOnClickListener {
        TimePickerDialog(this, { _, h, m ->
            time = LocalTime.of(h, m)
            timeButton.text = formatTime(this, time)
        }, time.hour, time.minute, DateFormat.is24HourFormat(this)).show()
    }
    timePanel.addView(timeButton)

    val once = radio("Once")
    val daily = radio("Every day")
    val weekdays = radio("Weekdays")
    val custom = radio("Choose days")
    val repeat = RadioGroup(this).apply {
        orientation = RadioGroup.VERTICAL
        listOf(once, daily, weekdays, custom).forEach { addView(it) }
        check(once.id)
    }
    timePanel.addView(label("Repeat"))
    timePanel.addView(repeat)

    val dayBoxes = DayOfWeek.values().associateWith { day ->
        CheckBox(this).apply { text = day.name.take(1) }
    }
    val dayRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        dayBoxes.values.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        visibility = View.GONE
    }
    timePanel.addView(dayRow)
    repeat.setOnCheckedChangeListener { _, checked -> dayRow.visibility = if (checked == custom.id) View.VISIBLE else View.GONE }
    box.addView(timePanel)

    kind.setOnCheckedChangeListener { _, checked ->
        delayPanel.visibility = if (checked == afterDelay.id) View.VISIBLE else View.GONE
        timePanel.visibility = if (checked == atTime.id) View.VISIBLE else View.GONE
    }
    kind.check(afterDelay.id)

    val error = TextView(this).apply {
        setPadding(0, dp(8), 0, 0)
        setTextColor(0xFFD32F2F.toInt())
        visibility = View.GONE
    }
    box.addView(error)

    val dialog = AlertDialog.Builder(this)
        .setTitle("New schedule")
        .setView(android.widget.ScrollView(this).apply { addView(box) })
        .setPositiveButton("Save", null)
        .setNegativeButton("Cancel", null)
        .create()
    dialog.setOnShowListener {
        // Validate before closing
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val on = turnOn.isChecked
            val schedule = if (kind.checkedRadioButtonId == afterDelay.id) {
                val totalMinutes = hours.value * 60 + minutes.value
                if (totalMinutes == 0) {
                    error.text = "Choose a delay of at least 1 minute."
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                Schedule(id = 0, turnOn = on, atMillis = System.currentTimeMillis() + totalMinutes * 60_000L)
            } else {
                val days = when (repeat.checkedRadioButtonId) {
                    daily.id -> Schedule.EVERY_DAY
                    weekdays.id -> Schedule.WEEKDAYS
                    custom.id -> dayBoxes.entries.filter { it.value.isChecked }.sumOf { Schedule.dayBit(it.key) }
                    else -> 0
                }
                if (repeat.checkedRadioButtonId == custom.id && days == 0) {
                    error.text = "Pick at least one day."
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                Schedule(id = 0, turnOn = on, hour = time.hour, minute = time.minute, days = days)
            }
            onSave(schedule)
            dialog.dismiss()
        }
    }
    dialog.show()
}
