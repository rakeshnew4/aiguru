package com.aiguruapp.student.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * A self-animating loading widget — two concentric rounded squares that
 * counter-rotate, producing a satisfying "thinking" effect.
 *
 * Outer box: app dark-navy (#1A1A2E) with a soft drop-shadow.
 * Inner box: cobalt blue (#1565C0), counter-rotates at 40% speed.
 *
 * Works at any size. Typical usage: 48–72 dp.
 *
 * Usage in XML:
 *   <com.aiguruapp.student.widget.BoxSpinnerView
 *       android:id="@+id/boxSpinner"
 *       android:layout_width="56dp"
 *       android:layout_height="56dp" />
 */
class BoxSpinnerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A2E")
        style = Paint.Style.FILL
        setShadowLayer(22f, 0f, 9f, Color.parseColor("#44000000"))
    }

    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        style = Paint.Style.FILL
    }

    private val outerRect = RectF()
    private val innerRect = RectF()
    private var angle = 0f

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        // setShadowLayer only works with software rendering
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    /** Stop the animation (e.g. when loading finishes). */
    fun stop() {
        animator.cancel()
        invalidate()
    }

    /** Restart after stop(). */
    fun start() {
        if (!animator.isRunning) animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outerS = minOf(width, height) * 0.65f
        val innerS = outerS * 0.48f
        val r = outerS * 0.22f

        outerRect.set(cx - outerS / 2, cy - outerS / 2, cx + outerS / 2, cy + outerS / 2)
        innerRect.set(cx - innerS / 2, cy - innerS / 2, cx + innerS / 2, cy + innerS / 2)

        // Outer box rotates clockwise
        canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.drawRoundRect(outerRect, r, r, outerPaint)
        canvas.restore()

        // Inner accent box counter-rotates at 40% speed for depth illusion
        canvas.save()
        canvas.rotate(-angle * 0.4f, cx, cy)
        canvas.drawRoundRect(innerRect, r * 0.55f, r * 0.55f, innerPaint)
        canvas.restore()
    }
}
