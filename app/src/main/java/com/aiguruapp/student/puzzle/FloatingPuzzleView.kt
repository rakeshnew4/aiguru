package com.aiguruapp.student.puzzle

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * FloatingPuzzleView — draggable floating puzzle game overlay.
 *
 * Mirrors FloatingCalculatorView pattern: added via addContentView() in BaseActivity.
 * Gate: student must complete ≥ 2 BB sessions today (checked via Firestore).
 * Play cap: 15 minutes/day tracked in SharedPreferences by PuzzleGate.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility", "SetTextI18n")
class FloatingPuzzleView(context: Context) : FrameLayout(context) {

    // ── Constants ─────────────────────────────────────────────────────────────
    private val BUBBLE_SZ  = dp(52)
    private val PANEL_W    = dp(308)
    private val BOARD_SZ   = dp(284)

    // ── Colours ───────────────────────────────────────────────────────────────
    private val cBubble  = 0xFF1B5E20.toInt()
    private val cPanel   = 0xFF1A1A2E.toInt()
    private val cHeader  = 0xFF16213E.toInt()
    private val cBtn     = 0xFF0F3460.toInt()
    private val cBtnPrs  = 0xFF1A4A8A.toInt()
    private val cText    = 0xFFEEEEEE.toInt()
    private val cTimer   = 0xFF4CAF50.toInt()
    private val cTimerLow= 0xFFFF5722.toInt()

    // ── Views ─────────────────────────────────────────────────────────────────
    private val bubble: FrameLayout
    private val panel: LinearLayout
    private val tvTimer: TextView
    private val tvMoves: TextView
    private val boardView: PuzzleBoardView
    private val winOverlay: FrameLayout
    private val tvWinMsg: TextView
    private val loadingBar: ProgressBar

    // ── State ─────────────────────────────────────────────────────────────────
    private var panelVisible  = false
    private var imageLoaded   = false
    private var sessionStartMs = 0L
    private val timerHandler   = Handler(Looper.getMainLooper())
    private var timerRunning   = false

    // ── Drag ──────────────────────────────────────────────────────────────────
    private var touchStartRawY = 0f
    private var viewStartTop   = 0f
    private var isDragging     = false

    companion object {
        private var savedYFraction = 0.6f   // persists position across activities in session
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        isClickable = false
        isFocusable = false
        setBackgroundColor(Color.TRANSPARENT)

        boardView   = buildBoardView()
        tvTimer     = newTv(13f, cTimer)
        tvMoves     = newTv(13f, cText)
        loadingBar  = ProgressBar(context).apply { isIndeterminate = true }
        winOverlay  = buildWinOverlay().also { it.visibility = View.GONE }
        tvWinMsg    = winOverlay.findViewWithTag("msg")
        panel       = buildPanel()
        bubble      = buildBubble()

        addView(panel,  LayoutParams(PANEL_W, WRAP_CONTENT).apply { gravity = Gravity.END })
        addView(bubble, LayoutParams(BUBBLE_SZ, BUBBLE_SZ).apply { gravity = Gravity.END })

        post { placeBubbleFromFraction(savedYFraction) }
        attachDrag()
    }

    // ── Bubble ────────────────────────────────────────────────────────────────

    private fun buildBubble(): FrameLayout {
        val bub = FrameLayout(context).apply {
            background = oval(cBubble)
            elevation  = dp(8).toFloat()
        }
        val icon = TextView(context).apply {
            text     = "🧩"
            textSize = 22f
            gravity  = Gravity.CENTER
        }
        bub.addView(icon, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return bub
    }

    // ── Panel ─────────────────────────────────────────────────────────────────

    private fun buildPanel(): LinearLayout {
        val pnl = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background  = roundRect(cPanel, dp(18))
            elevation   = dp(10).toFloat()
            setPadding(dp(10), dp(10), dp(10), dp(12))
            visibility  = View.GONE
        }

        // Title row
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            background  = roundRect(cHeader, dp(10))
            setPadding(dp(10), dp(6), dp(8), dp(6))
        }
        val tvTitle = newTv(13f, cText).apply { text = "🧩 Puzzle Break" }
        val tvTimerLabel = newTv(11f, 0xFFAAAAAA.toInt()).apply { text = "  ⏱ " }
        val closeBtn = makeBtn("✕", dp(28), dp(24)) { closePanel() }

        titleRow.addView(tvTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        titleRow.addView(tvTimerLabel)
        titleRow.addView(tvTimer, LinearLayout.LayoutParams(dp(60), WRAP_CONTENT))
        titleRow.addView(closeBtn, LinearLayout.LayoutParams(dp(28), dp(24)))
        pnl.addView(titleRow, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(8)))

        // Loading indicator (hidden once image loads)
        pnl.addView(loadingBar, lp(MATCH_PARENT, dp(4), bm = dp(4)))

