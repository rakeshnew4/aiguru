package com.aiguruapp.student

import android.app.Activity
import android.graphics.RectF
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.edit

/**
 * Step-by-step interactive feature tour for the Home screen.
 *
 * Usage:
 *   HomeTourManager(this).startTour()
 *
 * Auto-shows once on first launch; replays whenever startTour() is called.
 */
class HomeTourManager(private val activity: Activity) {

    private data class TourStep(val viewId: Int, val title: String, val desc: String)

    private val steps = listOf(
        TourStep(
            R.id.quickActionBbBtn,
            "🎓 Blackboard Mode",
            "Your AI visual tutor! Pick any topic and watch a live animated lesson unfold — just like a real teacher on a blackboard."
        ),
        TourStep(
            R.id.quickActionChatBtn,
            "💬 Ask AI – Chat",
            "Quick doubts? Chat with your AI tutor instantly. Great for one-line questions, explanations and practice."
        ),
        TourStep(
            R.id.quickActionTasksBtn,
            "📚 Saved Sessions",
            "All your past Blackboard lessons are saved here. Revisit, replay, or continue any lesson at any time."
        ),
        TourStep(
            R.id.dailyChallengeCard,
            "⚡ Today's Challenge",
            "A fresh brain-teaser every day! Tap it to explore the question as a live Blackboard lesson and earn bonus points."
        ),
        TourStep(
            R.id.subjectsRecyclerView,
            "📖 My Subjects",
            "Your imported or added subjects and chapters live here. Tap any subject card to start chatting or launch a Blackboard lesson."
        ),
        TourStep(
            R.id.addSubjectButton,
            "➕ Add Subject",
            "Not seeing your subject? Tap here to add more subjects and chapters to customise your learning list."
        ),
        TourStep(
            R.id.drawerToggleBtn,
            "☰ Menu",
            "Tap here to access your profile, plan details, credit balance, progress stats and app settings."
        ),
        TourStep(
            R.id.helpGuideBtn,
            "? Help",
            "Tap here anytime to replay this tour or browse the full feature guide. You're all set! 🎉"
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
            // Tapping the dark area advances to next step
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

        // Compute spotlight rectangle
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

        // Fill tooltip content
        tooltip.findViewById<TextView>(R.id.tourStepCounter).text =
            "Step ${currentStep + 1} of ${visibleStepCount()}"
        tooltip.findViewById<TextView>(R.id.tourTitleText).text = step.title
        tooltip.findViewById<TextView>(R.id.tourDescText).text  = step.desc

        val nextBtn = tooltip.findViewById<TextView>(R.id.tourNextBtn)
        val isLast  = findNextVisible(currentStep) >= steps.size
        nextBtn.text = if (isLast) "Finish ✓" else "Next →"
        nextBtn.setOnClickListener { if (isLast) dismiss() else advance() }

        tooltip.findViewById<TextView>(R.id.tourSkipBtn).setOnClickListener { dismiss() }

        // Animate tooltip in, then position it
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
            // Spotlight is in the top half → show tooltip below
            (spotRect.bottom + margin).toInt()
                .coerceAtMost((screenH - tooltipH - margin).toInt())
        } else {
            // Spotlight is in the bottom half → show tooltip above
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

    /** Returns the index of the next visible step after [from], or steps.size if none. */
    private fun findNextVisible(from: Int): Int {
        var i = from + 1
        while (i < steps.size) {
            val v = activity.findViewById<View?>(steps[i].viewId)
            if (v != null && v.isShown) return i
            i++
        }
        return steps.size
    }

    /** Count how many steps will actually be shown (target views visible). */
    private fun visibleStepCount(): Int {
        return steps.count { step ->
            activity.findViewById<View?>(step.viewId)?.isShown == true
        }
    }

    private fun dp(v: Int): Float = v * activity.resources.displayMetrics.density

    private fun markDone() {
        activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_HOME, true) }
    }

    companion object {
        private const val PREFS    = "app_tour"
        private const val KEY_HOME = "home_tour_v1_done"

        fun shouldShowHome(activity: Activity): Boolean =
            !activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_HOME, false)
    }
}
