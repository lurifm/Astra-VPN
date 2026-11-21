package com.astra.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.sin

/** Decorative orbit field; the accessible connect button is layered above it. */
internal class OrbitHeroView(context: Context) : View(context) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbitDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD477.toInt() }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD477.toInt() }
    private val points = listOf(
        .12f to .24f, .82f to .19f, .17f to .72f, .88f to .76f,
        .30f to .12f, .69f to .88f, .06f to .52f, .94f to .45f,
        .42f to .06f, .58f to .94f, .25f to .88f, .77f to .10f
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width * .43f, height * .43f)
        val phase = (android.os.SystemClock.uptimeMillis() % 18_000L) / 18_000f
        val pulse = .94f + .06f * sin(phase * Math.PI * 2.0).toFloat()
        glowPaint.shader = RadialGradient(cx, cy, radius * .72f * pulse,
            intArrayOf(0x45F6C85F, 0x16D8B65B, 0x00000000),
            floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * .72f * pulse, glowPaint)
        glowPaint.shader = null
        floatArrayOf(.42f, .67f, .91f).forEachIndexed { index, factor ->
            ringPaint.color = if (index == 0) 0x35E9C978 else 0x287D91B8
            ringPaint.strokeWidth = dp(if (index == 0) 1.3f else 1f)
            canvas.drawCircle(cx, cy, radius * factor, ringPaint)
        }
        points.forEachIndexed { index, point ->
            starPaint.alpha = if (index % 3 == 0) 220 else 145
            canvas.drawCircle(width * point.first, height * point.second,
                dp(if (index % 4 == 0) 2.4f else 1.4f), starPaint)
        }
        starPaint.alpha = 255

        // Slow counter-rotating satellites add depth without competing with the CTA.
        val orbitSpecs = listOf(.67f to 1f, .91f to -0.62f)
        orbitSpecs.forEachIndexed { index, spec ->
            val angle = (phase * Math.PI * 2.0 * spec.second + index * 1.7).toFloat()
            val orbitRadius = radius * spec.first
            val x = cx + (cos(angle) * orbitRadius).toFloat()
            val y = cy + (sin(angle) * orbitRadius).toFloat()
            orbitDotPaint.alpha = if (index == 0) 235 else 155
            canvas.drawCircle(x, y, dp(if (index == 0) 3.2f else 2f), orbitDotPaint)
        }
        orbitDotPaint.alpha = 255
        canvas.drawCircle(cx, cy - radius * .67f, dp(4f), starPaint)
        if (isShown) postInvalidateDelayed(50L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}