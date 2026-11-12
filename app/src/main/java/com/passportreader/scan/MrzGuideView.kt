package com.passportreader.scan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Darkens everything except a passport-data-page-shaped window (ID-3, 125 x 88 mm) and marks
 * the MRZ band along its bottom edge.
 */
class MrzGuideView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val scrim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * density; color = Color.WHITE
    }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * density; color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(10 * density, 8 * density), 0f)
    }
    private val bandLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12 * density; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val window = RectF()

    /** Width / height of the guide window. Default: TD3 passport data page. */
    var aspect: Float = 125f / 88f
    /** Draw the dashed MRZ band (off for delivery slips). */
    var showMrzBand: Boolean = true
    /** If set, the window's top edge as a fraction of the view height. */
    var topFraction: Float? = null
    private val path = Path()

    /** Turns the frame green once a valid MRZ has been seen at least once. */
    var locked: Boolean = false
        set(value) {
            field = value
            frame.color = if (value) Color.rgb(76, 217, 100) else Color.WHITE
            band.color = frame.color
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val ww = w * 0.92f
        val wh = ww / aspect
        val left = (w - ww) / 2
        val top = topFraction?.let { h * it } ?: ((h - wh) / 2.4f)
        window.set(left, top, left + ww, top + wh)
        val r = 14 * density

        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        path.addRoundRect(window, r, r, Path.Direction.CW)
        canvas.drawPath(path, scrim)
        canvas.drawRoundRect(window, r, r, frame)
        if (!showMrzBand) return

        // MRZ occupies roughly the bottom 22% of the TD3 data page.
        val bandTop = window.bottom - wh * 0.24f
        val inset = 10 * density
        canvas.drawRoundRect(
            RectF(window.left + inset, bandTop, window.right - inset, window.bottom - inset),
            6 * density, 6 * density, band,
        )
        val bandCenterY = (bandTop + window.bottom - inset) / 2 + bandLabel.textSize / 3
        canvas.drawText("<<<  the two MRZ lines go here  <<<", window.centerX(), bandCenterY, bandLabel)
    }

    /** Bottom of the guide window, for placing the hint text below it. */
    fun windowBottom(): Float = window.bottom
}
