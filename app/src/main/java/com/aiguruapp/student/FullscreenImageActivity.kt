package com.aiguruapp.student

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide

/**
 * Full-screen pinch-to-zoom image viewer.
 *
 * Launch with [EXTRA_IMAGE_URI] set to the URI string of the image to display.
 * Tap anywhere (without dragging) to dismiss. Use the ✕ button in the corner as well.
 */
class FullscreenImageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
    }

    private lateinit var imageView: ImageView
    private val matrix = Matrix()
    private var scaleFactor = 1f

    // Touch tracking
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false

    private lateinit var scaleDetector: ScaleGestureDetector

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen black window
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        supportActionBar?.hide()

        val uriStr = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uriStr == null) { finish(); return }

        // ── Root frame ────────────────────────────────────────────────────────
        val frame = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
        }

        // ── Zoomable ImageView ────────────────────────────────────────────────
        imageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.MATRIX
        }
        frame.addView(imageView)

        // ── Close button ──────────────────────────────────────────────────────
        val dp = resources.displayMetrics.density
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = (40 * dp).toInt()
                marginEnd = (8 * dp).toInt()
            }
            setOnClickListener { finish() }
        }
        frame.addView(closeBtn)

        setContentView(frame)

        // ── Load image ────────────────────────────────────────────────────────
        Glide.with(this)
            .load(Uri.parse(uriStr))
            .into(imageView)

        // ── Scale gesture ─────────────────────────────────────────────────────
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scaleFactor * detector.scaleFactor).coerceIn(0.5f, 8f)
                val ratio = newScale / scaleFactor
                scaleFactor = newScale
                matrix.postScale(ratio, ratio, detector.focusX, detector.focusY)
                imageView.imageMatrix = matrix
                return true
            }
        })

        // ── Touch listener (pinch + pan + tap-to-close) ───────────────────────
        imageView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (!isDragging && (kotlin.math.abs(dx) > 8f || kotlin.math.abs(dy) > 8f)) {
                            isDragging = true
                        }
                        if (isDragging) {
                            matrix.postTranslate(dx, dy)
                            imageView.imageMatrix = matrix
                        }
                        lastX = event.x
                        lastY = event.y
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging && event.pointerCount == 1) {
                        finish()
                    }
                }
            }
            true
        }

        // ── Center image once layout is measured ──────────────────────────────
        imageView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                imageView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                centerAndFitImage()
            }
        })
    }

    /**
     * Scale the image to fit the screen while maintaining aspect ratio, then center it.
     * Called after the view is laid out so we have correct dimensions.
     */
    private fun centerAndFitImage() {
        val drawable = imageView.drawable ?: return
        val viewW = imageView.width.toFloat()
        val viewH = imageView.height.toFloat()
        val drawW = drawable.intrinsicWidth.toFloat()
        val drawH = drawable.intrinsicHeight.toFloat()
        if (drawW <= 0f || drawH <= 0f) return

        val scale = minOf(viewW / drawW, viewH / drawH)
        scaleFactor = scale

        val tx = (viewW - drawW * scale) / 2f
        val ty = (viewH - drawH * scale) / 2f

        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(tx, ty)
        imageView.imageMatrix = matrix
    }
}
