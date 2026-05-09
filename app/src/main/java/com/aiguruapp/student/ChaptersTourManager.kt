package com.aiguruapp.student

import android.app.Activity
import android.graphics.RectF
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.edit

/**
 * Step-by-step interactive feature tour for the Chapters (Subject) screen.
 *
 * Usage:
 *   ChaptersTourManager(this).startTour()
 *
 * Auto-shows once on first launch of any subject; replays whenever startTour() is called.
 */
class ChaptersTourManager(private val activity: Activity) {

    private data class TourStep(val viewId: Int, val title: String, val desc: String)

    private val steps = listOf(
        TourStep(
            R.id.subjectTitle,
            "📖 Your Subject",
            "This is your subject page. All the chapters you've added are listed below. Tap any chapter to start an AI lesson or chat session."
        ),
        TourStep(
            R.id.chaptersRecyclerView,
            "📚 Chapter List",
            "Each chapter card has two buttons — tap 💬 to open AI chat for that chapter, or tap 🎓 to launch a live Blackboard animated lesson."
        ),
        TourStep(
            R.id.addChapterButton,
            "➕ Add Chapter",
            "Tap here to add a new chapter by name, import from NCERT, or attach a PDF. The more chapters you add, the richer your study plan."
        )
    )

    private var currentStep = 0
    private var overlayContainer: ViewGroup? = null
    private var onFinished: (() -> Unit)? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun startTour(onFinished: (() -> Unit)? = null) {
        this.onFinished = onFinished
        currentStep = findNextVisible(-1)
        if (currentStep >= steps.size) return
        buildOverlay()
        showCurrentStep()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildOverlay() {
        val root = activity.window.decorView as ViewGroup

        val container = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val spotlight = SpotlightView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { advance() }
        }

        val tooltip = LayoutInflater.from(activity)
            .inflate(R.layout.layout_tour_tooltip, container, false)

        container.addView(spotlight)
        container.addView(tooltip)
        root.addView(container)
        overlayContainer = container
    }

    private fun showCurrentStep() {
        val container = overlayContainer ?: return
        val spotlight = container.getChildAt(0) as? SpotlightView ?: return
        val tooltip   = container.getChildAt(1) ?: return
        val step      = steps[currentStep]

        val target = activity.findViewById<View?>(step.viewId)
        if (target == null || !target.isShown) { advance(); return }

        val loc = IntArray(2)
        target.getLocationInWindow(loc)
        val pad = dp(14)
        val spotRect = RectF(
            loc[0] - pad,
            loc[1] - pad,
            loc[0] + target.width  + pad,
            loc[1] + target.height + pad
        )
        spotlight.setSpotlight(spotRect)

        tooltip.findViewById<TextView>(R.id.tourStepCounter).text =
            "Step ${currentStep + 1} of ${visibleStepCount()}"
        tooltip.findViewById<TextView>(R.id.tourTitleText).text = step.title
        tooltip.findViewById<TextView>(R.id.tourDescText).text  = step.desc

        val nextBtn = tooltip.findViewById<TextView>(R.id.tourNextBtn)
        val isLast  = findNextVisible(currentStep) >= steps.size
        nextBtn.text = if (isLast) "Finish ✓" else "Next →"
        nextBtn.setOnClickListener { if (isLast) dismiss() else advance() }

        tooltip.findViewById<TextView>(R.id.tourSkipBtn).setOnClickListener { dismiss() }

        tooltip.alpha = 0f
        tooltip.animate().alpha(1f).setDuration(240).start()

        container.post {
            positionTooltip(tooltip, spotRect, container.height.toFloat())
        }
    }

    private fun positionTooltip(tooltip: View, spotRect: RectF, screenH: Float) {
        val tooltipH = tooltip.height.toFloat()
        val margin   = dp(16)
        val lp       = tooltip.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin  = dp(18).toInt()
        lp.rightMargin = dp(18).toInt()
        lp.gravity     = Gravity.NO_GRAVITY

        lp.topMargin = if (spotRect.centerY() < screenH * 0.55f) {
            (spotRect.bottom + margin).toInt()
                .coerceAtMost((screenH - tooltipH - margin).toInt())
        } else {
            (spotRect.top - tooltipH - margin).toInt()
                .coerceAtLeast(dp(8).toInt())
        }
        tooltip.layoutParams = lp
    }

    private fun advance() {
        currentStep = findNextVisible(currentStep)
        if (currentStep >= steps.size) { dismiss(); return }
        showCurrentStep()
    }

    private fun dismiss() {
        markDone()
        val root = activity.window.decorView as ViewGroup
        val callback = onFinished
        overlayContainer?.animate()
            ?.alpha(0f)
            ?.setDuration(280)
            ?.withEndAction {
                root.removeView(overlayContainer)
                overlayContainer = null
                callback?.invoke()
            }?.start()
    }

    private fun findNextVisible(from: Int): Int {
        var i = from + 1
        while (i < steps.size) {
            val v = activity.findViewById<View?>(steps[i].viewId)
            if (v != null && v.isShown) return i
            i++
        }
        return steps.size
    }

    private fun visibleStepCount(): Int =
        steps.count { activity.findViewById<View?>(it.viewId)?.isShown == true }

    private fun dp(v: Int): Float = v * activity.resources.displayMetrics.density

    private fun markDone() {
        activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_CHAPTERS, true) }
    }

    companion object {
        private const val PREFS         = "app_tour"
        private const val KEY_CHAPTERS  = "chapters_tour_v1_done"

        fun shouldShow(activity: Activity): Boolean =
            !activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_CHAPTERS, false)
    }
}
