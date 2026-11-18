package com.astra.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.ArrayDeque

/** Compact live chart for device-wide receive/transmit throughput. */
class SpeedGraphView(context: Context) : View(context) {
    private val download = ArrayDeque<Float>()
    private val upload = ArrayDeque<Float>()
    private val statistics = SpeedSampleWindow(60)
    private val downloadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF55D69A.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val uploadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9EAEFF.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        strokeWidth = 1f
    }

    fun addSample(downloadMbps: Float, uploadMbps: Float) {
        download.addLast(downloadMbps.coerceAtLeast(0f))
        upload.addLast(uploadMbps.coerceAtLeast(0f))
        statistics.add(downloadMbps, uploadMbps)
        while (download.size > 60) download.removeFirst()
        while (upload.size > 60) upload.removeFirst()
        invalidate()
    }

    fun currentStats(): SpeedSampleWindow.Stats = statistics.stats()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 3f
        val right = width - 3f
        val top = 8f
        val bottom = height - 8f
        for (line in 0..2) {
            val y = top + (bottom - top) * line / 2f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        val maxSpeed = maxOf(download.maxOrNull() ?: 0f, upload.maxOrNull() ?: 0f, 1f)
        drawSeries(canvas, download, downloadPaint, left, right, top, bottom, maxSpeed)
        drawSeries(canvas, upload, uploadPaint, left, right, top, bottom, maxSpeed)
    }

    private fun drawSeries(
        canvas: Canvas,
        values: ArrayDeque<Float>,
        paint: Paint,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        maxSpeed: Float
    ) {
        if (values.size < 2) return
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = left + (right - left) * index / (values.size - 1).toFloat()
            val y = bottom - (value / maxSpeed) * (bottom - top)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