        // Board
        val boardContainer = FrameLayout(context)
        boardContainer.addView(boardView, FrameLayout.LayoutParams(BOARD_SZ, BOARD_SZ).apply {
            gravity = Gravity.CENTER
        })
        boardContainer.addView(winOverlay, FrameLayout.LayoutParams(BOARD_SZ, BOARD_SZ).apply {
            gravity = Gravity.CENTER
        })
        pnl.addView(boardContainer, lp(MATCH_PARENT, BOARD_SZ, bm = dp(8)))

        // Bottom row: moves + shuffle
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }
        tvMoves.text = "Moves: 0"
        val shuffleBtn = makeBtn("🔀 Shuffle", WRAP_CONTENT, dp(32)) {
            winOverlay.visibility = View.GONE
            boardView.scramble()
            tvMoves.text = "Moves: 0"
        }
        bottomRow.addView(tvMoves,    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bottomRow.addView(shuffleBtn, LinearLayout.LayoutParams(WRAP_CONTENT, dp(32)))
        pnl.addView(bottomRow, lp(MATCH_PARENT, WRAP_CONTENT))

        return pnl
    }

    private fun buildBoardView(): PuzzleBoardView {
        val board = PuzzleBoardView(context)
        board.onMovesChanged = { m -> tvMoves.text = "Moves: $m" }
        board.onWin = { moves ->
            stopTimer(savePlayTime = true)
            tvWinMsg.text = "🎉 Solved in $moves moves!"
            winOverlay.visibility = View.VISIBLE
        }
        return board
    }

    private fun buildWinOverlay(): FrameLayout {
        val overlay = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape       = GradientDrawable.RECTANGLE
                setColor(0xCC1A1A2E.toInt())
                cornerRadius = dp(8).toFloat()
            }
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
        }
        val msg = newTv(15f, 0xFFFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
            tag     = "msg"
        }
        val playAgainBtn = makeBtn("▶ Play Again", WRAP_CONTENT, dp(36)) {
            if (PuzzleGate.playMsRemaining(context) > 0) {
                winOverlay.visibility = View.GONE
                boardView.scramble()
                tvMoves.text = "Moves: 0"
                startTimer()
            } else {
                showToast("No time left today 📚")
            }
        }
        val closeBtn2 = makeBtn("✕ Close", WRAP_CONTENT, dp(32)) { closePanel() }
        col.addView(msg,          lp(WRAP_CONTENT, WRAP_CONTENT, bm = dp(12)))
        col.addView(playAgainBtn, lp(WRAP_CONTENT, dp(36), bm = dp(8)))
        col.addView(closeBtn2,    lp(WRAP_CONTENT, dp(32)))
        overlay.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            gravity = Gravity.CENTER
        })
        return overlay
    }

    // ── Panel open/close ──────────────────────────────────────────────────────

    private fun openPanel() {
        PuzzleGate.checkUnlocked { unlocked, bbToday ->
            post {
                if (!unlocked) {
                    val needed = 2 - bbToday
                    showToast("Complete $needed more Blackboard lesson${if (needed > 1) "s" else ""} to unlock 🎓")
                    return@post
                }
                val remaining = PuzzleGate.playMsRemaining(context)
                if (remaining <= 0L) {
                    showToast("15-min break used up for today 📚")
                    return@post
                }
                panelVisible = true
                panel.visibility = View.VISIBLE
                post { updatePanelPosition() }
                if (!imageLoaded) loadPuzzleImage() else startTimer()
            }
        }
    }

    private fun closePanel() {
        stopTimer(savePlayTime = true)
        panelVisible = false
        panel.visibility = View.GONE
    }

    // ── Image loading from Firestore ──────────────────────────────────────────

    private fun loadPuzzleImage() {
        loadingBar.visibility = View.VISIBLE
        FirebaseFirestore.getInstance()
            .collection("puzzles")
            .whereEqualTo("active", true)
            .get()
            .addOnSuccessListener { snap ->
                val docs = snap.documents.filter { it.exists() }
                if (docs.isEmpty()) { loadFallback(); return@addOnSuccessListener }
                val doc      = docs.random()
                val imageUrl = doc.getString("image_url") ?: ""
                val gridSize = doc.getLong("grid_size")?.toInt() ?: 3
                if (imageUrl.isBlank()) { loadFallback(); return@addOnSuccessListener }

                Glide.with(context)
                    .asBitmap()
                    .load(imageUrl)
                    .override(gridSize * 256, gridSize * 256)
                    .into(object : CustomTarget<android.graphics.Bitmap>() {
                        override fun onResourceReady(
                            resource: android.graphics.Bitmap,
                            transition: Transition<in android.graphics.Bitmap>?
                        ) {
                            post {
                                boardView.setBitmap(resource, gridSize)
                                loadingBar.visibility = View.GONE
                                imageLoaded = true
                                startTimer()
                            }
                        }
                        override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                        override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                            post { loadFallback() }
                        }
                    })
            }
            .addOnFailureListener { loadFallback() }
    }

    private fun loadFallback() {
        // Generate a simple colorful placeholder bitmap
        val size = 768
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val colors = listOf(0xFF1565C0, 0xFF2E7D32, 0xFFC62828, 0xFF6A1B9A,
                            0xFF00838F, 0xFFF57F17, 0xFF558B2F, 0xFF4527A0,
                            0xFFAD1457).map { it.toInt() }
        val tileSize = size / 3
        for (row in 0 until 3) for (col in 0 until 3) {
            val paint = android.graphics.Paint().apply { color = colors[(row * 3 + col) % colors.size] }
            canvas.drawRect(
                (col * tileSize).toFloat(), (row * tileSize).toFloat(),
                ((col + 1) * tileSize).toFloat(), ((row + 1) * tileSize).toFloat(), paint
            )
            val tp = android.graphics.Paint().apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 80f
                textAlign = android.graphics.Paint.Align.CENTER
            }
            canvas.drawText(
                "${row * 3 + col + 1}",
                (col * tileSize + tileSize / 2).toFloat(),
                (row * tileSize + tileSize / 2 + 30).toFloat(), tp
            )
        }
        boardView.setBitmap(bmp, 3)
        loadingBar.visibility = View.GONE
        imageLoaded = true
        startTimer()
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private fun startTimer() {
        if (timerRunning) return
        sessionStartMs = System.currentTimeMillis()
        timerRunning   = true
        timerTick()
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            val remaining = PuzzleGate.playMsRemaining(context) -
                    (System.currentTimeMillis() - sessionStartMs)
            val clamped = maxOf(0L, remaining)
            val mins = (clamped / 60_000).toInt()
            val secs = ((clamped % 60_000) / 1000).toInt()
            tvTimer.text = "%d:%02d".format(mins, secs)
            tvTimer.setTextColor(if (clamped < 60_000) cTimerLow else cTimer)
            if (clamped <= 0L) {
                stopTimer(savePlayTime = true)
                closePanel()
                showToast("Time's up! Back to studying 📚")
            } else {
                timerHandler.postDelayed(this, 1_000)
            }
        }
    }

    private fun timerTick() { timerHandler.post(timerRunnable) }

    private fun stopTimer(savePlayTime: Boolean) {
        timerRunning = false
        timerHandler.removeCallbacks(timerRunnable)
        if (savePlayTime && sessionStartMs > 0L) {
            val elapsed = System.currentTimeMillis() - sessionStartMs
            PuzzleGate.recordPlayMs(context, elapsed)
            sessionStartMs = 0L
        }
    }

    // ── Drag (mirrors FloatingCalculatorView exactly) ─────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag() {
        bubble.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartRawY = ev.rawY
                    viewStartTop   = bubbleTopPx()
                    isDragging     = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - touchStartRawY
                    if (abs(dy) > dp(6)) isDragging = true
                    if (isDragging) { moveBubbleTo((viewStartTop + dy).toInt()); updatePanelPos() }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) openPanel()
                    else if (height > 0) savedYFraction = bubbleTopPx() / height.toFloat()
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun bubbleTopPx(): Float {
        val lp = bubble.layoutParams as LayoutParams
        return if (lp.gravity and Gravity.CENTER_VERTICAL != 0) {
            ((height - BUBBLE_SZ) / 2).toFloat()
        } else lp.topMargin.toFloat()
    }

    private fun moveBubbleTo(topPx: Int) {
        val clamped = max(0, min(topPx, height - BUBBLE_SZ))
        (bubble.layoutParams as LayoutParams).apply {
            gravity     = Gravity.END
            topMargin   = clamped
            bottomMargin = 0
        }.also { bubble.layoutParams = it }
    }

    private fun placeBubbleFromFraction(frac: Float) {
        if (height == 0) return
        moveBubbleTo(((height - BUBBLE_SZ) * frac).toInt())
    }

    private fun updatePanelPos() {
        if (!panelVisible) return
        val bTop    = bubbleTopPx().toInt()
        val pHeight = panel.measuredHeight.let { if (it > 0) it else dp(420) }
        var top     = bTop + BUBBLE_SZ / 2 - pHeight / 2
        top = max(dp(8), top)
        top = min(height - pHeight - dp(8), top)
        (panel.layoutParams as LayoutParams).apply {
            gravity     = Gravity.END
            topMargin   = top
            bottomMargin = 0
            marginEnd   = BUBBLE_SZ + dp(10)
        }.also { panel.layoutParams = it }
    }

    private fun updatePanelPosition() = updatePanelPos()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun newTv(size: Float, color: Int) = TextView(context).apply {
        textSize = size; setTextColor(color)
    }

    private fun makeBtn(label: String, w: Int, h: Int, onClick: () -> Unit) =
        TextView(context).apply {
            text       = label
            textSize   = 11f
            gravity    = Gravity.CENTER
            setTextColor(cText)
            background = roundRect(cBtn, dp(8))
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(w, h)
        }

    private fun lp(w: Int, h: Int, bm: Int = 0) =
        LinearLayout.LayoutParams(w, h).also { it.bottomMargin = bm }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun roundRect(color: Int, radius: Int) = GradientDrawable().apply {
        shape        = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun showToast(msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
}
