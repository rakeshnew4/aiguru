package com.aiguruapp.student.puzzle

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs

/**
 * Canvas-based sliding puzzle board.
 *
 * Usage:
 *   board.setBitmap(bitmap, gridSize = 3)   // slices image and scrambles
 *   board.onWin = { moves -> ... }
 *   board.onMovesChanged = { moves -> ... }
 */
@SuppressLint("ClickableViewAccessibility")
class PuzzleBoardView(context: Context) : View(context) {

    var onWin: ((moves: Int) -> Unit)? = null
    var onMovesChanged: ((moves: Int) -> Unit)? = null

    private var n = 3
    private val blank get() = n * n - 1   // sentinel value for blank tile
    private var grid = IntArray(0)         // grid[position] = tile index (blank = n*n-1)
    private var blankPos = 0
    private var tiles = emptyList<Bitmap>()
    private var moves = 0
    private var isAnimating = false

    // ── Animation state ───────────────────────────────────────────────────────
    private var animFrom = -1
    private var animTo   = -1
    private var animFrac = 0f

    // ── Paint ─────────────────────────────────────────────────────────────────
    private val gapPaint = Paint().apply {
        color = Color.parseColor("#1A1A2E")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // ── Public API ────────────────────────────────────────────────────────────

    fun setBitmap(bmp: Bitmap, gridSize: Int = 3) {
        n = gridSize.coerceIn(2, 5)
        tiles = sliceBitmap(bmp, n)
        grid  = IntArray(n * n) { it }
        blankPos = n * n - 1
        moves = 0
        scramble()
        invalidate()
    }

    fun scramble() {
        repeat(300) {
            val neighbours = adjacentTo(blankPos)
            val pick = neighbours.random()
            swapWithBlank(pick)
        }
        moves = 0
        onMovesChanged?.invoke(0)
        invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (tiles.isEmpty()) return
        val cw = width.toFloat()  / n
        val ch = height.toFloat() / n

        for (pos in 0 until n * n) {
            val tileIdx = grid[pos]
            if (tileIdx == blank) continue   // blank cell — don't draw

            val col = pos % n
            val row = pos / n
            var left = col * cw
            var top  = row * ch

            // Offset animating tile
            if (pos == animFrom && animFrom >= 0) {
                val toCol = animTo % n
                val toRow = animTo / n
                left += (toCol - col) * cw * animFrac
                top  += (toRow - row) * ch * animFrac
            }

            val dst = RectF(left, top, left + cw, top + ch)
            canvas.drawBitmap(tiles[tileIdx], null, dst, bitmapPaint)
        }

        // Grid lines
        for (i in 1 until n) {
            canvas.drawLine(i * cw, 0f, i * cw, height.toFloat(), gapPaint)
            canvas.drawLine(0f, i * ch, width.toFloat(), i * ch, gapPaint)
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP || isAnimating || tiles.isEmpty()) return true
        val col = (event.x / (width.toFloat() / n)).toInt().coerceIn(0, n - 1)
        val row = (event.y / (height.toFloat() / n)).toInt().coerceIn(0, n - 1)
        val tapped = row * n + col
        if (isAdjacentToBlank(tapped)) animateSlide(tapped)
        return true
    }

    // ── Animation ─────────────────────────────────────────────────────────────

    private fun animateSlide(from: Int) {
        isAnimating = true
        animFrom = from
        animTo   = blankPos
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration    = 130
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                animFrac = va.animatedFraction
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    swapWithBlank(from)
                    moves++
                    animFrom = -1; animTo = -1; animFrac = 0f
                    isAnimating = false
                    onMovesChanged?.invoke(moves)
                    invalidate()
                    checkWin()
                }
            })
            start()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isAdjacentToBlank(pos: Int): Boolean {
        val pr = pos / n;     val pc = pos % n
        val br = blankPos / n; val bc = blankPos % n
        return (pr == br && abs(pc - bc) == 1) || (pc == bc && abs(pr - br) == 1)
    }

    private fun adjacentTo(pos: Int): List<Int> {
        val r = pos / n; val c = pos % n
        return buildList {
            if (r > 0)     add((r - 1) * n + c)
            if (r < n - 1) add((r + 1) * n + c)
            if (c > 0)     add(r * n + c - 1)
            if (c < n - 1) add(r * n + c + 1)
        }
    }

    private fun swapWithBlank(pos: Int) {
        grid[blankPos] = grid[pos]
        grid[pos]      = blank
        blankPos       = pos
    }

    private fun checkWin() {
        val solved = (0 until n * n).all { grid[it] == it }
        if (solved) onWin?.invoke(moves)
    }

    private fun sliceBitmap(src: Bitmap, n: Int): List<Bitmap> {
        val scaled = Bitmap.createScaledBitmap(src, n * 256, n * 256, true)
        val tileW  = scaled.width  / n
        val tileH  = scaled.height / n
        return (0 until n * n - 1).map { idx ->    // n*n-1 tiles; last slot stays blank
            val col = idx % n
            val row = idx / n
            Bitmap.createBitmap(scaled, col * tileW, row * tileH, tileW, tileH)
        }
    }
}
