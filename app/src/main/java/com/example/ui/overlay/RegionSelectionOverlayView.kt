package com.example.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

class RegionSelectionOverlayView(
    context: Context,
    private val onRegionSelected: (Rect) -> Unit,
    private val onCancelled: () -> Unit
) : View(context) {

    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isSelecting = false

    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#99000000") // 60% dark dimming
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#38BDF8") // Sky cyan
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val dashBorderPaint = Paint().apply {
        color = Color.parseColor("#A855F7") // Purple
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(15f, 15f), 0f)
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        isAntiAlias = true
        setShadowLayer(8f, 0f, 4f, Color.BLACK)
    }

    private val cancelRect = RectF(40f, 80f, 240f, 160f)
    private val cancelPaint = Paint().apply {
        color = Color.parseColor("#DC2626")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw dim background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // Draw cancel button
        canvas.drawRoundRect(cancelRect, 20f, 20f, cancelPaint)
        canvas.drawText("Cancel ✕", 65f, 135f, textPaint)

        // Instruction text
        val instructions = if (isSelecting) "Release to capture region" else "Drag across any question or diagram"
        canvas.drawText(instructions, width / 2f - 260f, 135f, textPaint)

        if (isSelecting || (startX != currentX && startY != currentY)) {
            val left = minOf(startX, currentX)
            val top = minOf(startY, currentY)
            val right = maxOf(startX, currentX)
            val bottom = maxOf(startY, currentY)

            // Clear the selected rectangle
            canvas.drawRect(left, top, right, bottom, clearPaint)

            // Draw glowing cyan and dashed purple borders
            canvas.drawRect(left, top, right, bottom, borderPaint)
            canvas.drawRect(left, top, right, bottom, dashBorderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (cancelRect.contains(event.x, event.y)) {
                    onCancelled()
                    return true
                }
                startX = event.x
                startY = event.y
                currentX = event.x
                currentY = event.y
                isSelecting = true
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                currentX = event.x
                currentY = event.y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                isSelecting = false
                val left = minOf(startX, currentX).toInt()
                val top = minOf(startY, currentY).toInt()
                val right = maxOf(startX, currentX).toInt()
                val bottom = maxOf(startY, currentY).toInt()

                val rect = Rect(left, top, right, bottom)
                if (rect.width() > 30 && rect.height() > 30) {
                    onRegionSelected(rect)
                } else {
                    onCancelled()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
