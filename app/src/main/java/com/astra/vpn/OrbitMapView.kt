package com.astra.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Touch-selectable constellation of saved server profiles. */
internal class OrbitMapView(
    context: Context,
    private val nodes: List<Node>,
    private val onSelected: (Int) -> Unit
) : View(context) {
    data class Node(val label: String, val quality: Int, val selected: Boolean)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val positions = mutableListOf<Pair<Float, Float>>()
    private var selectedIndex = nodes.indexOfFirst { it.selected }.coerceAtLeast(0)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = nodes.size
        if (count == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width * 0.39f, height * 0.39f).coerceAtLeast(1f)
        positions.clear()
        for (i in 0 until count) {
            val angle = -Math.PI / 2.0 + 2.0 * Math.PI * i / count
            positions += (cx + cos(angle).toFloat() * radius) to (cy + sin(angle).toFloat() * radius)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        paint.color = Color.argb(70, 126, 190, 255)
        canvas.drawCircle(cx, cy, radius, paint)
        for (i in positions.indices) {
            val (x, y) = positions[i]
            val next = positions[(i + 1) % positions.size]
            canvas.drawLine(x, y, next.first, next.second, paint)
        }
        positions.forEachIndexed { index, (x, y) ->
            val color = when (nodes[index].quality) {
                3 -> Color.rgb(85, 214, 154)
                2 -> Color.rgb(126, 190, 255)
                1 -> Color.rgb(255, 198, 109)
                else -> Color.rgb(123, 139, 165)
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(55, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(x, y, if (index == selectedIndex) 24f else 18f, paint)
            paint.color = color
            canvas.drawCircle(x, y, if (index == selectedIndex) 8f else 6f, paint)
            if (index == selectedIndex) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * resources.displayMetrics.density
                paint.color = Color.WHITE
                canvas.drawCircle(x, y, 14f, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 12f * resources.displayMetrics.density
        paint.color = Color.WHITE
        canvas.drawText(nodes[selectedIndex.coerceIn(nodes.indices)].label.take(26), cx, cy + radius + 28f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP || positions.isEmpty()) return true
        val hitRadius = 32f * resources.displayMetrics.density
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
            onSelected(nearest)
        }
        return true
    }
}
