package com.wizmusicsync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Hue/saturation colour wheel: hue goes round the circle, saturation runs from white at the
 * centre to the pure colour at the edge (exactly HSV with value = 1).
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called while the user drags, with hue in degrees (0-360) and saturation (0-1). */
    var onPick: ((hue: Float, saturation: Float) -> Unit)? = null

    private var hue = 0f
    private var saturation = 1f

    private var wheel: Bitmap? = null
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    private val density = resources.displayMetrics.density
    private val maxSize = (280 * density).toInt()
    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = Color.WHITE
    }
    private val ringInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.BLACK
    }

    init {
        contentDescription = "Colour wheel"
    }

    fun setHueSaturation(hue: Float, saturation: Float) {
        this.hue = hue
        this.saturation = saturation.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, min(width, maxSize))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - 12 * density
        if (radius <= 0) return

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hues = IntArray(13) { Color.HSVToColor(floatArrayOf((it * 30f) % 360f, 1f, 1f)) }
        wheelPaint.shader = SweepGradient(cx, cy, hues, null)
        canvas.drawCircle(cx, cy, radius, wheelPaint)
        wheelPaint.shader = RadialGradient(cx, cy, radius, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, wheelPaint)
        wheelPaint.shader = null
        wheel = bitmap
    }

    override fun onDraw(canvas: Canvas) {
        wheel?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val angle = Math.toRadians(hue.toDouble())
        val x = cx + (cos(angle) * saturation * radius).toFloat()
        val y = cy + (sin(angle) * saturation * radius).toFloat()
        canvas.drawCircle(x, y, 10 * density, ringOuter)
        canvas.drawCircle(x, y, 10 * density, ringInner)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // Don't let the surrounding ScrollView steal the drag
                parent?.requestDisallowInterceptTouchEvent(true)
                val dx = event.x - cx
                val dy = event.y - cy
                hue = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360) % 360).toFloat()
                saturation = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
                invalidate()
                onPick?.invoke(hue, saturation)
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
