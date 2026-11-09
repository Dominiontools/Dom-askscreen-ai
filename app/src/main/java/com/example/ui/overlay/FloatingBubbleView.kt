package com.example.ui.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.example.data.local.PreferencesManager
import com.example.data.model.BubbleState

@SuppressLint("ViewConstructor")
class FloatingBubbleView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val prefs: PreferencesManager,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit
) : View(context) {

    private var screenWidth = 1080
    private var screenHeight = 2400

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    var bubbleState: BubbleState = BubbleState.IDLE
        set(value) {
            field = value
            if (value != BubbleState.IDLE) {
                startPulseAnimation()
            } else {
                stopPulseAnimation()
            }
            invalidate()
        }

    private var pulseRadiusRatio = 1.0f
    private var pulseAnimator: ValueAnimator? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            onLongPress()
        }
    })

    init {
        updateDimensions()
    }

    fun updateDimensions() {
        val metrics = context.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        val opacity = prefs.bubbleOpacityPercent / 100f
        alpha = opacity
        invalidate()
    }

    private fun startPulseAnimation() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ValueAnimator.ofFloat(1.0f, 1.25f).apply {
            duration = 750
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                pulseRadiusRatio = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseRadiusRatio = 1.0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val centerX = w / 2f
        val centerY = h / 2f
        val radius = (minOf(w, h) / 2f) - 10f

        // Outer pulse glow
        if (bubbleState != BubbleState.IDLE) {
            val pulseRadius = radius * pulseRadiusRatio
            glowPaint.color = when (bubbleState) {
                BubbleState.CAPTURING_SCREEN -> Color.parseColor("#38BDF8")
                BubbleState.LISTENING_AUDIO -> Color.parseColor("#F59E0B")
                BubbleState.PROCESSING_AI -> Color.parseColor("#A855F7")
                BubbleState.ERROR -> Color.parseColor("#EF4444")
                else -> Color.parseColor("#38BDF8")
            }
            glowPaint.alpha = (180 * (2f - pulseRadiusRatio)).toInt().coerceIn(0, 255)
            canvas.drawCircle(centerX, centerY, pulseRadius, glowPaint)
        }

        // Base gradient orb
        val shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                Color.parseColor("#2563EB"), // Royal blue
                Color.parseColor("#7C3AED"), // Violet
                Color.parseColor("#06B6D4")  // Cyan
            ),
            floatArrayOf(0.0f, 0.6f, 1.0f),
            Shader.TileMode.CLAMP
        )
        bgPaint.shader = shader
        canvas.drawCircle(centerX, centerY, radius, bgPaint)

        // Subtle dark rim
        glowPaint.color = Color.parseColor("#38BDF8")
        glowPaint.alpha = 140
        canvas.drawCircle(centerX, centerY, radius, glowPaint)

        // Center AI Sparkle symbol
        val sparkleSize = radius * 0.45f
        drawAiSparkle(canvas, centerX, centerY, sparkleSize)
    }

    private fun drawAiSparkle(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val path = Path().apply {
            moveTo(cx, cy - size)
            quadTo(cx + size * 0.15f, cy - size * 0.15f, cx + size, cy)
            quadTo(cx + size * 0.15f, cy + size * 0.15f, cx, cy + size)
            quadTo(cx - size * 0.15f, cy + size * 0.15f, cx - size, cy)
            quadTo(cx - size * 0.15f, cy - size * 0.15f, cx, cy - size)
            close()
        }
        canvas.drawPath(path, iconPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (Math.hypot(dx.toDouble(), dy.toDouble()) > 10) {
                    isDragging = true
                    layoutParams.x = (initialX + dx.toInt()).coerceIn(0, screenWidth - width)
                    layoutParams.y = (initialY + dy.toInt()).coerceIn(60, screenHeight - height - 120)
                    try {
                        windowManager.updateViewLayout(this, layoutParams)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    snapToEdge()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Gently snap the bubble to the nearest left or right screen edge.
     */
    private fun snapToEdge() {
        val currentX = layoutParams.x
        val targetX = if (currentX + width / 2 < screenWidth / 2) {
            12 // Snap to left edge
        } else {
            screenWidth - width - 12 // Snap to right edge
        }

        val animator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                layoutParams.x = it.animatedValue as Int
                try {
                    windowManager.updateViewLayout(this@FloatingBubbleView, layoutParams)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        animator.start()

        // Save position
        prefs.bubblePosX = targetX
        prefs.bubblePosY = layoutParams.y
    }
}
