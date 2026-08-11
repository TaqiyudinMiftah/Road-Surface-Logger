package com.example.roadsurfacelogger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class Orientation3DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Vec3(val x: Float, val y: Float, val z: Float)
    private data class Face(val indices: IntArray, val depth: Float)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        color = Color.rgb(55, 65, 81)
    }

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(55, 33, 150, 243)
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = resources.displayMetrics.scaledDensity * 12f
        color = Color.rgb(55, 65, 81)
    }

    private var azimuthDeg = 0f
    private var pitchDeg = 0f
    private var rollDeg = 0f

    fun setOrientationDegrees(azimuth: Float, pitch: Float, roll: Float) {
        if (azimuth.isNaN() || pitch.isNaN() || roll.isNaN()) return
        azimuthDeg = azimuth
        pitchDeg = pitch
        rollDeg = roll
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val scale = minOf(width, height) * 0.22f
        if (scale <= 0f) return

        val base = arrayOf(
            Vec3(-0.62f, -1.0f, -0.12f),
            Vec3(0.62f, -1.0f, -0.12f),
            Vec3(0.62f, 1.0f, -0.12f),
            Vec3(-0.62f, 1.0f, -0.12f),
            Vec3(-0.62f, -1.0f, 0.12f),
            Vec3(0.62f, -1.0f, 0.12f),
            Vec3(0.62f, 1.0f, 0.12f),
            Vec3(-0.62f, 1.0f, 0.12f)
        )

        val rotated = base.map { rotate(it) }
        val projected = rotated.map { project(it, cx, cy, scale) }
        val faceIndices = arrayOf(
            intArrayOf(0, 1, 2, 3),
            intArrayOf(4, 5, 6, 7),
            intArrayOf(0, 1, 5, 4),
            intArrayOf(1, 2, 6, 5),
            intArrayOf(2, 3, 7, 6),
            intArrayOf(3, 0, 4, 7)
        )

        faceIndices
            .map { ids -> Face(ids, ids.map { rotated[it].z }.average().toFloat()) }
            .sortedBy { it.depth }
            .forEach { face ->
                val p = Path()
                val first = projected[face.indices[0]]
                p.moveTo(first.first, first.second)
                for (i in 1 until face.indices.size) {
                    val point = projected[face.indices[i]]
                    p.lineTo(point.first, point.second)
                }
                p.close()
                canvas.drawPath(p, facePaint)
                canvas.drawPath(p, strokePaint)
            }

        drawAxis(canvas, Vec3(1.35f, 0f, 0f), "X", Color.rgb(211, 47, 47), cx, cy, scale)
        drawAxis(canvas, Vec3(0f, 1.35f, 0f), "Y", Color.rgb(46, 125, 50), cx, cy, scale)
        drawAxis(canvas, Vec3(0f, 0f, 1.35f), "Z", Color.rgb(25, 118, 210), cx, cy, scale)
    }

    private fun drawAxis(
        canvas: Canvas,
        axis: Vec3,
        label: String,
        color: Int,
        cx: Float,
        cy: Float,
        scale: Float
    ) {
        axisPaint.color = color
        labelPaint.color = color
        val origin = project(Vec3(0f, 0f, 0f), cx, cy, scale)
        val end = project(rotate(axis), cx, cy, scale)
        canvas.drawLine(origin.first, origin.second, end.first, end.second, axisPaint)
        canvas.drawText(label, end.first + 6f, end.second - 6f, labelPaint)
    }

    private fun rotate(v: Vec3): Vec3 {
        val ax = Math.toRadians(pitchDeg.toDouble())
        val ay = Math.toRadians(rollDeg.toDouble())
        val az = Math.toRadians(azimuthDeg.toDouble())

        var x = v.x.toDouble()
        var y = v.y.toDouble()
        var z = v.z.toDouble()

        val y1 = y * cos(ax) - z * sin(ax)
        val z1 = y * sin(ax) + z * cos(ax)
        y = y1
        z = z1

        val x2 = x * cos(ay) + z * sin(ay)
        val z2 = -x * sin(ay) + z * cos(ay)
        x = x2
        z = z2

        val x3 = x * cos(az) - y * sin(az)
        val y3 = x * sin(az) + y * cos(az)

        return Vec3(x3.toFloat(), y3.toFloat(), z.toFloat())
    }

    private fun project(v: Vec3, cx: Float, cy: Float, scale: Float): Pair<Float, Float> {
        val cameraDistance = 4.0f
        val perspective = cameraDistance / (cameraDistance - v.z.coerceIn(-2.5f, 2.5f))
        val sx = cx + v.x * scale * perspective
        val sy = cy - v.y * scale * perspective
        return sx to sy
    }
}
