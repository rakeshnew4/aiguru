package com.aiguruapp.student

import android.content.Context
import android.graphics.*
import android.view.View

/**
 * Full-screen view that draws a semi-transparent dim overlay with a
 * clear rounded-rect "spotlight" cutout over a target area.
 */
class SpotlightView(context: Context) : View(context) {

    private val dimPaint = Paint().apply {
        color = Color.argb(200, 0, 0, 20)
        isAntiAlias = true
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private var spotlight: RectF? = null
    private val cornerRadius = 20f

    init {
        // Software layer required for PorterDuff.CLEAR to work
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        spotlight?.let { canvas.drawRoundRect(it, cornerRadius, cornerRadius, clearPaint) }
    }

    fun setSpotlight(rect: RectF) {
        spotlight = rect
        invalidate()
    }
}
