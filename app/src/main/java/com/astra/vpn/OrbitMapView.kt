package com.astra.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Touch-selectable server constellation styled for the Astra night-sky UI. */
internal class OrbitMapView(
    context: Context,
    private val nodes: List<Node>,
    private val onSelected: (Int) -> Unit
) : View(context) {
    data class Node(val label: String, val quality: Int, val selected: Boolean)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val positions = mutableListOf<Pair<Float, Float>>()
    private var selectedIndex = nodes.indexOfFirst { it.selected }.coerceAtLeast(0)
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    init {
        isClickable = true
        contentDescription = "Карта VPN-серверов. Нажмите на звезду, чтобы выбрать сервер."
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = nodes.size
        if (count == 0) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width * .39f, height * .37f).coerceAtLeast(dp(36f))
        positions.clear()

        // Quiet orbital rings instead of a polygon connecting every server.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = Color.argb(48, 218, 177, 91)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.color = Color.argb(28, 218, 177, 91)
        canvas.drawCircle(cx, cy, radius * .63f, paint)

        for (i in 0 until count) {
            val angle = -Math.PI / 2.0 + 2.0 * Math.PI * i / count
            val ring = if (i % 3 == 1) radius * .72f else radius
            positions += (cx + cos(angle).toFloat() * ring) to
                (cy + sin(angle).toFloat() * ring * .78f)
        }

        // Tiny central star anchors the map without competing with server nodes.
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(218, 177, 91)
        canvas.drawCircle(cx, cy, dp(2.5f), paint)
        paint.color = Color.argb(75, 218, 177, 91)
        canvas.drawCircle(cx, cy, dp(8f), paint)

        positions.forEachIndexed { index, (x, y) ->
            val isSelected = index == selectedIndex
            // Keep the map within Astra's gold visual language. Ping is encoded
            // by brightness and halo size rather than unrelated status colors.
            val quality = nodes[index].quality.coerceIn(0, 3)
            val core = when (quality) {
                3 -> Color.rgb(255, 224, 158)
                2 -> Color.rgb(225, 183, 105)
                1 -> Color.rgb(174, 137, 76)
                else -> Color.rgb(112, 94, 65)
            }
            val color = if (isSelected) Color.rgb(255, 235, 190) else core
            val haloAlpha = when (quality) {
                3 -> 72
                2 -> 54
                1 -> 34
                else -> 18
            } + if (isSelected) 38 else 0

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(haloAlpha.coerceAtMost(110),
                Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(x, y, dp(if (isSelected) 29f else 15f + quality * 2.5f), paint)
            paint.color = color
            canvas.drawCircle(x, y, dp(if (isSelected) 7.5f else 3.5f + quality * .7f), paint)

            if (isSelected) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.5f)
                paint.color = Color.rgb(255, 235, 190)
                canvas.drawCircle(x, y, dp(13f), paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(30, 255, 222, 158)
                canvas.drawCircle(x, y, dp(19f), paint)
            }
        }

        val label = nodes.getOrNull(selectedIndex)?.label.orEmpty()
        if (label.isNotBlank()) {
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(12f)
            paint.color = Color.rgb(235, 222, 194)
            canvas.drawText(label.take(30), cx, min(height - dp(8f), cy + radius + dp(30f)), paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (positions.isEmpty()) return true
                val hitRadius = dp(30f)
                val nearest = positions.indices.minByOrNull { index ->
                    val dx = positions[index].first - event.x
                    val dy = positions[index].second - event.y
                    dx * dx + dy * dy
                } ?: return true
                val dx = positions[nearest].first - event.x
                val dy = positions[nearest].second - event.y
                if (dx * dx + dy * dy <= hitRadius * hitRadius) {
                    selectedIndex = nearest
                    invalidate()
                    performClick()
                    onSelected(nearest)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
