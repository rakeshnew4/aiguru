package com.aiguruapp.student

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

    private var mouthOpen   = 0f
    private var blinkAmt    = 0f
    private var headBob     = 0f
    private var isSpeaking  = false
    private var audioLevel  = 0f
    private var breathScale = 1f

    // ── Paints ────────────────────────────────────────────────────────────────
    private val bgPaint      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val skinPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5C5A3") }
    private val hairPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2C1A0E") }
    private val scleraPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val irisPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5C3317") }
    private val pupilPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val specPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shirtPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A4A7A") }
    private val collarPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8EEF4") }
    private val browPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2C1A0E")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val nosePaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D49A78")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val smilePaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B03050")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val mouthFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B03050") }
    private val teethPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    // ── Animations ────────────────────────────────────────────────────────────
    private val blinkAnim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
        duration = 2800
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { blinkAmt = it.animatedValue as Float; invalidate() }
    }
    private val breatheAnim = ValueAnimator.ofFloat(0.98f, 1.02f).apply {
        duration = 2200
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener { breathScale = it.animatedValue as Float; invalidate() }
    }
    private val bobAnim = ValueAnimator.ofFloat(-2f, 2f).apply {
        duration = 700
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener { headBob = it.animatedValue as Float }
    }

    init {
        blinkAnim.start()
        breatheAnim.start()
    }

    fun setSpeaking(speaking: Boolean) {
        isSpeaking = speaking
        if (speaking) bobAnim.start() else { bobAnim.cancel(); headBob = 0f }
        invalidate()
    }

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

    // ── Drawing ────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        val w  = width.toFloat()
        val h  = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r  = min(w, h) / 2f

        // Clip to circle
        canvas.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })

        // Background gradient
        bgPaint.shader = RadialGradient(
            cx, cy - r * 0.2f, r * 1.2f,
            intArrayOf(Color.parseColor("#3B2D5C"), Color.parseColor("#1A1033")),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Speaking glow ring
        if (isSpeaking) {
            glowPaint.shader = null
            glowPaint.style = Paint.Style.STROKE
            glowPaint.strokeWidth = r * 0.09f
            glowPaint.color = Color.parseColor("#6066BB6A")
            canvas.drawCircle(cx, cy, r * 0.91f, glowPaint)
        }

        canvas.save()
        canvas.scale(breathScale, breathScale, cx, cy)

        // ── Shirt / body ──────────────────────────────────────────────────────
        val shirtTop = cy + r * 0.35f
        val shirtPath = Path().apply {
            moveTo(0f, h)
            lineTo(0f, shirtTop)
            lineTo(cx - r * 0.25f, shirtTop)
            lineTo(cx,              shirtTop + r * 0.12f)   // V-collar dip
            lineTo(cx + r * 0.25f, shirtTop)
            lineTo(w, shirtTop)
            lineTo(w, h)
            close()
        }
        canvas.drawPath(shirtPath, shirtPaint)

        // White collar / shirt front
        val collarPath = Path().apply {
            moveTo(cx - r * 0.20f, shirtTop)
            lineTo(cx,              shirtTop + r * 0.18f)
            lineTo(cx + r * 0.20f, shirtTop)
            lineTo(cx + r * 0.22f, h)
            lineTo(cx - r * 0.22f, h)
            close()
        }
        canvas.drawPath(collarPath, collarPaint)

        // ── Neck ──────────────────────────────────────────────────────────────
        val neckTop = cy + r * 0.22f + headBob
        canvas.drawRoundRect(
            cx - r * 0.12f, neckTop,
            cx + r * 0.12f, cy + r * 0.48f,
            r * 0.06f, r * 0.06f, skinPaint
        )

        // ── Face ──────────────────────────────────────────────────────────────
        val faceY  = cy - r * 0.04f + headBob
        val faceRx = r * 0.40f
        val faceRy = r * 0.44f
        canvas.drawOval(cx - faceRx, faceY - faceRy, cx + faceRx, faceY + faceRy, skinPaint)

        // ── Ears ──────────────────────────────────────────────────────────────
        canvas.drawOval(cx - faceRx - r*0.07f, faceY - r*0.09f, cx - faceRx + r*0.07f, faceY + r*0.09f, skinPaint)
        canvas.drawOval(cx + faceRx - r*0.07f, faceY - r*0.09f, cx + faceRx + r*0.07f, faceY + r*0.09f, skinPaint)

        // ── Hair ──────────────────────────────────────────────────────────────
        val hairPath = Path()
        hairPath.addArc(
            cx - faceRx * 1.08f, faceY - faceRy * 1.18f,
            cx + faceRx * 1.08f, faceY + faceRy * 0.22f,
            180f, 180f
        )
        hairPath.close()
        canvas.drawPath(hairPath, hairPaint)
        // Side wisps
        canvas.drawOval(cx - faceRx - r*0.12f, faceY - faceRy*0.35f, cx - faceRx + r*0.08f, faceY + faceRy*0.20f, hairPaint)
        canvas.drawOval(cx + faceRx - r*0.08f, faceY - faceRy*0.35f, cx + faceRx + r*0.12f, faceY + faceRy*0.20f, hairPaint)

        // ── Eyebrows ──────────────────────────────────────────────────────────
        browPaint.strokeWidth = r * 0.045f
        val browY   = faceY - faceRy * 0.35f
        val eyeOffX = faceRx * 0.44f
        canvas.drawArc(cx - eyeOffX - r*0.14f, browY - r*0.05f, cx - eyeOffX + r*0.14f, browY + r*0.05f, 200f, 140f, false, browPaint)
        canvas.drawArc(cx + eyeOffX - r*0.14f, browY - r*0.05f, cx + eyeOffX + r*0.14f, browY + r*0.05f, 200f, 140f, false, browPaint)

        // ── Eyes ──────────────────────────────────────────────────────────────
        val eyeY = faceY - faceRy * 0.13f
        val eRx  = r * 0.115f
        val eRy  = eRx * 0.68f * (1f - blinkAmt * 0.92f)
        if (eRy > 0.5f) {
            canvas.drawOval(cx - eyeOffX - eRx, eyeY - eRy, cx - eyeOffX + eRx, eyeY + eRy, scleraPaint)
            canvas.drawOval(cx + eyeOffX - eRx, eyeY - eRy, cx + eyeOffX + eRx, eyeY + eRy, scleraPaint)
            val irisR  = eRy * 0.68f
            canvas.drawCircle(cx - eyeOffX, eyeY, irisR, irisPaint)
            canvas.drawCircle(cx + eyeOffX, eyeY, irisR, irisPaint)
            val pupilR = irisR * 0.52f
            canvas.drawCircle(cx - eyeOffX, eyeY, pupilR, pupilPaint)
            canvas.drawCircle(cx + eyeOffX, eyeY, pupilR, pupilPaint)
            // Specular highlights
            canvas.drawCircle(cx - eyeOffX + irisR*0.28f, eyeY - irisR*0.32f, pupilR*0.38f, specPaint)
            canvas.drawCircle(cx + eyeOffX + irisR*0.28f, eyeY - irisR*0.32f, pupilR*0.38f, specPaint)
        }

        // ── Nose ──────────────────────────────────────────────────────────────
        nosePaint.strokeWidth = r * 0.025f
        val nosePath = Path().apply {
            moveTo(cx - r*0.04f, faceY + faceRy*0.08f)
            quadTo(cx - r*0.08f, faceY + faceRy*0.35f, cx, faceY + faceRy*0.37f)
            quadTo(cx + r*0.08f, faceY + faceRy*0.35f, cx + r*0.04f, faceY + faceRy*0.08f)
        }
        canvas.drawPath(nosePath, nosePaint)

        // ── Mouth ─────────────────────────────────────────────────────────────
        val mouthY = faceY + faceRy * 0.60f
        val mouthW = faceRx * 0.48f
        if (isSpeaking && mouthOpen > 0.06f) {
            val mouthH = r * 0.20f * mouthOpen
            canvas.drawOval(cx - mouthW, mouthY, cx + mouthW, mouthY + mouthH, mouthFillPaint)
            canvas.drawRect(cx - mouthW*0.75f, mouthY + r*0.01f, cx + mouthW*0.75f, mouthY + mouthH*0.45f, teethPaint)
        } else {
            smilePaint.strokeWidth = r * 0.042f
            canvas.drawArc(cx - mouthW, mouthY - r*0.03f, cx + mouthW, mouthY + r*0.07f, 10f, 160f, false, smilePaint)
        }

        canvas.restore()
    }
}