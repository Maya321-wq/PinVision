package com.bowling.bowlingapp_final

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import org.opencv.core.Point
import org.opencv.core.Rect

// ✅ DATA MODELS
data class PinState(val id: Int, var centroid: Point, var isStanding: Boolean, var fallOrder: Int, var fallTimeSec: Double, var bbox: Rect? = null)
data class AnalysisState(
    val phase: String, val pins: List<PinState>, val carPath: List<Point>,
    val fallenCount: Int, val standingCount: Int, val elapsedSec: Int,
    val previewW: Int, val previewH: Int
)

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val standingPaint = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 8f }
    private val fallenPaint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 8f }
    private val labelPaint = Paint().apply { color = Color.WHITE; textSize = 48f; isFakeBoldText = true; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val hudPaint = Paint().apply { color = Color.WHITE; textSize = 40f; isFakeBoldText = true }
    private val bgPaint = Paint().apply { color = Color.argb(220, 0, 0, 0) }
    private var state: AnalysisState? = null
    private var layoutReady = false

    fun update(s: AnalysisState) {
        state = s
        postInvalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutReady = w > 0 && h > 0
    }

    override fun onDraw(canvas: Canvas) {
        val s = state ?: return
        if (!layoutReady || width == 0 || height == 0) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val srcW = s.previewW.toFloat()
        val srcH = s.previewH.toFloat()

        // ✅ FIT_CENTER MATH
        val srcRatio = srcW / srcH
        val viewRatio = viewW / viewH
        val scale = if (srcRatio > viewRatio) viewW / srcW else viewH / srcH
        val offsetX = (viewW - srcW * scale) / 2f
        val offsetY = (viewH - srcH * scale) / 2f

        // HUD
        canvas.drawRect(0f, 0f, viewW, 100f, bgPaint)
        canvas.drawText("Score: ${s.fallenCount} fallen / ${s.standingCount} standing", 20f, 40f, hudPaint)
        canvas.drawText("Time: ${String.format("%02d:%02d", s.elapsedSec / 60, s.elapsedSec % 60)}", 20f, 80f, hudPaint)

        // Car Path
        if (s.carPath.size > 1) {
            // ✅ DEBUG LOG: Tracking car path population
            Log.d("BowlingCV", "🚗 Car Path Size: ${s.carPath.size}, Last Point: ${s.carPath.last()}")

            for (i in 1 until s.carPath.size) {
                val alpha = i.toFloat() / s.carPath.size
                val p = Paint().apply {
                    color = Color.argb((255 * alpha).toInt(), 0, 255, 255)
                    style = Paint.Style.STROKE
                    strokeWidth = (8 * alpha).coerceAtLeast(3f)
                }
                val p1 = s.carPath[i - 1]
                val p2 = s.carPath[i]
                canvas.drawLine(
                    p1.x.toFloat() * scale + offsetX, p1.y.toFloat() * scale + offsetY,
                    p2.x.toFloat() * scale + offsetX, p2.y.toFloat() * scale + offsetY, p
                )
            }
            val last = s.carPath.last()
            canvas.drawCircle(last.x.toFloat() * scale + offsetX, last.y.toFloat() * scale + offsetY, 8f, Paint().apply { color = Color.CYAN })
        }

        // Pins
        val boxSize = 40f * scale
        for (pin in s.pins) {
            val cx = pin.centroid.x.toFloat() * scale + offsetX
            val cy = pin.centroid.y.toFloat() * scale + offsetY

            if (cx < 10f || cy < 10f || cx > viewW - 10f || cy > viewH - 10f) continue

            if (pin.isStanding) {
                canvas.drawRect(cx - boxSize, cy - boxSize, cx + boxSize, cy + boxSize, standingPaint)
                canvas.drawText("P${pin.id}", cx - boxSize, cy - boxSize - 10f, labelPaint)
            } else {
                canvas.drawRect(cx - boxSize * 1.2f, cy - boxSize * 1.2f, cx + boxSize * 1.2f, cy + boxSize * 1.2f, fallenPaint)
                canvas.drawText("#${pin.fallOrder}", cx - boxSize, cy - boxSize * 1.5f, labelPaint)
            }
        }
    }
}
