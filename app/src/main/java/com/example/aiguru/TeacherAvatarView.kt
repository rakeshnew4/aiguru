package com.example.aiguru

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

class TeacherAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ─────────────────────────────────────────────
    // Existing API (UNCHANGED)
    // ─────────────────────────────────────────────
    private var mouthOpen = 0f
    private var blinkAmt = 0f
    private var headBob = 0f

    // NEW internal state (no API change)
    private var isSpeaking = false
    private var audioLevel = 0f
    private var breathScale = 1f

    // ─────────────────────────────────────────────
    // Paints (optimized)
    // ─────────────────────────────────────────────
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FDD7B0")
    }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D45A7A")
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ─────────────────────────────────────────────
    // Animations
    // ─────────────────────────────────────────────
    private val blinkAnim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
        duration = 2500
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            blinkAmt = it.animatedValue as Float
            invalidate()
        }
    }

    private val breatheAnim = ValueAnimator.ofFloat(0.98f, 1.02f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            breathScale = it.animatedValue as Float
            invalidate()
        }
    }

    private val bobAnim = ValueAnimator.ofFloat(-2f, 2f).apply {
        duration = 800
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            headBob = it.animatedValue as Float
        }
    }

    init {
        blinkAnim.start()
        breatheAnim.start()
    }

    // ─────────────────────────────────────────────
    // EXISTING METHOD (UNCHANGED SIGNATURE)
    // ─────────────────────────────────────────────
    fun setSpeaking(speaking: Boolean) {
        isSpeaking = speaking

        if (speaking) {
            bobAnim.start()
        } else {
            bobAnim.cancel()
            headBob = 0f
        }

        invalidate()
    }

    // OPTIONAL (safe addition, not breaking)
    fun updateAudioLevel(level: Float) {
        audioLevel = level.coerceIn(0f, 1f)
        mouthOpen = audioLevel
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        blinkAnim.cancel()
        breatheAnim.cancel()
        bobAnim.cancel()
    }

    // ─────────────────────────────────────────────
    // Drawing (Premium simplified)
    // ─────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        val r = min(w, h) / 2

        // ── Background ──
        bgPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.parseColor("#1A1033"), Color.parseColor("#0A061A")),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, bgPaint)

        // ── Glow ──
        val glowColor = if (isSpeaking) "#66BB6A" else "#7E57C2"

        glowPaint.shader = RadialGradient(
            cx, cy, r * 1.2f,
            intArrayOf(Color.parseColor(glowColor), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, glowPaint)

        // ── Face ──
        val faceRadius = r * 0.5f * breathScale
        val faceY = cy - r * 0.1f + headBob

        canvas.drawCircle(cx, faceY, faceRadius, facePaint)

        // ── Eyes ──
        val eyeY = faceY - faceRadius * 0.2f
        val eyeOffset = faceRadius * 0.35f
        val eyeHeight = faceRadius * 0.08f * (1f - blinkAmt)

        if (eyeHeight > 1f) {
            canvas.drawOval(
                cx - eyeOffset - 15,
                eyeY - eyeHeight,
                cx - eyeOffset + 15,
                eyeY + eyeHeight,
                eyePaint
            )
            canvas.drawOval(
                cx + eyeOffset - 15,
                eyeY - eyeHeight,
                cx + eyeOffset + 15,
                eyeY + eyeHeight,
                eyePaint
            )
        }

        // ── Mouth (audio reactive) ──
        val mouthY = faceY + faceRadius * 0.3f
        val mouthWidth = faceRadius * 0.5f

        val mouthHeight = if (isSpeaking) {
            faceRadius * 0.35f * mouthOpen
        } else {
            faceRadius * 0.05f
        }

        canvas.drawRoundRect(
            cx - mouthWidth,
            mouthY,
            cx + mouthWidth,
            mouthY + mouthHeight,
            20f,
            20f,
            mouthPaint
        )
    }
}