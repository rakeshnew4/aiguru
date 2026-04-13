package com.aiguruapp.student.calculator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * FloatingCalculatorView — a self-contained in-app floating calculator.
 *
 * Usage (from any Activity):
 *   addContentView(FloatingCalculatorView(this),
 *       ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
 *
 * Features:
 *  - Draggable round bubble pinned to the right screen edge
 *  - Expand / collapse calculator panel with a tap
 *  - 3 modes: Basic (grades 1–6) · Scientific (7–10) · Advanced (11–12)
 *  - Expression-based input with live result preview
 *  - DEG / RAD toggle (Scientific & Advanced)
 *  - Persists bubble Y position across activities in the same session
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility", "SetTextI18n")
class FloatingCalculatorView(context: Context) : FrameLayout(context) {

    // ── Engine ────────────────────────────────────────────────────────────────
    private val engine = CalculatorEngine()

    // ── State ─────────────────────────────────────────────────────────────────
    private val expression = StringBuilder()
    private var panelVisible = false
    private var currentMode = CalcMode.BASIC

    // ── Views ──────────────────────────────────────────────────────────────────
    private val bubble: FrameLayout
    private val panel: LinearLayout
    private val tvExpr: TextView
    private val tvResult: TextView
    private val btnGrid: LinearLayout
    private val modeBar: LinearLayout
    private var angleModeBtn: Button? = null

    // ── Drag ──────────────────────────────────────────────────────────────────
    private var touchStartRawY = 0f
    private var viewStartTop = 0f
    private var isDragging = false

    // ── Modes ─────────────────────────────────────────────────────────────────
    enum class CalcMode(val label: String) {
        BASIC("Basic"),
        SCIENTIFIC("Scientific"),
        ADVANCED("Advanced")
    }

    // ── Colour palette (dark calculator theme) ────────────────────────────────
    private val cBg        = 0xFF1A1A2E.toInt()   // panel / bubble bg
    private val cPanel     = 0xFF1E1E30.toInt()   // panel surface
    private val cDisplay   = 0xFF12122A.toInt()   // display area background
    private val cNum       = 0xFF2A2A3E.toInt()   // digit buttons
    private val cNumPrs    = 0xFF3A3A50.toInt()   // digit button pressed
    private val cOp        = 0xFF1956A8.toInt()   // operator  buttons
    private val cOpPrs     = 0xFF2268C8.toInt()
    private val cFn        = 0xFF4A2A80.toInt()   // function  buttons  (purple)
    private val cFnPrs     = 0xFF6040A0.toInt()
    private val cEq        = 0xFF1565C0.toInt()   // equals
    private val cEqPrs     = 0xFF1E88E5.toInt()
    private val cClear     = 0xFFB71C1C.toInt()   // clear / C
    private val cClearPrs  = 0xFFD32F2F.toInt()
    private val cBack      = 0xFF37474F.toInt()   // backspace
    private val cBackPrs   = 0xFF546E7A.toInt()
    private val cText      = 0xFFFFFFFF.toInt()   // button text
    private val cResultPre = 0xFF9E9E9E.toInt()   // preview text

    // ── Session-persistent bubble Y (fraction 0..1) ───────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // init
    // ─────────────────────────────────────────────────────────────────────────

    init {
        isClickable  = false
        isFocusable  = false
        setBackgroundColor(Color.TRANSPARENT)

        tvExpr   = newTv(18f, cText,      TextUtils.TruncateAt.START)
        tvResult = newTv(13f, cResultPre, TextUtils.TruncateAt.END)
        btnGrid  = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        modeBar  = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        panel  = buildPanel()
        bubble = buildBubble()

        addView(panel,  LayoutParams(dp(PANEL_W), WRAP_CONTENT).apply {
            gravity = Gravity.END
        })
        addView(bubble, LayoutParams(dp(BUBBLE_SZ), dp(BUBBLE_SZ)).apply {
            gravity = Gravity.END
        })

        post { placeBubbleFromFraction(savedYFraction) }
        attachBubbleTouchListener()
        buildButtons()
        rebuildModeBar()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View construction
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildBubble(): FrameLayout {
        val bub = FrameLayout(context).apply {
            background = oval(cBg)
            elevation  = dp(8).toFloat()
        }
        // Icon from assets/calc.png
        val icon = ImageView(context).apply {
            try {
                val bmp = BitmapFactory.decodeStream(context.assets.open("calc.png"))
                setImageBitmap(bmp)
            } catch (_: Exception) {
                // fallback: leave image empty
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        bub.addView(icon, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return bub
    }

    private fun buildPanel(): LinearLayout {
        val pnl = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background  = roundRect(cPanel, dp(18))
            elevation   = dp(10).toFloat()
            setPadding(dp(8), dp(8), dp(8), dp(10))
            visibility  = View.GONE
        }

        // ── Title row ─────────────────────────────────────────────────────────
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text      = "Calculator"
            textSize  = 13f
            setTextColor(0xFFCCCCFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val btnX = makeSmallBtn("✕", cBack, cBackPrs) { togglePanel() }
        titleRow.addView(title)
        titleRow.addView(btnX, LinearLayout.LayoutParams(dp(32), dp(28)))
        pnl.addView(titleRow, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(6)))

        // ── Display ───────────────────────────────────────────────────────────
        val display = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background  = roundRect(cDisplay, dp(10))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        tvExpr.text = "0"
        display.addView(tvExpr,   lp(MATCH_PARENT, WRAP_CONTENT))
        display.addView(tvResult, lp(MATCH_PARENT, WRAP_CONTENT))
        pnl.addView(display, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(8)))

        // ── Mode bar ───────────────────────────────────────────────────────────
        pnl.addView(modeBar, lp(MATCH_PARENT, dp(32), bm = dp(6)))

        // ── Button grid (scrollable) ──────────────────────────────────────────
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(btnGrid, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        pnl.addView(scroll, lp(MATCH_PARENT, WRAP_CONTENT))

        return pnl
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mode bar
    // ─────────────────────────────────────────────────────────────────────────

    private fun rebuildModeBar() {
        modeBar.removeAllViews()
        for (m in CalcMode.entries) {
            val isOn = m == currentMode
            val btn = Button(context).apply {
                text       = m.label
                textSize   = 10f
                isAllCaps  = false
                setTextColor(if (isOn) cText else 0xFFAAAAAA.toInt())
                background = roundRect(if (isOn) cOp else cNum, dp(8))
                setPadding(0, 0, 0, 0)
                setOnClickListener { switchMode(m) }
            }
            val lp = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = dp(4) }
            modeBar.addView(btn, lp)
        }
        // Angle mode toggle (Sci + Adv only)
        if (currentMode != CalcMode.BASIC) {
            val label = if (engine.angleMode == CalculatorEngine.AngleMode.DEG) "DEG" else "RAD"
            angleModeBtn = Button(context).apply {
                text       = label
                textSize   = 9f
                isAllCaps  = false
                setTextColor(0xFFFFD600.toInt())
                background = roundRect(cNum, dp(8))
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener {
                    engine.angleMode =
                        if (engine.angleMode == CalculatorEngine.AngleMode.DEG)
                            CalculatorEngine.AngleMode.RAD
                        else CalculatorEngine.AngleMode.DEG
                    text = if (engine.angleMode == CalculatorEngine.AngleMode.DEG) "DEG" else "RAD"
                    refreshDisplay()
                }
            }
            modeBar.addView(angleModeBtn!!, LinearLayout.LayoutParams(dp(40), MATCH_PARENT))
        } else {
            angleModeBtn = null
        }
    }

    private fun switchMode(m: CalcMode) {
        currentMode = m
        buildButtons()
        rebuildModeBar()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Button grid
    // ─────────────────────────────────────────────────────────────────────────

    /** Describes a single calculator button. */
    private data class Btn(
        val label: String,   // display label
        val action: String,  // text to append, or "CLEAR" / "BACK" / "EVAL"
        val bg: Int,         // normal colour
        val pressed: Int     // pressed-state colour
    )

    private fun buildButtons() {
        btnGrid.removeAllViews()
        val btnH = when (currentMode) {
            CalcMode.BASIC       -> dp(46)
            CalcMode.SCIENTIFIC  -> dp(42)
            CalcMode.ADVANCED    -> dp(38)
        }
        for (row in rows()) {
            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(3))
            }
            for (b in row) {
                rowView.addView(
                    makeCalcBtn(b),
                    LinearLayout.LayoutParams(0, btnH, 1f).apply { marginEnd = dp(3) }
                )
            }
            btnGrid.addView(rowView, lp(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    /** Returns button rows for the current mode. */
    private fun rows(): List<List<Btn>> {
        // Colour shortcuts
        val N = cNum;  val NP = cNumPrs
        val O = cOp;   val OP = cOpPrs
        val F = cFn;   val FP = cFnPrs
        val E = cEq;   val EP = cEqPrs
        val C = cClear; val CP = cClearPrs
        val B = cBack;  val BP = cBackPrs

        // Shared number pad (bottom 4 rows, same in all modes)
        val numPad = listOf(
            listOf(Btn("7","7",N,NP),  Btn("8","8",N,NP),  Btn("9","9",N,NP),  Btn("÷","÷",O,OP)),
            listOf(Btn("4","4",N,NP),  Btn("5","5",N,NP),  Btn("6","6",N,NP),  Btn("×","×",O,OP)),
            listOf(Btn("1","1",N,NP),  Btn("2","2",N,NP),  Btn("3","3",N,NP),  Btn("-","-",O,OP)),
            listOf(Btn("0","0",N,NP),  Btn(".",".",N,NP),  Btn("+","+",O,OP),  Btn("=","EVAL",E,EP))
        )

        return when (currentMode) {
            // ── BASIC ─────────────────────────────────────────────────────────
            CalcMode.BASIC -> listOf(
                listOf(Btn("C","CLEAR",C,CP), Btn("(","(",F,FP), Btn(")",")",F,FP), Btn("⌫","BACK",B,BP))
            ) + numPad

            // ── SCIENTIFIC ────────────────────────────────────────────────────
            CalcMode.SCIENTIFIC -> listOf(
                listOf(Btn("sin","sin(",F,FP),   Btn("cos","cos(",F,FP),  Btn("tan","tan(",F,FP),  Btn("^","^",O,OP)),
                listOf(Btn("log","log(",F,FP),   Btn("ln","ln(",F,FP),    Btn("√","sqrt(",F,FP),   Btn("π","pi",F,FP)),
                listOf(Btn("e","e",F,FP),        Btn("x²","^2",O,OP),     Btn("(","(",F,FP),       Btn(")",")",F,FP)),
                listOf(Btn("C","CLEAR",C,CP),    Btn("Ans","ans",F,FP),   Btn("⌫","BACK",B,BP),    Btn("÷","÷",O,OP))
            ) + numPad

            // ── ADVANCED ──────────────────────────────────────────────────────
            CalcMode.ADVANCED -> listOf(
                listOf(Btn("sin⁻¹","asin(",F,FP), Btn("cos⁻¹","acos(",F,FP), Btn("tan⁻¹","atan(",F,FP), Btn("n!","!",O,OP)),
                listOf(Btn("sin","sin(",F,FP),    Btn("cos","cos(",F,FP),    Btn("tan","tan(",F,FP),     Btn("^","^",O,OP)),
                listOf(Btn("log","log(",F,FP),    Btn("ln","ln(",F,FP),      Btn("√","sqrt(",F,FP),      Btn("π","pi",F,FP)),
                listOf(Btn("nPr","npr(",F,FP),    Btn("nCr","ncr(",F,FP),    Btn("e","e",F,FP),          Btn("x²","^2",O,OP)),
                listOf(Btn("x³","^3",O,OP),       Btn(",",",",F,FP),         Btn("|x|","abs(",F,FP),     Btn("%","%",O,OP)),
                listOf(Btn("C","CLEAR",C,CP),     Btn("Ans","ans",F,FP),     Btn("⌫","BACK",B,BP),       Btn("÷","÷",O,OP))
            ) + numPad
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Button factory
    // ─────────────────────────────────────────────────────────────────────────

    private fun makeCalcBtn(spec: Btn): Button = Button(context).apply {
        text      = spec.label
        textSize  = if (spec.label.length > 3) 10f else 13f
        isAllCaps = false
        setTextColor(cText)
        background = ripple(spec.bg, spec.pressed)
        setPadding(0, 0, 0, 0)
        setOnClickListener { onButton(spec) }
    }

    private fun makeSmallBtn(label: String, bg: Int, bgPressed: Int, onClick: () -> Unit) =
        Button(context).apply {
            text       = label
            textSize   = 12f
            isAllCaps  = false
            setTextColor(cText)
            background = ripple(bg, bgPressed)
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Button logic
    // ─────────────────────────────────────────────────────────────────────────

    private fun onButton(spec: Btn) {
        when (spec.action) {
            "CLEAR" -> {
                expression.clear()
                refreshDisplay()
            }
            "BACK"  -> {
                if (expression.isNotEmpty()) expression.deleteCharAt(expression.length - 1)
                refreshDisplay()
            }
            "EVAL"  -> {
                val expr = expression.toString()
                if (expr.isNotEmpty()) {
                    val result = engine.evaluate(expr)
                    expression.clear()
                    expression.append(result)
                    tvExpr.text   = result
                    tvResult.text = ""
                }
            }
            else    -> {
                expression.append(spec.action)
                refreshDisplay()
            }
        }
    }

    private fun refreshDisplay() {
        val expr = expression.toString()
        tvExpr.text = if (expr.isEmpty()) "0" else expr
        // Live preview — silently ignore incomplete expressions
        if (expr.isNotEmpty()) {
            val preview = engine.evaluate(expr)
            tvResult.text = if (preview == "Error" || preview.isEmpty()) "" else "= $preview"
        } else {
            tvResult.text = ""
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bubble drag
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun attachBubbleTouchListener() {
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
                    if (isDragging) {
                        moveBubbleTo((viewStartTop + dy).toInt())
                        updatePanelPosition()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) togglePanel()
                    else {
                        // Save fraction for next activity
                        if (height > 0) savedYFraction = bubbleTopPx() / height.toFloat()
                    }
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
            ((height - dp(BUBBLE_SZ)) / 2).toFloat()
        } else {
            lp.topMargin.toFloat()
        }
    }

    private fun moveBubbleTo(topPx: Int) {
        val max = height - dp(BUBBLE_SZ)
        val clamped = max(0, min(topPx, max))
        val lp = bubble.layoutParams as LayoutParams
        lp.gravity    = Gravity.END
        lp.topMargin  = clamped
        lp.bottomMargin = 0
        bubble.layoutParams = lp
    }

    private fun placeBubbleFromFraction(frac: Float) {
        if (height == 0) return
        moveBubbleTo(((height - dp(BUBBLE_SZ)) * frac).toInt())
    }

    private fun updatePanelPosition() {
        if (!panelVisible) return
        val bTop    = bubbleTopPx().toInt()
        val pHeight = panel.measuredHeight.let { if (it > 0) it else dp(380) }
        var top     = bTop + dp(BUBBLE_SZ) / 2 - pHeight / 2
        top = max(dp(8), top)
        top = min(height - pHeight - dp(8), top)
        val lp = panel.layoutParams as LayoutParams
        lp.gravity      = Gravity.END
        lp.topMargin    = top
        lp.bottomMargin = 0
        lp.marginEnd    = dp(BUBBLE_SZ) + dp(10)
        panel.layoutParams = lp
    }

    private fun togglePanel() {
        panelVisible = !panelVisible
        if (panelVisible) {
            panel.visibility = View.VISIBLE
            post { updatePanelPosition() }
        } else {
            panel.visibility = View.GONE
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drawable helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape    = GradientDrawable.OVAL
            setColor(color)
        }

    private fun roundRect(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape         = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius  = radius.toFloat()
        }

    private fun ripple(normal: Int, pressed: Int): RippleDrawable =
        RippleDrawable(
            android.content.res.ColorStateList.valueOf(pressed),
            roundRect(normal, dp(8)),
            null
        )

    // ─────────────────────────────────────────────────────────────────────────
    // Tiny helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()

    private fun newTv(size: Float, color: Int, ellipsize: TextUtils.TruncateAt): TextView =
        TextView(context).apply {
            textSize      = size
            setTextColor(color)
            maxLines      = 1
            this.ellipsize = ellipsize
        }

    private fun lp(
        w: Int, h: Int,
        bm: Int = 0, em: Int = 0
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h).apply {
        bottomMargin = bm
        marginEnd    = em
    }

    companion object {
        private var savedYFraction = 0.45f
        private const val BUBBLE_SZ = 56    // dp
        private const val PANEL_W   = 285   // dp
    }
}
