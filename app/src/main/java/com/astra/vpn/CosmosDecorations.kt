package com.astra.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small animated star field used in the connection card.
 * It is deliberately drawn in Canvas: no bitmap assets or network calls are needed.
 */
internal class ConstellationView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val points = listOf(
        0.06f to 0.55f, 0.18f to 0.22f, 0.31f to 0.68f,
        0.43f to 0.35f, 0.57f to 0.58f, 0.69f to 0.20f,
        0.82f to 0.48f, 0.94f to 0.27f
    )
    private var phase = 0f
    private var active = false
    private var animator: ValueAnimator? = null

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
                    duration = 7200L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        phase = it.animatedValue as Float
                        invalidate()
                    }
                }
            }
            animator?.start()
        } else {
            animator?.cancel()
            phase = 0f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val coordinates = points.map { (x, y) -> x * w to y * h }
        val lineAlpha = if (active) 75 else 24
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        paint.color = Color.argb(lineAlpha, 126, 190, 255)
        for (i in 0 until coordinates.lastIndex) {
            val (x1, y1) = coordinates[i]
            val (x2, y2) = coordinates[i + 1]
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
        paint.style = Paint.Style.FILL
        coordinates.forEachIndexed { index, (x, y) ->
            val wave = (sin(phase + index * 0.83f) + 1f) / 2f
            val alpha = if (active) (100 + wave * 155).toInt() else 70
            paint.color = Color.argb(alpha, 174, 220, 255)
            val radius = resources.displayMetrics.density * (if (active) 1.5f + wave * 1.7f else 1.25f)
            canvas.drawCircle(x, y, radius, paint)
            if (active && wave > 0.78f) {
                paint.color = Color.argb((wave * 75).toInt(), 95, 190, 255)
                canvas.drawCircle(x, y, radius * 3.8f, paint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}

/** Compact four-point star marker for a server's orbit card. */
internal class OrbitMarkerView(context: Context) : View(context) {
    var quality: Int = 0
        set(value) { field = value.coerceIn(0, 3); invalidate() }
    var isChosen: Boolean = false
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val d = resources.displayMetrics.density
        val color = when (quality) {
            3 -> Color.rgb(85, 214, 154)
            2 -> Color.rgb(126, 190, 255)
            1 -> Color.rgb(255, 198, 109)
            else -> Color.rgb(123, 139, 165)
        }
        if (isChosen) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f * d
            paint.color = Color.argb(130, 126, 190, 255)
            canvas.drawCircle(cx, cy, 13f * d, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(55, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawCircle(cx, cy, 11f * d, paint)
        paint.color = color
        val path = Path().apply {
            moveTo(cx, cy - 10f * d)
            cubicTo(cx - 2.4f * d, cy - 2.4f * d, cx - 2.4f * d, cy - 2.4f * d, cx - 10f * d, cy)
            cubicTo(cx - 2.4f * d, cy + 2.4f * d, cx - 2.4f * d, cy + 2.4f * d, cx, cy + 10f * d)
            cubicTo(cx + 2.4f * d, cy + 2.4f * d, cx + 2.4f * d, cy + 2.4f * d, cx + 10f * d, cy)
            cubicTo(cx + 2.4f * d, cy - 2.4f * d, cx + 2.4f * d, cy - 2.4f * d, cx, cy - 10f * d)
            close()
        }
        canvas.drawPath(path, paint)
    }
}


/** One-shot expanding halo used when the main Astra star is tapped. */
internal class StarBurstView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 1f
    private var animator: ValueAnimator? = null

    fun play() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress >= 1f || width <= 0 || height <= 0) return
        val d = resources.displayMetrics.density
        val radius = (12f + progress * 39f) * d
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (2.6f - progress * 1.5f) * d
        paint.color = Color.argb(((1f - progress) * 190).toInt(), 169, 184, 255)
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(((1f - progress) * 34).toInt(), 169, 184, 255)
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
