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
    private var cachedNightState = false
    private var nextNightCheckAt = 0L

    private val ringFactors = floatArrayOf(.42f, .67f, .91f)
    private val meteorStarts = floatArrayOf(.08f, .38f, .68f)
    private val satelliteRadii = floatArrayOf(.67f, .91f)
    private val satelliteDirections = floatArrayOf(1f, -0.62f)
    private val gradientStops = floatArrayOf(0f, .48f, 1f)
    private val nightGradientStops = floatArrayOf(0f, .62f, 1f)
    private val connectedGlow = intArrayOf(0x5549D6A8, 0x1E36B88B, 0x00000000)
    private val transitionGlow = intArrayOf(0x55FFC45C, 0x1EDB9A42, 0x00000000)
    private val switchingGlow = intArrayOf(0x554D9BCB, 0x1E3D78A8, 0x00000000)
    private val idleGlow = intArrayOf(0x35F6C85F, 0x12D8B65B, 0x00000000)
    private val nightGlow = intArrayOf(0x001A2B55, 0x002B305B, 0x00000000)

    fun setConnectionState(state: String) {
        if (connectionState == state) return
        connectionState = state
        nextNightCheckAt = 0L
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
    // Keep the hero sky deliberately sparse, matching the supplied Astra mockup.
    private val points = listOf(
        .12f to .24f, .84f to .20f, .16f to .76f,
        .88f to .73f, .31f to .11f, .70f to .89f
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width * .43f, height * .43f)
        val now = android.os.SystemClock.uptimeMillis()
        val phase = if (animationAllowed)
            (now % 18_000L) / 18_000f else 0f
        val pulse = if (animationAllowed) .94f + .06f * sin(phase * Math.PI * 2.0).toFloat() else 1f
        val glowColors = when (connectionState) {
            "connected" -> connectedGlow
            "connecting", "disconnecting" -> transitionGlow
            "switching" -> switchingGlow
            else -> idleGlow
        }
        glowPaint.shader = RadialGradient(cx, cy, radius * .72f * pulse,
            glowColors, gradientStops, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * .72f * pulse, glowPaint)
        glowPaint.shader = null
        for (index in ringFactors.indices) {
            val factor = ringFactors[index]
            ringPaint.color = when (connectionState) {
                "connected" -> if (index == 0) 0x6658D6A5 else 0x3475C7A7
                "connecting", "disconnecting" -> if (index == 0) 0x66FFC45C else 0x34D99C53
                "switching" -> if (index == 0) 0x665C9CCB else 0x347A9BC0
                else -> if (index == 0) 0x35E9C978 else 0x287D91B8
            }
            ringPaint.strokeWidth = dp(if (index == 0) 1.3f else 1f)
            canvas.drawCircle(cx, cy, radius * factor, ringPaint)
        }
        points.forEachIndexed { index, point ->
            starPaint.alpha = if (index % 3 == 0) 220 else 145
            canvas.drawCircle(width * point.first, height * point.second,
                dp(if (index % 4 == 0) 2.4f else 1.4f), starPaint)
        }

        // The ordinary night-sky overlay is time-gated and uses deterministic coordinates.
        if (now >= nextNightCheckAt) {
            val localHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            cachedNightState = NightSkyAnimationPolicy.isNight(localHour)
            nextNightCheckAt = now + 60_000L
        }
        val nightActive = connectionState == "connected" && cachedNightState
        if (nightActive && !nightWasActive) nightRevealStartedAt = now
        nightWasActive = nightActive
        val nightReveal = if (nightActive) NightSkyAnimationPolicy.revealProgress(now - nightRevealStartedAt) else 0f
        val reveal = nightReveal

        if (nightActive && nightReveal > 0f) {
            // A soft, gradually illuminated sky; the overlay remains translucent.
            nightGlow[1] = android.graphics.Color.argb((reveal * 38).toInt(), 27, 48, 91)
            glowPaint.shader = RadialGradient(cx, cy, radius * 1.42f,
                nightGlow, nightGradientStops, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius * 1.42f, glowPaint)
            glowPaint.shader = null

            for (index in 0 until 6) {
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

        }
        // A small, fixed number of ordinary night-sky meteor trails; no calendar-based events.
        if (nightActive && animationAllowed) {
            val elapsed = now - nightRevealStartedAt
            for (index in meteorStarts.indices) {
                val fraction = meteorStarts[index]
                val progress = NightSkyAnimationPolicy.meteorProgress(elapsed, index * 2_700L)
                if (progress <= 0f) continue
                val startX = width * fraction
                val startY = height * (0.12f + (index % 2) * 0.13f)
                val endX = startX + width * 0.25f * progress
                val endY = startY + height * 0.28f * progress
                meteorPaint.alpha = ((1f - progress * 0.35f) * nightReveal * 220f).toInt()
                meteorPaint.strokeWidth = dp(1.35f)
                canvas.drawLine(endX - width * 0.075f, endY - height * 0.075f, endX, endY, meteorPaint)
                canvas.drawCircle(endX, endY, dp(1.7f), meteorPaint)
            }
            meteorPaint.alpha = 255
        }
        starPaint.alpha = 255
        starPaint.color = 0xFFFFD477.toInt()

        // Slow counter-rotating satellites add depth without competing with the CTA.
        for (index in satelliteRadii.indices) {
            val angle = ((if (animationAllowed) phase else 0f) * Math.PI * 2.0 * satelliteDirections[index] + index * 1.7).toFloat()
            val orbitRadius = radius * satelliteRadii[index]
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