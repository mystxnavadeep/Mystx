package com.mystx.app.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.mystx.app.model.Command

/**
 * Floating glass command autocomplete bar rendered as a TYPE_ACCESSIBILITY_OVERLAY.
 * Anchors cleanly above the active text input box (never overlapping the keyboard),
 * clips scrolling pills strictly inside its rounded glass capsule, and animates
 * smoothly on show, in-place filter updates, pill press, and dismiss.
 * All methods must be called on the main thread.
 */
class CommandAutocompleteOverlay(
    private val context: Context,
    private val handler: Handler
) {

    private var currentContainer: FrameLayout? = null
    private var currentPillRow: LinearLayout? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var animatingOutView: View? = null
    private var enterAnimator: AnimatorSet? = null
    private var exitAnimator: AnimatorSet? = null
    private var autoHideRunnable: Runnable? = null

    private fun dp(value: Int): Int {
        val density = context.resources.displayMetrics.density
        return (value * density + 0.5f).toInt()
    }

    private fun removeViewSafely(view: View) {
        try {
            view.visibility = View.GONE
            if (view.isAttachedToWindow) {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
                wm.removeViewImmediate(view)
            }
        } catch (_: Exception) {}
    }

    private fun populatePills(
        pillRow: LinearLayout,
        commands: List<Command>,
        onSelect: (Command) -> Unit
    ) {
        pillRow.removeAllViews()
        commands.forEachIndexed { index, command ->
            val pill = TextView(context).apply {
                text = command.trigger
                setTextColor(Color.WHITE)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                setPadding(dp(13), dp(7), dp(13), dp(7))
                background = GradientDrawable().apply {
                    setColor(PILL_BG_COLOR)
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), PILL_STROKE_COLOR)
                }
                isClickable = true
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80L).start()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
                        }
                    }
                    false
                }
                setOnClickListener {
                    dismissAnimated()
                    onSelect(command)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) {
                    marginStart = dp(6)
                }
            }
            pillRow.addView(pill, lp)
        }
    }

    private fun computeLayoutParams(anchorTopPx: Int?): WindowManager.LayoutParams {
        val displayMetrics = context.resources.displayMetrics
        val maxBarWidth = (displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(200))
        val estimatedBarHeight = dp(52)

        return WindowManager.LayoutParams(
            maxBarWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (anchorTopPx != null && anchorTopPx > dp(80)) {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (anchorTopPx - estimatedBarHeight - dp(10)).coerceAtLeast(dp(48))
            } else {
                // Fallback safely above standard OEM keyboards if node bounds are unavailable.
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(330)
            }
            windowAnimations = 0
        }
    }

    /**
     * Show up to 6 matching command triggers as tappable rounded glass pills inside
     * a cleanly clipped floating capsule. Updates in-place without flickering if already shown.
     */
    fun show(
        commands: List<Command>,
        anchorTopPx: Int? = null,
        onSelect: (Command) -> Unit
    ) {
        val limited = commands.take(MAX_SUGGESTIONS)
        if (limited.isEmpty()) {
            dismissAnimated()
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        // Reset auto-hide timer on every update
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        val hideTask = Runnable { dismissAnimated() }
        autoHideRunnable = hideTask
        handler.postDelayed(hideTask, AUTO_DISMISS_MS)

        // If already visible and attached, update pills & position smoothly in-place (zero flicker)
        val existingContainer = currentContainer
        val existingRow = currentPillRow
        if (existingContainer != null && existingRow != null && existingContainer.isAttachedToWindow) {
            populatePills(existingRow, limited, onSelect)
            val newParams = computeLayoutParams(anchorTopPx)
            val oldParams = currentParams
            if (oldParams == null || oldParams.gravity != newParams.gravity || kotlin.math.abs(oldParams.y - newParams.y) > dp(6)) {
                currentParams = newParams
                try {
                    wm.updateViewLayout(existingContainer, newParams)
                } catch (_: Exception) {}
            }
            return
        }

        // Clean up any stale view before creating a fresh animated capsule
        dismiss()

        val pillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        populatePills(pillRow, limited, onSelect)

        val scrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            clipToPadding = true
            addView(
                pillRow,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // Outer glass capsule strictly clips all child pills to its rounded outline
        val capsule = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(CONTAINER_BG_COLOR)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), CONTAINER_STROKE_COLOR)
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            clipChildren = true
            elevation = dp(8).toFloat()
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
            translationY = dp(12).toFloat()
            addView(
                scrollView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val params = computeLayoutParams(anchorTopPx)
        try {
            wm.addView(capsule, params)
            currentContainer = capsule
            currentPillRow = pillRow
            currentParams = params

            enterAnimator = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(capsule, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(capsule, View.TRANSLATION_Y, dp(12).toFloat(), 0f),
                    ObjectAnimator.ofFloat(capsule, View.SCALE_X, 0.94f, 1f),
                    ObjectAnimator.ofFloat(capsule, View.SCALE_Y, 0.94f, 1f)
                )
                duration = ENTER_ANIM_MS
                interpolator = OvershootInterpolator(0.75f)
                start()
            }

            // Re-schedule auto-hide after dismiss() cleared it above
            autoHideRunnable = hideTask
            handler.postDelayed(hideTask, AUTO_DISMISS_MS)
        } catch (_: Exception) {
            currentContainer = null
            currentPillRow = null
            currentParams = null
        }
    }

    /** Smoothly fade and slide down the overlay before removing it from WindowManager. */
    fun dismissAnimated() {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        autoHideRunnable = null
        enterAnimator?.cancel()
        enterAnimator = null
        exitAnimator?.cancel()
        exitAnimator = null
        animatingOutView?.let { stale ->
            animatingOutView = null
            removeViewSafely(stale)
        }

        val view = currentContainer ?: return
        currentContainer = null
        currentPillRow = null
        currentParams = null
        animatingOutView = view

        try {
            exitAnimator = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha, 0f),
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, view.translationY, dp(10).toFloat()),
                    ObjectAnimator.ofFloat(view, View.SCALE_X, view.scaleX, 0.96f),
                    ObjectAnimator.ofFloat(view, View.SCALE_Y, view.scaleY, 0.96f)
                )
                duration = EXIT_ANIM_MS
                interpolator = DecelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        removeViewSafely(view)
                        if (animatingOutView === view) animatingOutView = null
                        if (exitAnimator === animation) exitAnimator = null
                    }
                })
                start()
            }
        } catch (_: Exception) {
            removeViewSafely(view)
            if (animatingOutView === view) animatingOutView = null
        }
    }

    /** Immediately remove any overlay view (including one animating out) for service teardown. */
    fun dismiss() {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        autoHideRunnable = null
        enterAnimator?.cancel()
        enterAnimator = null
        exitAnimator?.cancel()
        exitAnimator = null
        currentContainer?.let { view ->
            currentContainer = null
            currentPillRow = null
            currentParams = null
            removeViewSafely(view)
        }
        animatingOutView?.let { view ->
            animatingOutView = null
            removeViewSafely(view)
        }
    }

    companion object {
        private const val MAX_SUGGESTIONS = 6
        private const val AUTO_DISMISS_MS = 8_000L
        private const val ENTER_ANIM_MS = 220L
        private const val EXIT_ANIM_MS = 160L
        private const val CONTAINER_BG_COLOR = 0xE614161E.toInt()
        private const val CONTAINER_STROKE_COLOR = 0x38FFFFFF
        private const val PILL_BG_COLOR = 0x26FFFFFF
        private const val PILL_STROKE_COLOR = 0x45FFFFFF
    }
}
