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
    private var connectionState: String = "idle"
    private var animationAllowed: Boolean = true

    fun setConnectionState(state: String) {
        if (connectionState == state) return
        connectionState = state
        invalidate()
    }

    /** Stops frame scheduling in the background and while Android Battery Saver is active. */
    fun setAnimationAllowed(allowed: Boolean) {
        if (animationAllowed == allowed) return
        animationAllowed = allowed
        invalidate()
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val meteorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFE8C2.toInt()
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private var nightWasActive = false
    private var nightRevealStartedAt = 0L
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
        val phase = if (animationAllowed)
            (android.os.SystemClock.uptimeMillis() % 18_000L) / 18_000f else 0f
        val pulse = if (animationAllowed) .94f + .06f * sin(phase * Math.PI * 2.0).toFloat() else 1f
        glowPaint.shader = RadialGradient(cx, cy, radius * .72f * pulse,
            when (connectionState) {
                "connected" -> intArrayOf(0x5549D6A8, 0x1E36B88B, 0x00000000)
                "connecting", "disconnecting" -> intArrayOf(0x55FFC45C, 0x1EDB9A42, 0x00000000)
                "switching" -> intArrayOf(0x554D9BCB, 0x1E3D78A8, 0x00000000)
                else -> intArrayOf(0x35F6C85F, 0x12D8B65B, 0x00000000)
            },
            floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * .72f * pulse, glowPaint)
        glowPaint.shader = null
        floatArrayOf(.42f, .67f, .91f).forEachIndexed { index, factor ->
            ringPaint.color = when (connectionState) {
                "connected" -> if (index == 0) 0x6658D6A5 else 0x3475C7A7
                "connecting", "disconnecting" -> if (index == 0) 0x66FFC45C else 0x34D99C53
                "switching" -> if (index == 0) 0x665C9CCB else 0x347A9BC0
                else -> if (index == 0) 0x35E9C978 else 0x287D91B8
            }
            ringPaint.strokeWidth = dp(if (index == 0) 1.3f else 1f)
            canvas.drawCircle(cx, cy, radius * factor, ringPaint)
        }
        val calendar = java.util.Calendar.getInstance()
        val seasonal = SeasonalSkyPolicy.resolve(
            calendar.get(java.util.Calendar.MONTH) + 1,
            calendar.get(java.util.Calendar.DAY_OF_MONTH)
        )
        val seasonalActive = connectionState == "connected"
        if (!seasonalActive || !seasonal.newYearSnow) {
            points.forEachIndexed { index, point ->
                starPaint.alpha = if (index % 3 == 0) 220 else 145
                canvas.drawCircle(width * point.first, height * point.second,
                    dp(if (index % 4 == 0) 2.4f else 1.4f), starPaint)
            }
        }

        // At night, a successful VPN connection reveals a denser sky around the hero.
        // Coordinates are deterministic (no per-frame allocations or random number generation).
        val localHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val isNight = NightSkyAnimationPolicy.isNight(localHour)
        val nightActive = seasonalActive && isNight
        val now = android.os.SystemClock.uptimeMillis()
        if (nightActive && !nightWasActive) nightRevealStartedAt = now
        nightWasActive = nightActive
        val reveal = if (nightActive) NightSkyAnimationPolicy.revealProgress(now - nightRevealStartedAt) else 0f

        if (nightActive && reveal > 0f) {
            // A soft, gradually illuminated sky; the overlay remains translucent.
            glowPaint.shader = RadialGradient(cx, cy, radius * 1.42f,
                intArrayOf(0x001A2B55, android.graphics.Color.argb((reveal * 38).toInt(), 27, 48, 91),
                    0x00000000),
                floatArrayOf(0f, .62f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius * 1.42f, glowPaint)
            glowPaint.shader = null

            if (seasonal.newYearSnow) {
                // New Year snow replaces the additional star field.
                for (index in 0 until 34) {
                    val drift = if (animationAllowed) phase * (0.7f + (index % 5) * 0.13f) else 0f
                    val x = ((index * 29 + 13) % 101) / 100f * width +
                        sin(index * 1.7f + drift) * dp(5f)
                    val y = (((index * 43 + 7) % 101) / 101f + drift * 0.22f) % 1f * height
                    starPaint.color = 0xFFEAF4FF.toInt()
                    starPaint.alpha = ((90 + (index % 4) * 35) * reveal).toInt()
                    canvas.drawCircle(x, y, dp(if (index % 6 == 0) 2.2f else 1.2f), starPaint)
                }
            } else for (index in 0 until 38) {
                val x = ((index * 37 + 11) % 97) / 100f
                val y = ((index * 61 + 23) % 97) / 100f
                // Keep the brightest extra stars outside the central connect-button area.
                if (x in 0.37f..0.63f && y in 0.37f..0.63f) continue
                val twinkle = if (animationAllowed)
                    (sin(phase * Math.PI * 2.0 + index * 1.31) + 1.0) / 2.0 else 0.5
                starPaint.color = 0xFFFFE7AE.toInt()
                starPaint.alpha = ((75 + twinkle * 165) * reveal).toInt()
                val starRadius = dp(if (index % 7 == 0) 2.1f else 1.15f) *
                    (0.72f + twinkle.toFloat() * 0.45f)
                canvas.drawCircle(width * x, height * y, starRadius, starPaint)
                if (index % 7 == 0 && twinkle > 0.72) {
                    starPaint.alpha = (twinkle * 65 * reveal).toInt()
                    canvas.drawCircle(width * x, height * y, starRadius * 3.1f, starPaint)
                }
            }

            // Three deterministic meteor tracks use the existing frame loop and reusable Paint.
            if (animationAllowed && (seasonal.perseids || isNight)) {
                val elapsed = now - nightRevealStartedAt
                val meteorCount = if (seasonal.perseids) 5 else 3
                for (index in 0 until meteorCount) {
                    val progress = NightSkyAnimationPolicy.meteorProgress(elapsed, index * 2_700L)
                    if (progress <= 0f) continue
                    val startX = width * (0.16f + index * 0.29f)
                    val startY = height * (0.12f + (index % 2) * 0.13f)
                    val endX = startX + width * 0.25f * progress
                    val endY = startY + height * 0.28f * progress
                    meteorPaint.alpha = ((1f - progress * 0.35f) * reveal * 220f).toInt()
                    meteorPaint.strokeWidth = dp(1.35f)
                    canvas.drawLine(endX - width * 0.075f, endY - height * 0.075f, endX, endY, meteorPaint)
                    canvas.drawCircle(endX, endY, dp(1.7f), meteorPaint)
                }
                meteorPaint.alpha = 255
            }
        }
        if (seasonalActive && seasonal.eclipse) {
            val ex = width * 0.79f
            val ey = height * 0.22f
            glowPaint.shader = RadialGradient(ex, ey, dp(25f),
                intArrayOf(0x00FFF1B5, 0x66FFE6A1, 0x00FFE6A1),
                floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(ex, ey, dp(25f), glowPaint)
            glowPaint.shader = null
            starPaint.color = 0xFF101522.toInt()
            starPaint.alpha = 255
            canvas.drawCircle(ex, ey, dp(10f), starPaint)
            meteorPaint.color = 0xFFFFE7AE.toInt()
            meteorPaint.alpha = 180
            meteorPaint.strokeWidth = dp(1.5f)
            canvas.drawCircle(ex, ey, dp(11.5f), meteorPaint)
            meteorPaint.color = 0xFFFFE8C2.toInt()
        }
        starPaint.alpha = 255
        starPaint.color = 0xFFFFD477.toInt()

        // Slow counter-rotating satellites add depth without competing with the CTA.
        val orbitSpecs = listOf(.67f to 1f, .91f to -0.62f)
        orbitSpecs.forEachIndexed { index, spec ->
            val angle = ((if (animationAllowed) phase else 0f) * Math.PI * 2.0 * spec.second + index * 1.7).toFloat()
            val orbitRadius = radius * spec.first
            val x = cx + (cos(angle) * orbitRadius).toFloat()
            val y = cy + (sin(angle) * orbitRadius).toFloat()
            orbitDotPaint.alpha = if (index == 0) 235 else 155
            canvas.drawCircle(x, y, dp(if (index == 0) 3.2f else 2f), orbitDotPaint)
        }
        orbitDotPaint.alpha = 255
        canvas.drawCircle(cx, cy - radius * .67f, dp(4f), starPaint)
        if (animationAllowed && isShown) postInvalidateDelayed(50L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animationAllowed) postInvalidateOnAnimation()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}