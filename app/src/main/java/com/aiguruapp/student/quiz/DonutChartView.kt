package com.aiguruapp.student.quiz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * A lightweight custom View that draws an animated donut / ring chart.
 * No external library required.
 *
 * Call [setScore] to update the chart. Animates on first set.
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Public state
    private var correctCount = 0
    private var wrongCount   = 0
    private var percent      = 0

    // Animation
    private var animatedSweep = 0f
    private var targetSweep   = 0f
    private val animRunnable = object : Runnable {
        override fun run() {
            if (animatedSweep < targetSweep) {
                animatedSweep = minOf(animatedSweep + 6f, targetSweep)
                invalidate()
                postDelayed(this, 16)
            } else {
                animatedSweep = targetSweep
                invalidate()
            }
        }
    }

    // Paints
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE
        color = 0xFFE0E4F0.toInt()
    }
    private val correctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE
        strokeCap  = Paint.Cap.ROUND
        color = 0xFF1E9B6B.toInt()   // colorSuccess
    }
    private val wrongPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE
        strokeCap  = Paint.Cap.ROUND
        color = 0xFFE05050.toInt()   // colorError
    }
    private val percentTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize  = 52f
        typeface  = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = 0xFF1A1A2E.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize  = 28f
        color = 0xFF666B8A.toInt()
    }

    private val oval = RectF()

    fun setScore(correct: Int, total: Int) {
        correctCount = correct
        wrongCount   = total - correct
        percent      = if (total > 0) (correct * 100 / total) else 0

        // correct arc occupies proportional degrees of 340° ring
        targetSweep  = if (total > 0) (correct.toFloat() / total * 340f) else 0f
        animatedSweep = 0f
        removeCallbacks(animRunnable)
        postDelayed(animRunnable, 100)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r  = minOf(cx, cy) - STROKE / 2 - PADDING

        oval.set(cx - r, cy - r, cx + r, cy + r)

        // Track (full grey ring)
        canvas.drawArc(oval, START, 340f, false, trackPaint)

        // Wrong segment (remainder after correct)
        val wrongSweep = 340f - animatedSweep
        if (wrongSweep > 0f) {
            canvas.drawArc(oval, START + animatedSweep, wrongSweep, false, wrongPaint)
        }

        // Correct segment
        if (animatedSweep > 0f) {
            canvas.drawArc(oval, START, animatedSweep, false, correctPaint)
        }

        // Centre text
        val textY = cy - (percentTextPaint.descent() + percentTextPaint.ascent()) / 2
        canvas.drawText("$percent%", cx, textY - 16f, percentTextPaint)
        canvas.drawText("$correctCount / ${correctCount + wrongCount}", cx, textY + 36f, labelPaint)
    }

    companion object {
        private const val STROKE  = 32f
        private const val PADDING = 12f
        private const val START   = -190f     // start slightly left of bottom
    }
}
