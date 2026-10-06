package com.srooyesh.seedcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/** Centered scan ROI. Size is controlled only from the main-page settings. */
class ScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    companion object {
        private const val MIN_WIDTH = 0.56f
        private const val MAX_WIDTH = 0.96f
        private const val MIN_HEIGHT = 0.12f
        private const val MAX_HEIGHT = 0.58f
        private const val DEFAULT_WIDTH = 0.84f
        private const val DEFAULT_HEIGHT = 0.28f
    }

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA9000000.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = dp(13f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private var widthFraction = DEFAULT_WIDTH
    private var heightFraction = DEFAULT_HEIGHT
    private val rect = RectF()

    fun setFractions(widthFraction: Float, heightFraction: Float) {
        this.widthFraction = widthFraction.coerceIn(MIN_WIDTH, MAX_WIDTH)
        this.heightFraction = heightFraction.coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        invalidate()
    }

    fun getWidthPercent(): Int = (widthFraction * 100f).roundToInt()
    fun getHeightPercent(): Int = (heightFraction * 100f).roundToInt()

    fun scanRectInPreview(): Rect {
        updateRect()
        return Rect(
            rect.left.roundToInt().coerceAtLeast(0),
            rect.top.roundToInt().coerceAtLeast(0),
            rect.right.roundToInt().coerceAtMost(width),
            rect.bottom.roundToInt().coerceAtMost(height)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateRect()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateRect()

        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, dimPaint)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, dimPaint)

        canvas.drawRoundRect(rect, dp(12f), dp(12f), linePaint)
        val thirdW = rect.width() / 3f
        val thirdH = rect.height() / 3f
        canvas.drawLine(rect.left + thirdW, rect.top, rect.left + thirdW, rect.bottom, gridPaint)
        canvas.drawLine(rect.left + thirdW * 2f, rect.top, rect.left + thirdW * 2f, rect.bottom, gridPaint)
        canvas.drawLine(rect.left, rect.top + thirdH, rect.right, rect.top + thirdH, gridPaint)
        canvas.drawLine(rect.left, rect.top + thirdH * 2f, rect.right, rect.top + thirdH * 2f, gridPaint)

        val label = "محدوده تشخیص  ${getWidthPercent()}٪ × ${getHeightPercent()}٪"
        val labelY = (rect.top - dp(12f)).coerceAtLeast(dp(26f))
        canvas.drawText(label, width / 2f, labelY, textPaint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = false

    private fun updateRect() {
        val w = width * widthFraction
        val h = height * heightFraction
        rect.set(
            (width - w) / 2f,
            (height - h) / 2f,
            (width + w) / 2f,
            (height + h) / 2f
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
