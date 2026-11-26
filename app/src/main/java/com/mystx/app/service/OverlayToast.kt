package com.mystx.app.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import com.mystx.app.ui.components.MystToastTokens

/**
 * Renders the app's custom in-overlay toast: a TYPE_ACCESSIBILITY_OVERLAY view with
 * slide/fade in and out animations, auto-dismissed after a delay. Extracted verbatim
 * from AssistantService so the accessibility service no longer owns transient-UI
 * concerns. All methods must be called on the main thread.
 */
class OverlayToast(private val context: Context, private val handler: Handler) {

    private var currentOverlayToast: View? = null
    private var animatingOutToast: View? = null
    private var dismissRunnable: Runnable? = null
    private var dismissAnimator: AnimatorSet? = null
    private var enterAnimator: AnimatorSet? = null

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

    /** Show [msg], replacing any currently-visible overlay toast. */
    fun show(msg: String) {
        dismiss()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val textView = TextView(context).apply {
            text = msg
            setTextColor(Color.WHITE)
            textSize = MystToastTokens.TEXT_SIZE_SP.toFloat()
            setPadding(
                dp(MystToastTokens.HORIZONTAL_PADDING_DP), dp(MystToastTokens.VERTICAL_PADDING_DP),
                dp(MystToastTokens.HORIZONTAL_PADDING_DP), dp(MystToastTokens.VERTICAL_PADDING_DP)
            )
            maxWidth =
                (context.resources.displayMetrics.widthPixels * MystToastTokens.MAX_WIDTH_FRACTION).toInt()
            background = GradientDrawable().apply {
                setColor(TOAST_BACKGROUND_COLOR)
                cornerRadius = dp(MystToastTokens.CORNER_RADIUS_DP).toFloat()
            }
            gravity = Gravity.CENTER
            alpha = 0f
            translationY = dp(TOAST_SLIDE_DISTANCE_DP).toFloat()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(TOAST_BOTTOM_MARGIN_DP)
            windowAnimations = 0
        }

        try {
            wm.addView(textView, params)
            currentOverlayToast = textView

            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(textView, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(textView, View.TRANSLATION_Y, dp(TOAST_SLIDE_DISTANCE_DP).toFloat(), 0f)
                )
                duration = TOAST_ANIM_DURATION_MS
                interpolator = DecelerateInterpolator()
                start()
                enterAnimator = this
            }

            val runnable = Runnable { dismissAnimated() }
            dismissRunnable = runnable
            handler.postDelayed(runnable, TOAST_DURATION_MS)
        } catch (_: Exception) {
            try { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    /** Immediately remove any overlay toast (including one currently animating out). */
    fun dismiss() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        enterAnimator?.cancel()
        enterAnimator = null
        dismissAnimator?.cancel()
        dismissAnimator = null
        currentOverlayToast?.let { view ->
            currentOverlayToast = null
            removeViewSafely(view)
        }
        animatingOutToast?.let { view ->
            animatingOutToast = null
            removeViewSafely(view)
        }
    }

    private fun dismissAnimated() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        enterAnimator?.cancel()
        enterAnimator = null
        dismissAnimator?.cancel()
        dismissAnimator = null
        animatingOutToast?.let { stale ->
            animatingOutToast = null
            removeViewSafely(stale)
        }
        currentOverlayToast?.let { view ->
            currentOverlayToast = null
            animatingOutToast = view
            try {
                dismissAnimator = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha, 0f),
                        ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, view.translationY, dp(TOAST_SLIDE_DISTANCE_DP).toFloat())
                    )
                    duration = TOAST_ANIM_DURATION_MS
                    interpolator = DecelerateInterpolator()
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            removeViewSafely(view)
                            if (animatingOutToast === view) {
                                animatingOutToast = null
                            }
                            if (dismissAnimator === animation) {
                                dismissAnimator = null
                            }
                        }
                    })
                    start()
                }
            } catch (_: Exception) {
                removeViewSafely(view)
                if (animatingOutToast === view) {
                    animatingOutToast = null
                }
            }
        }
    }

    companion object {
        // Single source of truth shared with the Compose renderer, so the service's toast and
        // the in-app one cannot drift apart. See MystToastTokens.
        private const val TOAST_BACKGROUND_COLOR = MystToastTokens.BACKGROUND_ARGB
        private const val TOAST_DURATION_MS = MystToastTokens.DURATION_MS
        private const val TOAST_BOTTOM_MARGIN_DP = MystToastTokens.BOTTOM_MARGIN_DP
        private const val TOAST_ANIM_DURATION_MS = MystToastTokens.ANIM_DURATION_MS.toLong()
        private const val TOAST_SLIDE_DISTANCE_DP = MystToastTokens.SLIDE_DISTANCE_DP
    }
}
