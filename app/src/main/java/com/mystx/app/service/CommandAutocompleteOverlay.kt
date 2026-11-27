package com.mystx.app.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
 * - Works smoothly on phones, tablets, and foldables (capped width + smooth horizontal scroll).
 * - Includes a left drag grip (⋮) so users can reposition the bar anywhere on screen,
 *   and a right close button (✕) + outside-touch auto-dismiss so it never gets stuck.
 * - Uses native RippleDrawable on pills instead of OnTouchListener so HorizontalScrollView
 *   never loses scroll gestures.
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
    private var userDraggedPosition: Pair<Int, Int>? = null

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
            val shape = GradientDrawable().apply {
                setColor(PILL_BG_COLOR)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), PILL_STROKE_COLOR)
            }
            val rippleBg = RippleDrawable(
                ColorStateList.valueOf(0x40FFFFFF),
                shape,
                null
            )
            val pill = TextView(context).apply {
                text = command.trigger
                setTextColor(Color.WHITE)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                setPadding(dp(13), dp(7), dp(13), dp(7))
                background = rippleBg
                isClickable = true
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
        val dm = context.resources.displayMetrics
        val maxBarWidth = minOf(dm.widthPixels - dp(24), dp(460)).coerceAtLeast(dp(220))
        val estimatedBarHeight = dp(52)

        return WindowManager.LayoutParams(
            maxBarWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            val savedPos = userDraggedPosition
            if (savedPos != null) {
                gravity = Gravity.TOP or Gravity.START
                x = savedPos.first.coerceIn(dp(8), (dm.widthPixels - maxBarWidth - dp(8)).coerceAtLeast(dp(8)))
                y = savedPos.second.coerceIn(dp(40), (dm.heightPixels - estimatedBarHeight - dp(40)).coerceAtLeast(dp(40)))
            } else if (anchorTopPx != null && anchorTopPx > dp(80)) {
                gravity = Gravity.TOP or Gravity.START
                x = ((dm.widthPixels - maxBarWidth) / 2).coerceAtLeast(dp(12))
                y = (anchorTopPx - estimatedBarHeight - dp(10)).coerceAtLeast(dp(48))
            } else {
                gravity = Gravity.TOP or Gravity.START
                x = ((dm.widthPixels - maxBarWidth) / 2).coerceAtLeast(dp(12))
                y = (dm.heightPixels - dp(360)).coerceAtLeast(dp(80))
            }
            windowAnimations = 0
        }
    }

    /**
     * Show up to 6 matching command triggers as tappable rounded glass pills inside
     * a draggable, smoothly scrollable floating capsule.
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

        autoHideRunnable?.let { handler.removeCallbacks(it) }
        val hideTask = Runnable { dismissAnimated() }
        autoHideRunnable = hideTask
        handler.postDelayed(hideTask, AUTO_DISMISS_MS)

        // Update in-place without recreating window if already visible
        val existingContainer = currentContainer
        val existingRow = currentPillRow
        if (existingContainer != null && existingRow != null && existingContainer.isAttachedToWindow) {
            populatePills(existingRow, limited, onSelect)
            if (userDraggedPosition == null) {
                val newParams = computeLayoutParams(anchorTopPx)
                val oldParams = currentParams
                if (oldParams == null || kotlin.math.abs(oldParams.y - newParams.y) > dp(8)) {
                    currentParams = newParams
                    try {
                        wm.updateViewLayout(existingContainer, newParams)
                    } catch (_: Exception) {}
                }
            }
            return
        }

        dismiss()

        val pillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        populatePills(pillRow, limited, onSelect)

        val scrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_ALWAYS
            isFillViewport = false
            isSmoothScrollingEnabled = true
            addView(
                pillRow,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val params = computeLayoutParams(anchorTopPx)

        // Left drag handle so users can freely move the bar on phones & tablets
        var dragStartX = 0
        var dragStartY = 0
        var touchStartRawX = 0f
        var touchStartRawY = 0f

        val dragHandle = TextView(context).apply {
            text = "⋮"
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(6), dp(6))
            contentDescription = "Drag command bar"
        }

        // Right close button so the bar can always be dismissed cleanly
        val closeButton = TextView(context).apply {
            text = "✕"
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(11), dp(6))
            isClickable = true
            setOnClickListener { dismissAnimated() }
            contentDescription = "Close command bar"
        }

        val barRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                dragHandle,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                scrollView,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                closeButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val capsule = object : FrameLayout(context) {
            override fun onTouchEvent(event: MotionEvent?): Boolean {
                if (event?.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    dismissAnimated()
                    return true
                }
                return super.onTouchEvent(event)
            }
        }.apply {
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
                barRow,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        dragHandle.setOnTouchListener { _, event ->
            val p = currentParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = p.x
                    dragStartY = p.y
                    touchStartRawX = event.rawX
                    touchStartRawY = event.rawY
                    // Reset auto-hide while dragging
                    autoHideRunnable?.let { handler.removeCallbacks(it) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dm = context.resources.displayMetrics
                    val newX = (dragStartX + (event.rawX - touchStartRawX).toInt())
                        .coerceIn(dp(6), (dm.widthPixels - p.width - dp(6)).coerceAtLeast(dp(6)))
                    val newY = (dragStartY + (event.rawY - touchStartRawY).toInt())
                        .coerceIn(dp(36), (dm.heightPixels - dp(72)).coerceAtLeast(dp(36)))
                    p.x = newX
                    p.y = newY
                    userDraggedPosition = newX to newY
                    try {
                        if (capsule.isAttachedToWindow) {
                            wm.updateViewLayout(capsule, p)
                        }
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    autoHideRunnable = hideTask
                    handler.postDelayed(hideTask, AUTO_DISMISS_MS)
                    true
                }
                else -> false
            }
        }

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
