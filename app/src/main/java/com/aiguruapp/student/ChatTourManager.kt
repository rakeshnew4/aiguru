package com.aiguruapp.student

import android.app.Activity
import android.graphics.RectF
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.edit

/**
 * Step-by-step interactive feature tour for the Chat screen (FullChatFragment).
 *
 * Usage (from a Fragment):
 *   ChatTourManager(requireActivity()).startTour()
 *
 * Auto-shows once on first chat session; replays when triggered manually.
 */
class ChatTourManager(private val activity: Activity) {

    private data class TourStep(val viewId: Int, val title: String, val desc: String)

    private val steps = listOf(
        TourStep(
            R.id.tabLayout,
            "📑 Pages · Chat · BB Sessions",
            "Three tabs organise your learning:\n\n📄 Pages — view textbook pages, add your own images\n\n💬 Chat — your AI tutor answers here\n\n📌 BB Sessions — replay saved Blackboard lessons anytime\n\nSwipe left/right to switch tabs quickly."
        ),
        TourStep(
            R.id.messagesRecyclerView,
            "💬 Conversation Area",
            "This is where your AI tutor responds. Questions and answers appear here as you chat. Scroll up to review the full conversation."
        ),
        TourStep(
            R.id.messageInput,
            "⌨️ Type Your Question",
            "Type any question here — about a chapter topic, homework problem, or concept you're stuck on. The AI will explain it clearly."
        ),
        TourStep(
            R.id.voiceButton,
            "🎤 Voice Input",
            "Tap the mic to ask your question by voice — hands-free! Great for dictating long questions or when your keyboard is in the way."
        ),
        TourStep(
            R.id.sendButton,
            "➤ Send",
            "Tap Send (or press Enter) to submit your question. The AI starts answering immediately with streaming text."
        ),
        TourStep(
            R.id.plusButton,
            "＋ More Actions",
            "Tap ＋ to reveal extra tools: 📷 attach a photo of your textbook, 🖼️ describe an image, switch language, and more."
        ),
        TourStep(
            R.id.imageButton,
            "📷 Attach Image",
            "Take a photo or pick from gallery. The AI will read your textbook page, diagram, or handwritten notes and explain it."
        ),
        TourStep(
            R.id.liveModeChip,
            "🎙️ Live Voice Mode",
            "Enable continuous voice mode to have a hands-free back-and-forth conversation — ask questions aloud, hear answers spoken back."
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
            .edit { putBoolean(KEY_CHAT, true) }
    }

    companion object {
        private const val PREFS    = "app_tour"
        private const val KEY_CHAT = "chat_tour_v1_done"

        fun shouldShow(activity: Activity): Boolean =
            !activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_CHAT, false)
    }
}
