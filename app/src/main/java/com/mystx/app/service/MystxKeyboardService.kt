package com.mystx.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.mystx.app.MystxApp
import com.mystx.app.api.GeminiClient
import com.mystx.app.api.OpenAICompatibleClient
import com.mystx.app.manager.CommandManager
import com.mystx.app.manager.CommandStudioStore
import com.mystx.app.manager.KeyManager
import com.mystx.app.model.HistoryManager
import com.mystx.app.model.KeyboardStyle
import com.mystx.app.model.PrefKeys
import com.mystx.app.model.RichCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mystx AI Keyboard (InputMethodService) with 6 familiar OEM keyboard skins:
 * Pixel Dark, Pixel Light, Samsung One UI, HyperOS/Poco, Vivo Origin, and Realme/OnePlus.
 *
 * - Zero Accessibility permission required (0 BHIM / Paytm / banking alerts).
 * - Haptic tap feedback, hold-to-delete Backspace, Caps Lock, double-space period,
 *   number row on One UI / HyperOS, and 1-tap style cycling right on the keyboard bar.
 * - Top Command Studio AI bar (/heng, /fix, /improve, custom) + Auto-Back to user's own keyboard.
 */
class MystxKeyboardService : InputMethodService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeJob: Job? = null

    private lateinit var keyManager: KeyManager
    private lateinit var commandManager: CommandManager
    private lateinit var studioStore: CommandStudioStore
    private val geminiClient = GeminiClient()
    private val openAIClient = OpenAICompatibleClient()

    private var rootLayout: LinearLayout? = null
    private var statusView: TextView? = null
    private var styleBtn: TextView? = null
    private var chipsContainer: LinearLayout? = null
    private var keysContainer: LinearLayout? = null

    private var style: KeyboardStyle = KeyboardStyle.PIXEL_DARK
    private var isShift = false
    private var isCapsLock = false
    private var isSymbols = false
    private var lastShiftTapMs = 0L
    private var lastSpaceTapMs = 0L
    private var autoReturnToUserKeyboard = false

    private val repeatDeleteRunnable = object : Runnable {
        override fun run() {
            currentInputConnection?.deleteSurroundingText(1, 0)
            mainHandler.postDelayed(this, 65L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        keyManager = (applicationContext as? MystxApp)?.keyManager ?: KeyManager(applicationContext)
        commandManager = CommandManager(applicationContext)
        studioStore = CommandStudioStore(applicationContext, commandManager)
        reloadPrefs()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(repeatDeleteRunnable)
        activeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun reloadPrefs() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        style = KeyboardStyle.fromKey(prefs.getString(PrefKeys.KEYBOARD_STYLE, PrefKeys.DEFAULT_KEYBOARD_STYLE))
        autoReturnToUserKeyboard = prefs.getBoolean("ime_auto_return", false)
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(10))
        }
        rootLayout = root

        // Row 1: Mystx Status + Skin Switcher + Switch Keyboard
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(5))
        }

        val titleStatus = TextView(this).apply {
            text = "Mystx · ${style.label}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusView = titleStatus
        header.addView(titleStatus)

        val themeCycleBtn = TextView(this).apply {
            text = "Style"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener {
                performTapHaptic(it)
                cycleKeyboardStyle()
            }
        }
        styleBtn = themeCycleBtn
        header.addView(themeCycleBtn)

        val switchBtn = TextView(this).apply {
            text = "Switch"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                performTapHaptic(it)
                switchBackToUserKeyboard()
            }
        }
        header.addView(switchBtn)
        root.addView(header)

        // Row 2: Horizontal scrollable Mystx Command Studio chips
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(2), dp(2), dp(2), dp(6))
        }
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipsContainer = chips
        scroll.addView(chips)
        root.addView(scroll)

        // Row 3: Keypad container
        val keypad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        keysContainer = keypad
        root.addView(keypad)

        applyStyleAndRender()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        reloadPrefs()
        applyStyleAndRender()
    }

    private fun cycleKeyboardStyle() {
        val list = KeyboardStyle.entries
        val next = list[(list.indexOf(style) + 1) % list.size]
        style = next
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString(PrefKeys.KEYBOARD_STYLE, next.key).apply()
        applyStyleAndRender()
    }

    private fun applyStyleAndRender() {
        val root = rootLayout ?: return
        root.setBackgroundColor(Color.parseColor(style.boardBg))
        statusView?.apply {
            text = "Mystx · ${style.label}"
            setTextColor(Color.parseColor(style.accentHex))
        }
        styleBtn?.apply {
            setTextColor(Color.parseColor(style.keyFg))
            background = roundedBg(style.chipBg, 8, style.chipBorder)
        }
        // Style Switch button (last child of header row)
        val header = root.getChildAt(0) as? LinearLayout
        val switchBtn = header?.getChildAt(header.childCount - 1) as? TextView
        switchBtn?.apply {
            setTextColor(Color.parseColor(style.enterFg))
            background = roundedBg(style.enterBg, 8)
        }
        refreshCommandChips()
        renderKeypad()
    }

    private fun refreshCommandChips() {
        val container = chipsContainer ?: return
        container.removeAllViews()
        val commands = try {
            studioStore.getRichCommands().filter { it.enabled }
        } catch (_: Exception) {
            emptyList()
        }
        if (commands.isEmpty()) {
            val emptyHint = TextView(this).apply {
                text = "Add commands in Mystx Studio"
                setTextColor(Color.parseColor(style.subFg))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            container.addView(emptyHint)
            return
        }
        commands.forEach { cmd ->
            val chip = TextView(this).apply {
                text = "${cmd.trigger} ${cmd.name}"
                setTextColor(Color.parseColor(style.keyFg))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                background = roundedBg(style.chipBg, 10, style.chipBorder)
                setPadding(dp(11), dp(6), dp(11), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setOnClickListener {
                    performTapHaptic(it)
                    runCommandOnCurrentInput(cmd)
                }
            }
            container.addView(chip)
        }
    }

    private fun runCommandOnCurrentInput(cmd: RichCommand) {
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
        val before = ic.getTextBeforeCursor(4000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(1000, 0)?.toString().orEmpty()
        val fullText = (extracted ?: (before + after)).trim()

        if (fullText.isBlank()) {
            statusView?.text = "Type some text first"
            return
        }

        val cleanInput = if (fullText.endsWith(cmd.trigger, ignoreCase = true)) {
            fullText.dropLast(cmd.trigger.length).trim()
        } else {
            fullText
        }
        if (cleanInput.isBlank()) {
            statusView?.text = "Type text before ${cmd.trigger}"
            return
        }

        activeJob?.cancel()
        statusView?.text = "Running ${cmd.trigger}..."
        activeJob = scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    runTextCommand(
                        context = applicationContext,
                        keyManager = keyManager,
                        geminiClient = geminiClient,
                        openAIClient = openAIClient,
                        prompt = cmd.promptTemplate,
                        text = cleanInput,
                        modelOverride = cmd.modelOverride,
                        temperatureOverride = cmd.temperature
                    )
                }
                when (outcome) {
                    is CommandOutcome.Success -> {
                        val liveIc = currentInputConnection
                        if (liveIc != null) {
                            liveIc.beginBatchEdit()
                            val curBefore = liveIc.getTextBeforeCursor(10000, 0)?.length ?: 0
                            val curAfter = liveIc.getTextAfterCursor(10000, 0)?.length ?: 0
                            liveIc.deleteSurroundingText(curBefore, curAfter)
                            liveIc.commitText(outcome.text, 1)
                            liveIc.endBatchEdit()
                        }
                        try {
                            withContext(Dispatchers.IO) {
                                HistoryManager.addEntry(
                                    context = applicationContext,
                                    originalText = cleanInput,
                                    commandTrigger = cmd.trigger,
                                    response = outcome.text,
                                    isSelection = false
                                )
                            }
                        } catch (_: Exception) {
                        }
                        statusView?.text = "Done ${cmd.trigger} ✓"
                        if (autoReturnToUserKeyboard) {
                            switchBackToUserKeyboard()
                        }
                    }
                    is CommandOutcome.Failure -> statusView?.text = outcome.message.take(44)
                    is CommandOutcome.Unavailable -> statusView?.text = outcome.message.take(44)
                    is CommandOutcome.Refusal -> statusView?.text = "Model declined request"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusView?.text = "Failed: ${e.message?.take(34) ?: "error"}"
            }
        }
    }

    private fun switchBackToUserKeyboard() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (switchToPreviousInputMethod()) return
            } else {
                @Suppress("DEPRECATION")
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                val token = window?.window?.attributes?.token
                if (imm != null && token != null && imm.switchToLastInputMethod(token)) return
            }
        } catch (_: Exception) {
        }
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun renderKeypad() {
        val container = keysContainer ?: return
        container.removeAllViews()

        val rows = mutableListOf<List<String>>()
        if (!isSymbols && style.showNumberRow) {
            rows.add(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
        }
        if (!isSymbols) {
            rows.add(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"))
            rows.add(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"))
            rows.add(listOf("SHIFT", "z", "x", "c", "v", "b", "n", "m", "DEL"))
            rows.add(listOf("123", "/", "SPACE", ".", "ENTER"))
        } else {
            rows.add(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
            rows.add(listOf("@", "#", "₹", "_", "&", "-", "+", "(", ")"))
            rows.add(listOf("ABC", "*", "\"", "'", ":", ";", "!", "?", "DEL"))
            rows.add(listOf("ABC", "/", "SPACE", ",", "ENTER"))
        }

        val keyHeightDp = if (style.showNumberRow && !isSymbols) 40 else 45

        rows.forEachIndexed { rowIndex, rowKeys ->
            val isNumRow = !isSymbols && style.showNumberRow && rowIndex == 0
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                val horizPad = if (!isSymbols && rowKeys.size == 9 && rowKeys.first() == "a") dp(16) else 0
                setPadding(horizPad, 0, horizPad, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(5) }
            }

            rowKeys.forEach { key ->
                val weight = when (key) {
                    "SPACE" -> 4.3f
                    "SHIFT", "DEL", "123", "ABC", "ENTER" -> 1.55f
                    else -> 1f
                }
                val uppercaseActive = isShift || isCapsLock
                val label = when (key) {
                    "SHIFT" -> if (isCapsLock) "⇪" else if (isShift) "▲" else "△"
                    "DEL" -> "⌫"
                    "SPACE" -> style.label
                    "ENTER" -> "↵"
                    else -> if (uppercaseActive && !isSymbols && key.length == 1) key.uppercase() else key
                }
                val isSpecial = key in setOf("SHIFT", "DEL", "123", "ABC")
                val bgNormal = when {
                    key == "ENTER" -> style.enterBg
                    isSpecial -> style.specialKeyBg
                    else -> style.keyBg
                }
                val fgColor = when {
                    key == "ENTER" -> style.enterFg
                    key == "/" -> style.accentHex
                    key == "SPACE" -> style.subFg
                    else -> style.keyFg
                }
                val radius = if (key == "ENTER") style.enterRadiusDp else style.keyRadiusDp
                val height = if (isNumRow) dp(34) else dp(keyHeightDp)

                val btn = TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(fgColor))
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_SP,
                        when {
                            key == "SPACE" -> 12f
                            isNumRow -> 14f
                            key == "123" || key == "ABC" -> 13f
                            else -> 18f
                        }
                    )
                    typeface = if (key == "ENTER" || key == "/") Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    background = pressableKeyBg(bgNormal, style.keyPressedBg, radius)
                    layoutParams = LinearLayout.LayoutParams(0, height, weight).apply {
                        marginStart = dp(2)
                        marginEnd = dp(2)
                    }
                    if (key == "DEL") {
                        setOnTouchListener { v, event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    v.isPressed = true
                                    performTapHaptic(v)
                                    currentInputConnection?.deleteSurroundingText(1, 0)
                                    mainHandler.postDelayed(repeatDeleteRunnable, 380L)
                                    true
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    v.isPressed = false
                                    mainHandler.removeCallbacks(repeatDeleteRunnable)
                                    true
                                }
                                else -> false
                            }
                        }
                    } else {
                        setOnClickListener {
                            performTapHaptic(it)
                            handleKeyPress(key)
                        }
                    }
                }
                row.addView(btn)
            }
            container.addView(row)
        }
    }

    private fun handleKeyPress(key: String) {
        val ic = currentInputConnection ?: return
        when (key) {
            "SHIFT" -> {
                val now = System.currentTimeMillis()
                if (isCapsLock) {
                    isCapsLock = false
                    isShift = false
                } else if (isShift && now - lastShiftTapMs < 350L) {
                    isCapsLock = true
                    isShift = true
                } else {
                    isShift = !isShift
                }
                lastShiftTapMs = now
                renderKeypad()
            }
            "123" -> {
                isSymbols = true
                renderKeypad()
            }
            "ABC" -> {
                isSymbols = false
                renderKeypad()
            }
            "SPACE" -> {
                val now = System.currentTimeMillis()
                val before = ic.getTextBeforeCursor(3, 0)?.toString().orEmpty()
                if (now - lastSpaceTapMs < 320L && before.endsWith(" ") && before.length >= 2 && before[before.length - 2].isLetterOrDigit()) {
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(". ", 1)
                } else {
                    ic.commitText(" ", 1)
                    checkTypedSlashCommand()
                }
                lastSpaceTapMs = now
            }
            "ENTER" -> ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            else -> {
                val upper = isShift || isCapsLock
                val out = if (upper && !isSymbols && key.length == 1) key.uppercase() else key
                ic.commitText(out, 1)
                if (isShift && !isCapsLock) {
                    isShift = false
                    renderKeypad()
                }
            }
        }
    }

    private fun checkTypedSlashCommand() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(500, 0)?.toString()?.trimEnd() ?: return
        val enabled = try {
            studioStore.getRichCommands().filter { it.enabled }
        } catch (_: Exception) {
            return
        }
        val matched = enabled.firstOrNull { cmd ->
            before.endsWith(cmd.trigger, ignoreCase = true) &&
                before.length > cmd.trigger.length &&
                before[before.length - cmd.trigger.length - 1].isWhitespace()
        }
        if (matched != null) {
            runCommandOnCurrentInput(matched)
        }
    }

    private fun performTapHaptic(view: View) {
        try {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Exception) {
        }
    }

    private fun pressableKeyBg(normalHex: String, pressedHex: String, radiusDp: Int): StateListDrawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedBg(pressedHex, radiusDp))
            addState(intArrayOf(), roundedBg(normalHex, radiusDp))
        }
    }

    private fun roundedBg(fillHex: String, radiusDp: Int, strokeHex: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeHex != null) {
                setStroke(dp(1), Color.parseColor(strokeHex))
            }
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
