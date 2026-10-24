package com.wizmusicsync

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.ln
import kotlin.math.pow

/**
 * Picks a [BulbLight]: a Colour tab with a hue/saturation wheel, a White tab with warmth presets
 * and a slider, and a shared brightness slider. Used for the fixed colour and every sync state.
 */
class LightPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    companion object {
        private const val MIN_TEMP = 2200
        private const val MAX_TEMP = 6500
        private val WHITE_PRESETS = listOf("Warm" to 2700, "Neutral" to 4200, "Daylight" to 6500)
    }

    /** Called on every user change. */
    var onChange: ((BulbLight) -> Unit)? = null

    private var rgb = Color.BLUE
    private var temp = 4200
    private var dimming = 100
    private var isWhite = false

    private val density = resources.displayMetrics.density
    private val previewShape = GradientDrawable().apply { cornerRadius = 8 * density }
    private val previewLabel = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
    }
    private val tabs = RadioGroup(context).apply { orientation = HORIZONTAL }
    private val colourTab = RadioButton(context).apply { text = "Colour"; id = View.generateViewId() }
    private val whiteTab = RadioButton(context).apply { text = "White"; id = View.generateViewId() }
    private val wheel = ColorWheelView(context)
    private val whitePanel = LinearLayout(context).apply { orientation = VERTICAL }
    private val warmthLabel = TextView(context)
    private val warmth = SeekBar(context)
    private val brightnessLabel = TextView(context)
    private val brightness = SeekBar(context)

    init {
        orientation = VERTICAL

        addView(previewLabel.apply { background = previewShape }, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))

        tabs.addView(colourTab)
        tabs.addView(whiteTab)
        addView(tabs, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        tabs.setOnCheckedChangeListener { _, checkedId ->
            val white = checkedId == whiteTab.id
            if (white == isWhite) return@setOnCheckedChangeListener
            isWhite = white
            showPanel()
            changed()
        }

        addView(wheel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        wheel.onPick = { h, s ->
            rgb = Color.HSVToColor(floatArrayOf(h, s, 1f))
            changed()
        }

        val presets = LinearLayout(context).apply { orientation = HORIZONTAL }
        for ((name, kelvin) in WHITE_PRESETS) {
            presets.addView(Button(context).apply {
                text = name
                setOnClickListener {
                    temp = kelvin
                    warmth.progress = kelvin / 100
                    changed()
                }
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        whitePanel.addView(presets)
        whitePanel.addView(warmthLabel.apply { setPadding(0, dp(12), 0, 0) })
        whitePanel.addView(warmth.apply {
            min = MIN_TEMP / 100
            max = MAX_TEMP / 100
            setOnSeekBarChangeListener(listener { value ->
                temp = value * 100
                changed()
            })
        })
        addView(whitePanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        addView(brightnessLabel.apply { setPadding(0, dp(16), 0, 0) })
        addView(brightness.apply {
            min = 10
            max = 100
            setOnSeekBarChangeListener(listener { value ->
                dimming = value
                changed()
            })
        })

        setLight(BulbLight.Rgb(0, 0, 255, 100))
    }

    /** What the picker currently shows. */
    val light: BulbLight
        get() = if (isWhite) BulbLight.White(temp, dimming)
        else BulbLight.Rgb(Color.red(rgb), Color.green(rgb), Color.blue(rgb), dimming)

    /** Shows [light] without firing [onChange]. An untouched colour is returned exactly as given. */
    fun setLight(light: BulbLight) {
        dimming = light.dimming
        when (light) {
            is BulbLight.Rgb -> {
                isWhite = false
                rgb = Color.rgb(light.r, light.g, light.b)
            }
            is BulbLight.White -> {
                isWhite = true
                temp = light.temp
            }
        }
        val hsv = FloatArray(3).also { Color.colorToHSV(rgb, it) }
        wheel.setHueSaturation(hsv[0], hsv[1])
        warmth.progress = temp / 100
        brightness.progress = dimming
        (if (isWhite) whiteTab else colourTab).isChecked = true
        showPanel()
        updatePreview()
    }

    private fun showPanel() {
        wheel.visibility = if (isWhite) GONE else VISIBLE
        whitePanel.visibility = if (isWhite) VISIBLE else GONE
    }

    private fun changed() {
        updatePreview()
        onChange?.invoke(light)
    }

    private fun updatePreview() {
        val color = if (isWhite) kelvinToColor(temp) else rgb
        previewShape.setColor(color)
        previewLabel.text = describe(light)
        // Keep the label readable on light and dark swatches
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        previewLabel.setTextColor(if (luminance > 0.6) Color.BLACK else Color.WHITE)
        warmthLabel.text = "Warmth: $temp K"
        brightnessLabel.text = "Brightness: $dimming%"
    }

    private fun listener(onUserChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onUserChange(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    }

    private fun dp(value: Int) = (value * density).toInt()
}

/** Short label such as "#0000FF · 60%" or "4200 K white · 100%". */
fun describe(light: BulbLight): String = when (light) {
    is BulbLight.Rgb -> "#%02X%02X%02X · %d%%".format(light.r, light.g, light.b, light.dimming)
    is BulbLight.White -> "${light.temp} K white · ${light.dimming}%"
}

/** On-screen colour for a swatch of [light]. */
fun swatchColor(light: BulbLight): Int = when (light) {
    is BulbLight.Rgb -> Color.rgb(light.r, light.g, light.b)
    is BulbLight.White -> kelvinToColor(light.temp)
}

/** Approximate on-screen colour of white light at [kelvin] (Tanner Helland's fit). */
fun kelvinToColor(kelvin: Int): Int {
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
