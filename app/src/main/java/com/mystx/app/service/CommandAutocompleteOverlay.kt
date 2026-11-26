package com.mystx.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.mystx.app.model.Command

/**
 * Lightweight floating horizontal command autocomplete bar rendered as a
 * TYPE_ACCESSIBILITY_OVERLAY above the keyboard. All methods must be called on the main thread.
 */
class CommandAutocompleteOverlay(
    private val context: Context,
    private val handler: Handler
) {

    private var currentView: View? = null
    private var autoHideRunnable: Runnable? = null

    private fun dp(value: Int): Int {
        val density = context.resources.displayMetrics.density
        return (value * density + 0.5f).toInt()
    }

    /**
     * Show up to 6 matching command triggers as tappable rounded glass pills.
     * Auto-dismisses after 8 seconds of inactivity.
     */
    fun show(commands: List<Command>, onSelect: (Command) -> Unit) {
        dismiss()
        val limited = commands.take(MAX_SUGGESTIONS)
        if (limited.isEmpty()) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val pillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        limited.forEachIndexed { index, command ->
            val pill = TextView(context).apply {
                text = command.trigger
                setTextColor(Color.WHITE)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = GradientDrawable().apply {
                    setColor(PILL_BG_COLOR)
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), PILL_STROKE_COLOR)
                }
                isClickable = true
                setOnClickListener {
                    dismiss()
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

        val scrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = GradientDrawable().apply {
                setColor(CONTAINER_BG_COLOR)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), CONTAINER_STROKE_COLOR)
            }
            elevation = dp(6).toFloat()
            addView(
                pillRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val maxBarWidth = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(96)
            windowAnimations = 0
        }

        scrollView.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            if (right - left > maxBarWidth) {
                params.width = maxBarWidth
                try {
                    if (v.isAttachedToWindow) {
                        wm.updateViewLayout(v, params)
                    }
                } catch (_: Exception) {}
            }
        }

        try {
            wm.addView(scrollView, params)
            currentView = scrollView

            val runnable = Runnable { dismiss() }
            autoHideRunnable = runnable
            handler.postDelayed(runnable, AUTO_DISMISS_MS)
        } catch (_: Exception) {
            currentView = null
        }
    }

    /** Safely remove the overlay view and cancel any pending auto-hide timer. */
    fun dismiss() {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        autoHideRunnable = null
        val view = currentView ?: return
        currentView = null
        try {
            view.visibility = View.GONE
            if (view.isAttachedToWindow) {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
                wm.removeViewImmediate(view)
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val MAX_SUGGESTIONS = 6
        private const val AUTO_DISMISS_MS = 8_000L
        private const val CONTAINER_BG_COLOR = 0xD91A1C24.toInt()
        private const val CONTAINER_STROKE_COLOR = 0x33FFFFFF
        private const val PILL_BG_COLOR = 0x26FFFFFF
        private const val PILL_STROKE_COLOR = 0x40FFFFFF
    }
}
