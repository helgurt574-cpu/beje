package com.example.offlinedikte

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.min
import kotlin.math.sin

/**
 * Mikrofon düğmesinin arkasında, ses seviyesine göre büyüyen ve nabız gibi atan halkalar çizer.
 * Simgedeki güneş ışınlarını andıran kısa çizgiler de ses seviyesine göre uzar.
 */
class MicPulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val activeColor = ContextCompat.getColor(context, R.color.brand_red)
    private val rayColor = ContextCompat.getColor(context, R.color.brand_sun)

    private var targetLevel = 0f
    private var displayLevel = 0f
    private var startTime = SystemClock.uptimeMillis()

    /** Düğmenin yarıçapı (px); halkalar bunun dışında çizilir. */
    var innerRadius = 0f
        set(value) {
            field = value
            invalidate()
        }

    var isActive = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startTime = SystemClock.uptimeMillis() else targetLevel = 0f
            invalidate()
        }

    /** 0..1 arası ses seviyesi. */
    fun setLevel(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
        if (isActive) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = min(width, height) / 2f
        val base = if (innerRadius > 0f) innerRadius else maxRadius * 0.55f
        val room = (maxRadius - base).coerceAtLeast(1f)

        displayLevel += (targetLevel - displayLevel) * 0.25f
        if (!isActive && displayLevel < 0.01f) {
            displayLevel = 0f
            return
        }

        val time = (SystemClock.uptimeMillis() - startTime) / 1000f

        // Nabız halkaları
        for (index in 0 until 3) {
            val phase = ((time * 0.6f) + index / 3f) % 1f
            val radius = base + room * phase * (0.45f + 0.55f * displayLevel)
            val alpha = ((1f - phase) * (0.10f + 0.30f * displayLevel) * 255).toInt().coerceIn(0, 255)
            ringPaint.color = ColorUtils.setAlphaComponent(activeColor, alpha)
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }

        // Güneş ışınları: ses seviyesine göre uzayan kısa çizgiler
        val rays = 21
        rayPaint.strokeWidth = base * 0.07f
        rayPaint.color = ColorUtils.setAlphaComponent(rayColor, (90 + 165 * displayLevel).toInt().coerceIn(0, 255))
        val rayStart = base + room * 0.12f
        for (index in 0 until rays) {
            val angle = (index * 2 * Math.PI / rays) - Math.PI / 2
            val wobble = 0.5f + 0.5f * sin(time * 7f + index * 1.7f)
            val length = room * (0.06f + 0.42f * displayLevel * (0.55f + 0.45f * wobble))
            val cos = kotlin.math.cos(angle).toFloat()
            val sinA = kotlin.math.sin(angle).toFloat()
            canvas.drawLine(
                cx + cos * rayStart, cy + sinA * rayStart,
                cx + cos * (rayStart + length), cy + sinA * (rayStart + length),
                rayPaint
            )
        }

        if (isActive || displayLevel > 0f) postInvalidateOnAnimation()
    }
}
