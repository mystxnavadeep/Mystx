package com.mystx.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mystx.app.api.GeminiClient
import com.mystx.app.api.OpenAICompatibleClient
import com.mystx.app.manager.CommandManager
import com.mystx.app.manager.CommandStudioStore
import com.mystx.app.manager.KeyManager
import com.mystx.app.manager.StatsManager
import com.mystx.app.model.Command
import com.mystx.app.model.CommandType
import com.mystx.app.model.PromptPlaceholders
import com.mystx.app.model.RichCommand
import com.mystx.app.ui.processtext.ProcessTextEdit
import com.mystx.app.ui.processtext.ProcessTextReplacementBridge
import com.mystx.app.ui.processtext.resolveProcessTextEdit
import com.mystx.app.R
import com.mystx.app.MystxApp
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AssistantService : AccessibilityService() {

    private lateinit var keyManager: KeyManager
    private lateinit var commandManager: CommandManager
    private lateinit var commandStudioStore: CommandStudioStore
    private lateinit var statsManager: StatsManager
    private val client = GeminiClient()
    private val openAIClient = OpenAICompatibleClient()
    private val serviceJob = SupervisorJob()
    // Any exception escaping a coroutine launched from the accessibility service would kill
    // the whole process with no UI to crash into (the Settings toggle stays "on" regardless,
    // so the Dashboard just shows the service as inactive). Log and swallow instead.
    private val serviceScope = CoroutineScope(
        serviceJob + Dispatchers.IO +
            CoroutineExceptionHandler { _, e ->
                Log.w(TAG, "uncaught exception in service scope", e)
            }
    )
    private val isProcessing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var triggerLastChars = setOf<Char>()
    private var cachedPrefix = CommandManager.DEFAULT_PREFIX
    private var cachedTranslatePrefix = ""
    @Volatile
    private var currentJob: Job? = null
    private var processingResetRunnable: Runnable? = null
    // Intentionally single-level undo (toggle between current and previous text).
    // Tracks the source node's identity to prevent cross-field undo corruption.
    @Volatile
    private var lastOriginalText: String? = null
    @Volatile
    private var lastUndoSourceId: String? = null
    @Volatile
    private var lastCopiedText: String? = null
    @Volatile
    private var lastReplacedText: String? = null
    @Volatile
    private var lastReplacedAt = 0L
    @Volatile
    private var lastFocusFallbackAt = 0L
    @Volatile
    private var lastReplacedSource: AccessibilityNodeInfo? = null
    private var verifyRunnable: Runnable? = null
    /** (clipboard, originalClip, clipLabel) for a paste-fallback restore that has not run yet. */
    private var pendingClipRestore: Triple<ClipboardManager, ClipData?, String>? = null
    private var lastTriggerRefresh = 0L
    private var watchdogRunnable: Runnable? = null
    private var commandsPrefs: SharedPreferences? = null
    private var studioPrefs: SharedPreferences? = null
    private var settingsPrefs: SharedPreferences? = null
    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (::commandManager.isInitialized) {
            lastTriggerRefresh = 0L
            updateTriggers()
        }
    }
    private var overlayToastInitialized = false
    private val overlayToast by lazy {
        overlayToastInitialized = true
        OverlayToast(this@AssistantService, handler)
    }
    private var autocompleteOverlayInitialized = false
    private val autocompleteOverlay by lazy {
        autocompleteOverlayInitialized = true
        CommandAutocompleteOverlay(this@AssistantService, handler)
    }

    /**
     * Stable identity for the field an undo point belongs to.
     *
     * Uses [AccessibilityNodeInfo.hashCode], which the framework overrides to derive from the
     * source node id (accessibility view id + virtual descendant id) and window id — so it is
     * a per-node value, not an identity hash, and it is equal across the successive node
     * instances the same field produces.
     *
     * This deliberately does NOT prefer viewIdResourceName, which it used to: that is the less
     * precise of the two. Sibling fields built from one layout share a resource name, so a
     * RecyclerView of identical rows or a multi-field form collapsed to a single id and undo
     * could be applied to the wrong field — exactly the corruption the check exists to prevent.
     */
    private fun sourceId(source: AccessibilityNodeInfo): String = source.hashCode().toString()

    private companion object {
        const val TAG = "MystxService"
        const val TRIGGER_REFRESH_INTERVAL_MS = 5_000L
        const val PROCESSING_WATCHDOG_MS = 120_000L
        const val WATCHDOG_FORCE_RESET_DELAY_MS = 1_500L
        const val FOCUS_FALLBACK_MIN_INTERVAL_MS = 300L
        const val MAX_DEPTH = 20
        const val NODE_BUDGET = 250
        const val MAX_SCREEN_TEXT_CHARS = 4000
        val SPINNER_FRAMES = arrayOf("◐", "◓", "◑", "◒")

        val SENSITIVE_PACKAGES = setOf(
            "com.android.systemui",
            // Password managers
            "com.agilebits.onepassword",
            "com.onepassword.android",
            "com.x8bit.bitwarden",
            "com.dashlane",
            "com.lastpass.lpandroid",
            "keepass2android.keepass2android",
            "keepass2android.keepass2android_nonet",
            "com.kunzisoft.keepass.free",
            "com.kunzisoft.keepass.libre",
            "com.kunzisoft.keepass.pro",
            "com.callpod.android_apps.keeper",
            "com.nordpass.android.app.password.manager",
            "proton.android.pass",
            "com.enpass.android",
            // Authenticators / 2FA
            "com.google.android.apps.authenticator2",
            "com.authy.authy",
            "com.azure.authenticator",
            "com.duosecurity.duomobile",
            "org.fedorahosted.freeotp",
            "org.fedorahosted.freeotpplus",
            "com.yubico.yubioath",
            "com.beemdevelopment.aegis",
            // Payments / wallets
            "com.google.android.apps.walletnfcrel",
            "com.google.android.apps.nbu.paisa.user",
            "com.phonepe.app",
            "net.one97.paytm",
            "com.paypal.android.p2pmobile",
            "com.venmo",
            "com.squareup.cash"
        )

        val SENSITIVE_PACKAGE_KEYWORDS = listOf(
            "1password",
            "bitwarden",
            "keepass",
            "dashlane",
            "lastpass",
            "authenticator",
            "password",
            "keychain",
            "bank",
            "banking",
            "wallet",
            "payment",
            ".pay.",
            ".pay"
        )
    }

    private fun isSensitivePackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val lower = pkg.lowercase(Locale.ROOT)
        if (lower in SENSITIVE_PACKAGES) return true
        return SENSITIVE_PACKAGE_KEYWORDS.any { keyword ->
            if (keyword == ".pay") lower.endsWith(".pay") else lower.contains(keyword)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            keyManager = (applicationContext as MystxApp).keyManager
            commandManager = CommandManager(applicationContext)
            commandStudioStore = CommandStudioStore(applicationContext, commandManager)
            statsManager = (applicationContext as? MystxApp)?.statsManager ?: StatsManager(applicationContext)
            commandsPrefs = applicationContext.getSharedPreferences("commands", Context.MODE_PRIVATE).also {
                it.registerOnSharedPreferenceChangeListener(prefChangeListener)
            }
            studioPrefs = applicationContext.getSharedPreferences("command_studio", Context.MODE_PRIVATE).also {
                it.registerOnSharedPreferenceChangeListener(prefChangeListener)
            }
            settingsPrefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE).also {
                it.registerOnSharedPreferenceChangeListener(prefChangeListener)
            }
            updateTriggers()
        } catch (e: Exception) {
            // This callback runs on the binder thread with no framework guard: an exception
            // here propagates to AccessibilityManagerService, which drops the service into the
            // "crashed services" limbo with the toggle still on. Log and degrade instead — the
            // event path and command path already handle an uninitialized manager (#125).
            Log.w(TAG, "onServiceConnected failed; service will stay inert until re-enabled", e)
        }
    }

    private fun updateTriggers() {
        cachedPrefix = commandManager.getTriggerPrefix()
        cachedTranslatePrefix = "${cachedPrefix}translate:"
        val cmds = commandManager.getCommands()
        triggerLastChars = cmds.mapNotNull { it.trigger.lastOrNull() }.toSet()
        lastTriggerRefresh = System.currentTimeMillis()
    }

    private fun startWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            if (isProcessing.get()) {
                val stuckJob = currentJob
                stuckJob?.cancel()
                // Let the cancelled job's finally block release isProcessing cleanly;
                // only force-reset if still stuck after cleanup.
                handler.postDelayed({
                    if (isProcessing.get() && (currentJob === stuckJob || currentJob == null)) {
                        isProcessing.set(false)
                    }
                }, WATCHDOG_FORCE_RESET_DELAY_MS)
            }
        }
        watchdogRunnable = runnable
        handler.postDelayed(runnable, PROCESSING_WATCHDOG_MS)
    }

    private fun cancelWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun cancelPendingProcessingReset() {
        processingResetRunnable?.let { handler.removeCallbacks(it) }
        processingResetRunnable = null
    }

    private fun scheduleProcessingReset() {
        cancelPendingProcessingReset()
        val runnable = Runnable { isProcessing.set(false) }
        processingResetRunnable = runnable
        if (!handler.postDelayed(runnable, 500)) {
            isProcessing.set(false)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handleAccessibilityEvent(event)
        } catch (e: Exception) {
            // AccessibilityNodeInfo methods throw IllegalStateException when the underlying
            // view is gone or the node was already recycled by the time we call into it — a
            // real race in the Accessibility API, not something we can fully prevent by
            // checking first. Nothing here ran inside a coroutine, so nothing catches this on
            // its own: an accessibility service has no foreground UI to crash into, so an
            // uncaught exception silently kills the whole service process. The Settings toggle
            // stays "on" (that flag is independent of whether the process is alive), so the
            // user sees the Dashboard go inactive with no error and no way to tell why. See
            // #125 — swallow and drop the event instead of taking the service down with it.
            Log.w(TAG, "dropping accessibility event", e)
            try { event?.source?.safeRecycle() } catch (_: Exception) {}
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        val eventPkg = event.packageName?.toString()
        if (eventPkg == packageName) return
        if (isSensitivePackage(eventPkg)) {
            dismissAutocompleteOverlay()
            return
        }
        if (!::keyManager.isInitialized) return

        // Some hosts (WeChat-style editors, WebView fields) emit text-changed events whose
        // source node is null or already recycled. Fall back to the focused input node of the
        // active window before giving up — see #125 / #131. The root lookup is a binder call on
        // the main thread, so it is throttled: hosts that flood null-source events are rare, and
        // skipping an occasional event is harmless for trigger detection.
        val source = event.source ?: run {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFocusFallbackAt < FOCUS_FALLBACK_MIN_INTERVAL_MS) return
            lastFocusFallbackAt = now
            findFocusedEditableSource()
        } ?: return

        var handedOff = false
        try {
            if (isSensitivePackage(source.packageName?.toString())) {
                dismissAutocompleteOverlay()
                source.safeRecycle()
                handedOff = true
                return
            }
            if (source.isPassword) {
                dismissAutocompleteOverlay()
                source.safeRecycle()
                handedOff = true
                return
            }
            val text = source.text?.toString() ?: run {
                source.safeRecycle()
                handedOff = true
                return
            }
            if (handlePendingProcessTextReplacement(event, source, text)) {
                handedOff = true
                return
            }
            if (isProcessing.get()) {
                dismissAutocompleteOverlay()
                source.safeRecycle()
                handedOff = true
                return
            }
            if (text.isEmpty()) {
                dismissAutocompleteOverlay()
                verifyRunnable?.let { handler.removeCallbacks(it) }
                lastReplacedText = null
                val prev = lastReplacedSource
                lastReplacedSource = null
                if (prev != null && prev !== source) {
                    prev.safeRecycle()
                }
                source.safeRecycle()
                handedOff = true
                return
            }

            // Skip events where text matches what we just replaced (prevents IME re-commit race)
            val replaced = lastReplacedText
            if (replaced != null && text == replaced &&
                System.currentTimeMillis() - lastReplacedAt < 1000) {
                source.safeRecycle()
                handedOff = true
                return
            }

            if (System.currentTimeMillis() - lastTriggerRefresh > TRIGGER_REFRESH_INTERVAL_MS) {
                updateTriggers()
            }

            val lastChar = text[text.length - 1]
            if (!triggerLastChars.contains(lastChar)) {
                if (!lastChar.isLetterOrDigit() || !text.contains(cachedTranslatePrefix)) {
                    updateAutocompleteForText(text, source)
                    source.safeRecycle()
                    handedOff = true
                    return
                }
            }

            val command = commandManager.findCommand(text) ?: run {
                updateAutocompleteForText(text, source)
                source.safeRecycle()
                handedOff = true
                return
            }

            dismissAutocompleteOverlay()

            val richCommand = commandStudioStore.getRichForCommand(command)
            if (!richCommand.enabled) {
                source.safeRecycle()
                handedOff = true
                return
            }

            val precedingText = text.substring(0, text.length - richCommand.trigger.length)
            val cleanText = precedingText.trim()

            if (richCommand.trigger.endsWith("undo") && richCommand.isBuiltIn) {
                if (!isProcessing.compareAndSet(false, true)) {
                    source.safeRecycle()
                    handedOff = true
                    return
                }
                startWatchdog()
                cancelPendingProcessingReset()
                currentJob?.cancel()
                handedOff = true
                handleUndo(source, cleanText)
                return
            }

            if (richCommand.isBuiltIn && (richCommand.trigger.endsWith("copy") || richCommand.trigger.endsWith("cut") ||
                richCommand.trigger.endsWith("paste") || richCommand.trigger.endsWith("replace"))) {
                if (!isProcessing.compareAndSet(false, true)) {
                    source.safeRecycle()
                    handedOff = true
                    return
                }
                startWatchdog()
                cancelPendingProcessingReset()
                currentJob?.cancel()
                handedOff = true
                handleClipboardCommand(source, precedingText, command)
                return
            }

            when (richCommand.type) {
                CommandType.TEXT_REPLACER -> {
                    if (!isProcessing.compareAndSet(false, true)) {
                        source.safeRecycle()
                        handedOff = true
                        return
                    }
                    startWatchdog()
                    cancelPendingProcessingReset()
                    currentJob?.cancel()
                    handedOff = true
                    currentJob = serviceScope.launch {
                        val thisJob = coroutineContext[Job]
                        try {
                            withContext(Dispatchers.Main) {
                                val replacerOk = replaceText(source, precedingText + richCommand.promptTemplate)
                                if (!replacerOk) {
                                    // Don't record an undo point, a CONFIRM haptic or a usage stat
                                    // for a replacement the field silently refused.
                                    performHapticFeedback(HapticFeedbackConstants.REJECT)
                                    showToast(getString(R.string.toast_replace_failed))
                                } else {
                                    lastOriginalText = precedingText
                                    lastUndoSourceId = sourceId(source)
                                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    statsManager.recordUsage(richCommand.trigger)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                showToast(getString(R.string.toast_replace_failed))
                            }
                        } finally {
                            withContext(NonCancellable + Dispatchers.Main) {
                                if (currentJob === thisJob) {
                                    cancelWatchdog()
                                    scheduleProcessingReset()
                                }
                            }
                            recycleIfUnowned(source)
                        }
                    }
                }
                CommandType.AI -> {
                    val requiresInputText = cleanText.isEmpty() && !richCommand.trigger.endsWith("reply") && !richCommand.promptTemplate.contains("{screen_text}")
                    if (requiresInputText) {
                        source.safeRecycle()
                        handedOff = true
                        return
                    }
                    if (!isProcessing.compareAndSet(false, true)) {
                        source.safeRecycle()
                        handedOff = true
                        return
                    }
                    startWatchdog()
                    cancelPendingProcessingReset()
                    currentJob?.cancel()
                    handedOff = true
                    processCommand(source, cleanText, richCommand)
                }
            }
        } catch (e: Exception) {
            // Ensure even fallback nodes from findFocusedEditableSource() are recycled on exception.
            if (!handedOff) {
                recycleIfUnowned(source)
            }
            throw e
        }
    }

    /**
     * Best-effort source for text-changed events that arrive without one: the focused input
     * node of the active window. Returns null when unavailable; the caller treats the result
     * exactly like a null event.source and recycles it like one. All node access is guarded —
     * the root can be stale the moment we ask (#125).
     *
     * Two-stage: `findFocus(FOCUS_INPUT)` keeps precedence (it surfaces sources upstream
     * accepts), and a bounded recursive search for an editable+focused node runs only when
     * `findFocus` reports nothing — some hosts expose the input deeper in the tree than
     * `findFocus` reaches.
     *
     * Ownership: when `findFocus` returns non-null, this method recycles `root` and returns the
     * focused node. When `findFocus` returns null, ownership of `root` is transferred to
     * [FocusedEditableFinder] which recycles every visited node except the match it returns
     * (or all on miss). The outer catch's `safeRecycle` is a safety net for the rare case where
     * an exception escapes before the finder takes ownership; double-recycle is benign
     * (`safeRecycle` catches `IllegalStateException`, and on API 33+ `recycle()` is a no-op).
     */
    private fun dismissAutocompleteOverlay() {
        if (autocompleteOverlayInitialized) {
            handler.post { autocompleteOverlay.dismiss() }
        }
    }

    private fun extractPartialTriggerToken(text: String, prefix: String): String? {
        if (text.isEmpty() || prefix.isEmpty()) return null
        if (text.last().isWhitespace()) return null
        var tokenStart = text.length
        while (tokenStart > 0 && !text[tokenStart - 1].isWhitespace()) {
            tokenStart--
        }
        val token = text.substring(tokenStart)
        if (!token.startsWith(prefix)) return null
        val suffixLen = token.length - prefix.length
        if (suffixLen !in 0..12) return null
        return token
    }

    private fun updateAutocompleteForText(text: String, source: AccessibilityNodeInfo? = null) {
        if (!::commandManager.isInitialized || !::commandStudioStore.isInitialized) return
        val prefix = commandManager.getTriggerPrefix()
        val partialToken = extractPartialTriggerToken(text, prefix)
        if (partialToken == null) {
            if (autocompleteOverlayInitialized) {
                handler.post { autocompleteOverlay.dismissAnimated() }
            }
            return
        }
        val matches = commandStudioStore.getRichCommands()
            .asSequence()
            .filter { it.enabled && it.trigger.startsWith(partialToken, ignoreCase = true) }
            .map { Command(it.trigger, it.promptTemplate, it.isBuiltIn, it.type) }
            .take(6)
            .toList()
        if (matches.isEmpty()) {
            if (autocompleteOverlayInitialized) {
                handler.post { autocompleteOverlay.dismissAnimated() }
            }
            return
        }
        val anchorTopPx = try {
            source?.let { node ->
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                rect.top.takeIf { it > 0 }
            }
        } catch (_: Exception) {
            null
        }
        handler.post {
            if (isProcessing.get()) {
                autocompleteOverlay.dismiss()
                return@post
            }
            autocompleteOverlay.show(matches, anchorTopPx) { selected ->
                onAutocompleteCommandSelected(selected, partialToken)
            }
        }
    }

    private fun onAutocompleteCommandSelected(selected: Command, partialToken: String) {
        if (isProcessing.get() || !::commandManager.isInitialized || !::commandStudioStore.isInitialized) return
        val focused = findFocusedEditableSource() ?: return
        var handedOff = false
        try {
            if (isSensitivePackage(focused.packageName?.toString()) || focused.isPassword) {
                focused.safeRecycle()
                handedOff = true
                return
            }
            val currentText = focused.text?.toString() ?: ""
            val precedingText = when {
                currentText.endsWith(partialToken, ignoreCase = true) ->
                    currentText.substring(0, currentText.length - partialToken.length)
                else -> {
                    val activePartial = extractPartialTriggerToken(currentText, commandManager.getTriggerPrefix())
                    if (activePartial != null && currentText.endsWith(activePartial)) {
                        currentText.substring(0, currentText.length - activePartial.length)
                    } else {
                        currentText
                    }
                }
            }
            val richCommand = commandStudioStore.getRichForCommand(selected)
            val cleanText = precedingText.trim()
            val canRunAiImmediately = richCommand.enabled &&
                richCommand.type == CommandType.AI &&
                (cleanText.isNotEmpty() || richCommand.trigger.endsWith("reply") || richCommand.promptTemplate.contains("{screen_text}"))

            if (canRunAiImmediately) {
                if (!isProcessing.compareAndSet(false, true)) {
                    focused.safeRecycle()
                    handedOff = true
                    return
                }
                startWatchdog()
                cancelPendingProcessingReset()
                currentJob?.cancel()
                handedOff = true
                processCommand(focused, cleanText, richCommand)
            } else {
                handedOff = true
                val updatedText = precedingText + selected.trigger
                serviceScope.launch {
                    try {
                        replaceText(focused, updatedText)
                    } catch (_: Exception) {
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) {
                            recycleIfUnowned(focused)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!handedOff) {
                recycleIfUnowned(focused)
            }
            Log.w(TAG, "autocomplete selection failed", e)
        }
    }

    private fun findFocusedEditableSource(): AccessibilityNodeInfo? {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.w(TAG, "focused-node fallback: root unavailable", e)
            null
        } ?: return null
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused === root) {
                if (root.isPassword) {
                    root.safeRecycle()
                    return null
                }
                return root
            }
            if (focused != null) {
                root.safeRecycle()
                if (focused.isPassword) {
                    focused.safeRecycle()
                    return null
                }
                return focused
            }
            // Second stage: findFocus found nothing, so walk the tree for an editable+focused
            // node. The walk recycles every visited node except the match it returns.
            val found = FocusedEditableFinder.find(AccessibilityFocusNode(root)) as? AccessibilityFocusNode
            return found?.node
        } catch (e: Exception) {
            Log.w(TAG, "focused-node fallback failed", e)
            root.safeRecycle()
            return null
        }
    }

    private fun handlePendingProcessTextReplacement(
        event: AccessibilityEvent,
        source: AccessibilityNodeInfo,
        afterText: String
    ): Boolean {
        val request = ProcessTextReplacementBridge.current(SystemClock.elapsedRealtime())
            ?: return false
        // Require a known source package that matches the event package to prevent cross-app
        // replacement hijacking.
        val eventPackage = event.packageName?.toString() ?: return false
        if (request.sourcePackage == null || eventPackage != request.sourcePackage) {
            return false
        }
        val beforeText = event.beforeText?.toString() ?: return false
        val edit = resolveProcessTextEdit(
            beforeText = beforeText,
            afterText = afterText,
            fromIndex = event.fromIndex,
            removedCount = event.removedCount,
            addedCount = event.addedCount,
            request = request
        )
        if (edit == ProcessTextEdit.Unrelated) {
            return false
        }
        if (edit is ProcessTextEdit.Appended && isProcessing.get()) {
            // A command is already running. Leave the request pending and the field untouched
            // instead of consuming the request and swallowing the user's keystroke — a later
            // text-changed event inside the bridge TTL can still apply it (#125).
            // Do NOT recycle `source` when returning false; the caller still owns it.
            return false
        }
        if (!ProcessTextReplacementBridge.consume(request)) {
            return false
        }
        if (edit == ProcessTextEdit.Replaced) {
            source.safeRecycle()
            return true
        }

        edit as ProcessTextEdit.Appended
        if (!isProcessing.compareAndSet(false, true)) {
            // Lost a race with a new command after the pre-check; the request is already
            // consumed, so drop this edit rather than clobbering the command's field writes.
            source.safeRecycle()
            return true
        }
        startWatchdog()
        cancelPendingProcessingReset()
        currentJob = serviceScope.launch {
            val thisJob = coroutineContext[Job]
            try {
                val replaced = replaceText(source, edit.correctedText)
                if (replaced) {
                    lastOriginalText = beforeText
                    lastUndoSourceId = sourceId(source)
                } else {
                    withContext(Dispatchers.Main) {
                        showToast(getString(R.string.toast_replace_failed))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    showToast(getString(R.string.toast_replace_failed))
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentJob === thisJob) {
                        cancelWatchdog()
                        scheduleProcessingReset()
                    }
                    recycleIfUnowned(source)
                }
            }
        }
        return true
    }

    private fun processCommand(source: AccessibilityNodeInfo, text: String, command: RichCommand) {
        if (!keyManager.keystoreAvailable) {
            // keys_keystore_error rather than toast_keystore_unavailable: the latter tells the
            // user to reinstall, which destroys every key, command and setting, and does not
            // address the usual cause (the KeyStore key being invalidated by a lock-screen
            // change, where re-adding the keys is enough). Both strings are already localized.
            handler.post { overlayToast.show(getString(R.string.keys_keystore_error)) }
            cancelWatchdog()
            isProcessing.set(false)
            recycleIfUnowned(source)
            return
        }

        currentJob = serviceScope.launch {
            val thisJob = coroutineContext[Job]
            val originalText = text
            var spinnerJob: Job? = null
            try {
                val lang = PromptPlaceholders.languageFromTrigger(command.trigger)
                val appPackage = source.packageName?.toString()

                val needsScreenText = command.promptTemplate.contains("{screen_text}") || command.trigger.endsWith("reply")
                val screenTextStr = if (needsScreenText) {
                    val root = try { rootInActiveWindow } catch (e: Exception) { null }
                    extractScreenText(root, source)
                } else null

                // Prompt injection defense: never interpolate untrusted screen text into the
                // system prompt. Replace {screen_text} with a pointer and pass the screen context
                // strictly inside the user payload fenced in <screen_context>...</screen_context>.
                val sanitizedTemplate = command.promptTemplate
                    .replace("{screen_text}", "the <screen_context> block in the user input")
                val placeholderContext = PromptPlaceholders.Context(
                    text = text,
                    language = lang,
                    tone = null,
                    instruction = null,
                    app = appPackage,
                    screenText = null
                )
                val finalPrompt = PromptPlaceholders.render(sanitizedTemplate, placeholderContext)
                val userPayload = if (needsScreenText && !screenTextStr.isNullOrBlank()) {
                    if (text.isBlank()) {
                        "<screen_context>\n$screenTextStr\n</screen_context>"
                    } else {
                        "$text\n\n<screen_context>\n$screenTextStr\n</screen_context>"
                    }
                } else {
                    text
                }

                // Bypass cache whenever screen context is involved to prevent cross-conversation
                // cache collisions on ?reply or {screen_text} commands.
                val cachedResponse = if (needsScreenText) {
                    null
                } else {
                    com.mystx.app.model.HistoryManager.findCachedResponse(applicationContext, originalText, command.trigger)
                }
                val outcome = if (cachedResponse != null) {
                    CommandOutcome.Success(cachedResponse)
                } else {
                    withTimeout(90_000) {
                        runTextCommand(
                            applicationContext, keyManager, client, openAIClient,
                            finalPrompt, userPayload,
                            modelOverride = command.modelOverride,
                            temperatureOverride = command.temperature
                        ) { spinnerJob = startInlineSpinner(source, originalText) }
                    }
                }
                // From the first attempt onward the field holds the spinner glyph instead of the
                // user's text, so every outcome below starts by taking it back out. No spinner
                // means no usable key was ever found and the field was never touched — a failed
                // no-op write must not produce a "could not restore your text" prefix.
                val fieldWasAltered = spinnerJob != null
                spinnerJob?.cancelAndJoin()
                spinnerJob = null

                when (outcome) {
                    is CommandOutcome.Success -> {
                        if (!replaceText(source, outcome.text)) {
                            // The field rejected the write. Restore the user's text, and don't
                            // record an undo point or a CONFIRM haptic for text that never landed.
                            replaceText(source, originalText)
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            showToast(getString(R.string.toast_replace_failed))
                        } else {
                            lastOriginalText = originalText
                            lastUndoSourceId = sourceId(source)
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            statsManager.recordUsage(command.trigger)
                            com.mystx.app.model.HistoryManager.addEntry(applicationContext, originalText, command.trigger, outcome.text, false)
                        }
                    }
                    is CommandOutcome.Refusal -> {
                        replaceText(source, originalText)
                        performHapticFeedback(HapticFeedbackConstants.REJECT)
                        showToast(getString(R.string.error_safety_blocked))
                    }
                    // Nothing was sent, so there is nothing to restore.
                    is CommandOutcome.Unavailable -> showToast(outcome.message)
                    is CommandOutcome.Failure -> {
                        val restoredOk = !fieldWasAltered || replaceText(source, originalText)
                        performHapticFeedback(HapticFeedbackConstants.REJECT)
                        showToast(
                            if (restoredOk) outcome.message
                            else getString(R.string.toast_restore_failed) + "\n" + outcome.message
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                spinnerJob?.cancelAndJoin()
                // If the restore fails the field is left holding the spinner glyph, which
                // matters more to the user than the timeout itself — say so rather than
                // swallowing it (both strings already exist in every locale).
                var restoreFailed = false
                try { restoreFailed = !replaceText(source, originalText) } catch (_: Exception) { restoreFailed = true }
                showToast(
                    if (restoreFailed) getString(R.string.toast_restore_failed) + "\n" + getString(R.string.toast_request_timed_out)
                    else getString(R.string.toast_request_timed_out)
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    spinnerJob?.cancel()
                    // Only restore if this job still owns the field. The watchdog clears
                    // isProcessing as soon as it cancels, so a new command can start while this
                    // handler is still running under NonCancellable — and restoring the old text
                    // then would overwrite what the newer job has already written.
                    if (currentJob === thisJob) {
                        try { replaceText(source, originalText) } catch (_: Exception) {}
                    }
                }
                throw e
            } catch (e: Exception) {
                spinnerJob?.cancelAndJoin()
                // showToast() dismisses any visible toast first, so the previous code's
                // restore-failure toast was destroyed microseconds later by the error toast
                // below — making it unreadable. Combine them instead.
                var restoreFailed = false
                try { restoreFailed = !replaceText(source, originalText) } catch (_: Exception) { restoreFailed = true }
                val mapped = mapErrorMessage(e.message ?: "Unknown error")
                showToast(if (restoreFailed) getString(R.string.toast_restore_failed) + "\n" + mapped else mapped)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentJob === thisJob) {
                        cancelWatchdog()
                        scheduleProcessingReset()
                    }
                    spinnerJob?.cancel()
                    recycleIfUnowned(source)
                }
            }
        }
    }

    private fun handleUndo(source: AccessibilityNodeInfo, currentText: String) {
        currentJob = serviceScope.launch {
            val thisJob = coroutineContext[Job]
            try {
                val previousText = lastOriginalText
                val undoId = lastUndoSourceId
                if (previousText == null || undoId != sourceId(source)) {
                    performHapticFeedback(HapticFeedbackConstants.REJECT)
                    showToast(getString(R.string.toast_nothing_to_undo))
                } else if (replaceText(source, previousText)) {
                    // Commit the new undo point only after the write succeeded. Doing it first
                    // meant a silently-failed replace destroyed the saved original text.
                    lastOriginalText = currentText
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    performHapticFeedback(HapticFeedbackConstants.REJECT)
                    showToast(getString(R.string.toast_undo_failed))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast(getString(R.string.toast_undo_failed))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentJob === thisJob) {
                        cancelWatchdog()
                        scheduleProcessingReset()
                    }
                    recycleIfUnowned(source)
                }
            }
        }
    }

    private fun handleClipboardCommand(source: AccessibilityNodeInfo, precedingText: String, command: Command) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        currentJob = serviceScope.launch {
            val thisJob = coroutineContext[Job]
            try {
                val trigger = command.trigger
                when {
                    trigger.endsWith("copy") -> {
                        val textToCopy = precedingText.trim()
                        if (textToCopy.isEmpty()) {
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            showToast(getString(R.string.toast_nothing_to_copy))
                        } else {
                            // The success decision must be made OUTSIDE the inner withContext:
                            // return@withContext exits only that lambda, so the success toast
                            // still fired — and since showToast dismisses the previous toast, it
                            // hid the failure message entirely.
                            val wrote = withContext(Dispatchers.Main) {
                                replaceText(source, precedingText, callerOwnsClipboard = true)
                            }
                            if (wrote) {
                                lastCopiedText = textToCopy
                                withContext(Dispatchers.Main) {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Mystx", textToCopy))
                                }
                                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                showToast(getString(R.string.toast_copied))
                                statsManager.recordUsage(command.trigger)
                            } else {
                                performHapticFeedback(HapticFeedbackConstants.REJECT)
                                showToast(getString(R.string.toast_replace_failed))
                            }
                        }
                    }
                    trigger.endsWith("cut") -> {
                        val textToCut = precedingText.trim()
                        if (textToCut.isEmpty()) {
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            showToast(getString(R.string.toast_nothing_to_cut))
                        } else {
                            val wrote = withContext(Dispatchers.Main) {
                                replaceText(source, "", callerOwnsClipboard = true)
                            }
                            if (wrote) {
                                lastCopiedText = textToCut
                                lastOriginalText = precedingText
                                lastUndoSourceId = sourceId(source)
                                withContext(Dispatchers.Main) {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Mystx", textToCut))
                                }
                                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                showToast(getString(R.string.toast_cut))
                                statsManager.recordUsage(command.trigger)
                            } else {
                                // Never claim "Cut to clipboard" while the text is still there.
                                performHapticFeedback(HapticFeedbackConstants.REJECT)
                                showToast(getString(R.string.toast_replace_failed))
                            }
                        }
                    }
                    trigger.endsWith("paste") -> handlePasteInto(source, precedingText, keepPrefix = precedingText, command = command)
                    trigger.endsWith("replace") -> handlePasteInto(source, precedingText, keepPrefix = "", command = command)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast(getString(R.string.toast_clipboard_failed))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentJob === thisJob) {
                        cancelWatchdog()
                        scheduleProcessingReset()
                    }
                    recycleIfUnowned(source)
                }
            }
        }
    }

    /**
     * Handles `?paste` and `?replace`.
     *
     * [keepPrefix] is the text to keep in front of the pasted content: the text preceding
     * the trigger for `?paste`, empty for `?replace`.
     *
     * Prefers [pasteFromSystemClipboard], which drives the target app's own paste action so
     * the *real* system clipboard is used. The previous implementation read the clipboard
     * itself and fell back to [lastCopiedText], which could never work for text copied in
     * another app: an accessibility service is not the focused window and not the default
     * IME, and framework ClipboardService gates OP_READ_CLIPBOARD on exactly that — so
     * `primaryClip` was always null and `?paste` reported "Clipboard is empty" for anything
     * Mystx had not copied itself. Writing is not gated, which is why `?copy` works and
     * why the fallback below can still stage text on the clipboard.
     */
    private suspend fun handlePasteInto(
        source: AccessibilityNodeInfo,
        precedingText: String,
        keepPrefix: String,
        command: Command
    ) {
        val pasted = pasteFromSystemClipboard(source, keepPrefix)
        if (pasted != null) {
            lastOriginalText = precedingText
            lastUndoSourceId = sourceId(source)
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            statsManager.recordUsage(command.trigger)
            return
        }

        // Either the clipboard is empty or the field ignored ACTION_SET_TEXT / ACTION_PASTE.
        // Fall back to the only clipboard content this service is allowed to know about: what
        // it last copied itself.
        val fallback = lastCopiedText
        if (fallback.isNullOrEmpty()) {
            performHapticFeedback(HapticFeedbackConstants.REJECT)
            showToast(getString(R.string.toast_clipboard_empty))
            return
        }
        if (replaceText(source, keepPrefix + fallback)) {
            lastOriginalText = precedingText
            lastUndoSourceId = sourceId(source)
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            statsManager.recordUsage(command.trigger)
        } else {
            performHapticFeedback(HapticFeedbackConstants.REJECT)
            showToast(getString(R.string.toast_replace_failed))
        }
    }

    /**
     * Strips the trigger, puts the caret after [keepPrefix], and asks the target app to paste.
     * The app performs the read under its own window focus, so this honours text copied
     * anywhere on the device without Mystx ever reading the clipboard.
     *
     * Returns the resulting field text, or null when the field does not offer a paste action,
     * the app refused a step, or the paste landed somewhere unexpected. The field is left
     * untouched in the first case and holding [keepPrefix] in the others, both of which the
     * caller's fallback can recover from.
     */
    private suspend fun pasteFromSystemClipboard(
        source: AccessibilityNodeInfo,
        keepPrefix: String
    ): String? = withContext(Dispatchers.Main) {
        if (!source.refresh()) return@withContext null
        // Check before touching anything: TextView only advertises ACTION_PASTE when the field is
        // editable, has a selection, AND hasPrimaryClip() is true. Without this guard an empty
        // clipboard still got the trigger stripped by the setFieldText below, so the command
        // silently ate the user's "?paste" and then reported the clipboard was empty.
        val pasteSupported = source.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_PASTE
        }
        if (!pasteSupported) return@withContext null
        if (!setFieldText(source, keepPrefix)) return@withContext null
        delay(50)
        if (!source.refresh()) return@withContext null
        // Bail out if the field silently rejected the write, otherwise the paste would land
        // on top of the still-present trigger text.
        if (source.text?.toString() != keepPrefix) return@withContext null

        val caret = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, keepPrefix.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, keepPrefix.length)
        }
        source.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, caret)
        if (!source.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return@withContext null

        delay(100)
        if (!source.refresh()) return@withContext null
        val after = source.text?.toString() ?: return@withContext null
        // Nothing added => empty clipboard or an ignored paste. Not starting with keepPrefix
        // => the app ignored ACTION_SET_SELECTION and pasted somewhere else; treat both as a
        // failure so the caller's fallback can put the field into a known state.
        if (after == keepPrefix || !after.startsWith(keepPrefix)) return@withContext null
        scheduleTextVerification(source, after)
        after
    }

    /**
     * Writes [newText] into [source]. Returns false when the field could not be updated.
     * It previously returned Unit and signalled failure by returning early, which made every
     * failure invisible: handleUndo had already overwritten its saved original text, and the
     * restore paths could not tell a real restore from a silent no-op.
     */
    private suspend fun replaceText(
        source: AccessibilityNodeInfo,
        newText: String,
        // When the caller owns the clipboard after this call (?copy / ?cut), the paste fallback
        // must not restore or clear it — that destroyed the very clip the command just placed.
        callerOwnsClipboard: Boolean = false
    ): Boolean = withContext(Dispatchers.Main) {
        var currentSource = source
        var sourceIsOwned = false
        val expectedPackage = try { source.packageName?.toString() } catch (_: Exception) { null }
        val expectedSourceId = try { sourceId(source) } catch (_: Exception) { null }

        fun refreshOrFallback(): Boolean {
            if (currentSource.refresh()) return true
            val fallback = findFocusedEditableSource() ?: return false
            val fallbackPkg = try { fallback.packageName?.toString() } catch (_: Exception) { null }
            val fallbackId = try { sourceId(fallback) } catch (_: Exception) { null }
            val fallbackIsPassword = try { fallback.isPassword } catch (_: Exception) { true }
            if (fallbackIsPassword ||
                expectedPackage == null || fallbackPkg != expectedPackage ||
                expectedSourceId == null || fallbackId != expectedSourceId
            ) {
                fallback.safeRecycle()
                return false
            }
            if (sourceIsOwned && currentSource !== lastReplacedSource) currentSource.safeRecycle()
            currentSource = fallback
            sourceIsOwned = true
            return true
        }

        try {
            if (!refreshOrFallback()) return@withContext false
            val bundle = Bundle()
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)

            val success = currentSource.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)

            if (success) {
                // Verify the text actually persisted — some apps (Firefox, Google Keep)
                // return true but don't update their internal text state
                delay(100)
                if (!refreshOrFallback()) {
                    // The node was recycled during the verification delay. Reading .text now
                    // would throw IllegalStateException; report the write as unverified and let
                    // the caller's failure path handle it instead of failing the replacement.
                    return@withContext false
                }
                val currentText = currentSource.text?.toString()
                if (currentText == newText) {
                    scheduleTextVerification(currentSource, newText)
                    return@withContext true // Text persisted
                }
                // Some editors (WebView-based, Samsung Notes, Keep) accept ACTION_SET_TEXT but
                // commit asynchronously — give them one longer window before declaring the write
                // ignored, so note-apps don't get a spurious clipboard fallback (#125).
                delay(400)
                if (!refreshOrFallback()) return@withContext false
                val settledText = currentSource.text?.toString()
                if (settledText == newText) {
                    scheduleTextVerification(currentSource, newText)
                    return@withContext true // Text persisted late
                }
                // Text didn't persist, fall through to clipboard fallback
            }

            // Clipboard fallback: select all + paste (goes through app's input pipeline)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val oldClip = clipboard.primaryClip
            val clipLabel = "mystx_paste_${System.currentTimeMillis()}"
            val newClip = ClipData.newPlainText(clipLabel, newText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                newClip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            clipboard.setPrimaryClip(newClip)

            if (!refreshOrFallback() || currentSource.text == null) {
                // We already replaced the clipboard above; bail out without leaving our temp clip
                // (which holds the transformed text) as the user's clipboard.
                if (!callerOwnsClipboard) restoreClipboard(clipboard, oldClip, clipLabel)
                return@withContext false
            }
            val selectAllArgs = Bundle()
            selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentSource.text?.length ?: 0)
            currentSource.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs)

            val pasted = currentSource.performAction(AccessibilityNodeInfo.ACTION_PASTE)

            if (pasted) {
                scheduleTextVerification(currentSource, newText)
            }

            if (!callerOwnsClipboard) {
                // Deliberately does NOT touch `source`: scheduleTextVerification recycles the node
                // at +300ms, so source.refresh() here threw IllegalStateException on API < 33.
                // pendingClipRestore lets onInterrupt/onDestroy run this synchronously — both flush
                // the handler, which previously cancelled it and left Mystx's temp clip (the
                // transformed text) as the user's clipboard indefinitely.
                val currentPending = Triple(clipboard, oldClip, clipLabel)
                pendingClipRestore = currentPending
                handler.postDelayed({
                    try {
                        restoreClipboard(clipboard, oldClip, clipLabel)
                    } catch (_: Exception) {
                    } finally {
                        if (pendingClipRestore === currentPending) {
                            pendingClipRestore = null
                        }
                    }
                }, 500)
            }
            // Report what the paste action actually returned. Returning an unconditional true here
            // silently defeated every caller's failure check.
            pasted
        } finally {
            if (sourceIsOwned && currentSource !== lastReplacedSource) {
                currentSource.safeRecycle()
            }
        }
    }

    /**
     * Puts the user's clipboard back after the paste fallback in [replaceText], checking the
     * unique clip label first so anything the user copied in the meantime is never clobbered.
     * On API 28+, clears the primary clip cleanly when [oldClip] is unavailable instead of
     * writing an empty string or stale [lastCopiedText].
     */
    private fun restoreClipboard(clipboard: ClipboardManager, oldClip: ClipData?, ourClipLabel: String) {
        try {
            val currentLabel = clipboard.primaryClipDescription?.label?.toString()
            if (currentLabel != null && currentLabel != ourClipLabel) return // user copied something newer
            if (oldClip != null) {
                clipboard.setPrimaryClip(oldClip)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.safeRecycle() {
        try { recycle() } catch (_: Exception) {}
    }

    /** Recycle source only if scheduleTextVerification didn't take ownership. */
    private fun recycleIfUnowned(source: AccessibilityNodeInfo) {
        if (lastReplacedSource !== source) {
            source.safeRecycle()
        }
    }

    private fun scheduleTextVerification(source: AccessibilityNodeInfo, expectedText: String) {
        lastReplacedText = expectedText
        lastReplacedAt = System.currentTimeMillis()
        // Recycle the previous source if it's a different node
        val prev = lastReplacedSource
        if (prev != null && prev !== source) {
            prev.safeRecycle()
        }
        lastReplacedSource = source
        verifyRunnable?.let { handler.removeCallbacks(it) }
        val capturedSource = source
        val runnable = Runnable {
            try {
                if (!capturedSource.refresh()) return@Runnable
                val currentText = capturedSource.text?.toString()
                val isImeClobber = currentText != null && currentText.isNotEmpty() && expectedText.startsWith(currentText)
                if (isImeClobber && currentText != expectedText && currentText.length < expectedText.length) {
                    val bundle = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expectedText)
                    }
                    capturedSource.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                }
            } catch (_: Exception) {
            } finally {
                // Only recycle if this source is still the current one (not replaced by a newer command)
                if (lastReplacedSource === capturedSource) {
                    lastReplacedText = null
                    capturedSource.safeRecycle()
                    lastReplacedSource = null
                }
            }
        }
        verifyRunnable = runnable
        if (!handler.postDelayed(runnable, 300)) {
            lastReplacedText = null
            lastReplacedAt = 0L
            lastReplacedSource = null
        }
    }

    private fun setFieldText(source: AccessibilityNodeInfo, text: String): Boolean {
        if (!source.refresh()) return false
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return source.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    private fun startInlineSpinner(source: AccessibilityNodeInfo, baseText: String): Job {
        return serviceScope.launch(Dispatchers.Main) {
            var frameIndex = 0
            try {
                while (isActive) {
                    if (!setFieldText(source, "$baseText ${SPINNER_FRAMES[frameIndex]}")) break
                    frameIndex = (frameIndex + 1) % SPINNER_FRAMES.size
                    delay(200)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The node was recycled or the view went away mid-request; stop the spinner
                // instead of letting an unhandled exception kill the service. The AI result
                // path still restores the original text. See #125.
                Log.w(TAG, "spinner node went stale; stopping spinner", e)
            }
        }
    }

    /**
     * Runs a paste-fallback clipboard restore that has not fired yet. Both onInterrupt and
     * onDestroy call handler.removeCallbacksAndMessages(null), which cancelled the pending
     * +500ms restore and left Mystx's temp clip (the user's transformed text) on the
     * clipboard for good.
     */
    private fun flushPendingClipRestore() {
        val pending = pendingClipRestore ?: return
        pendingClipRestore = null
        restoreClipboard(pending.first, pending.second, pending.third)
    }

    private fun mapErrorMessage(raw: String): String = getString(ErrorMessages.map(raw))

    private suspend fun showToast(msg: String) = withContext(Dispatchers.Main) {
        overlayToast.show(msg)
    }

    @Suppress("DEPRECATION")
    private fun performHapticFeedback(feedbackType: Int) {
        handler.post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager ?: return@post
                    val vibrator = vibratorManager.defaultVibrator
                    if (!vibrator.hasVibrator()) return@post
                    when (feedbackType) {
                        HapticFeedbackConstants.CONFIRM ->
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                        HapticFeedbackConstants.REJECT ->
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return@post
                    if (!vibrator.hasVibrator()) return@post
                    when (feedbackType) {
                        HapticFeedbackConstants.CONFIRM ->
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                        HapticFeedbackConstants.REJECT ->
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return@post
                    if (!vibrator.hasVibrator()) return@post
                    vibrator.vibrate(VibrationEffect.createOneShot(40L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return@post
                    if (!vibrator.hasVibrator()) return@post
                    vibrator.vibrate(40L)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {
        flushPendingClipRestore()
        isProcessing.set(false)
        currentJob?.cancel()
        serviceJob.cancelChildren()
        handler.removeCallbacksAndMessages(null)
        lastReplacedText = null
        lastReplacedAt = 0L
        lastReplacedSource?.safeRecycle()
        lastReplacedSource = null
        if (overlayToastInitialized) {
            overlayToast.dismiss()
        }
        if (autocompleteOverlayInitialized) {
            autocompleteOverlay.dismiss()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        commandsPrefs?.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        studioPrefs?.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        settingsPrefs?.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        commandsPrefs = null
        studioPrefs = null
        settingsPrefs = null
        flushPendingClipRestore()
        isProcessing.set(false)
        lastReplacedText = null
        lastReplacedAt = 0L
        lastReplacedSource?.safeRecycle()
        lastReplacedSource = null
        handler.removeCallbacksAndMessages(null)
        if (overlayToastInitialized) {
            overlayToast.dismiss()
        }
        if (autocompleteOverlayInitialized) {
            autocompleteOverlay.dismiss()
        }
        serviceScope.cancel()
    }

    private fun extractScreenText(root: AccessibilityNodeInfo?, skipNode: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val rootPkg = try { root.packageName?.toString() } catch (_: Exception) { null }
        if (isSensitivePackage(rootPkg)) {
            if (root !== skipNode) root.safeRecycle()
            return ""
        }

        val sb = StringBuilder()
        val visited = mutableSetOf<String>()
        var remainingBudget = NODE_BUDGET

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            try {
                if (depth > MAX_DEPTH || remainingBudget <= 0 || sb.length >= MAX_SCREEN_TEXT_CHARS) return
                remainingBudget--

                val id = try { sourceId(node) } catch (_: Exception) { return }
                if (!visited.add(id)) return

                val isSkip = try { node === skipNode || node == skipNode } catch (_: Exception) { false }
                if (isSkip) return

                val isPassword = try { node.isPassword } catch (_: Exception) { true }
                if (isPassword) return

                val visible = try { node.isVisibleToUser } catch (_: Exception) { false }
                if (visible) {
                    val nodeText = try {
                        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                            ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    } catch (_: Exception) {
                        null
                    }
                    if (!nodeText.isNullOrBlank()) {
                        val remaining = MAX_SCREEN_TEXT_CHARS - sb.length
                        if (remaining > 0) {
                            if (sb.isNotEmpty() && remaining > 1) {
                                sb.append('\n')
                            }
                            val allowed = MAX_SCREEN_TEXT_CHARS - sb.length
                            if (allowed > 0) {
                                sb.append(if (nodeText.length > allowed) nodeText.substring(0, allowed) else nodeText)
                            }
                        }
                    }
                }

                if (sb.length >= MAX_SCREEN_TEXT_CHARS || remainingBudget <= 0 || depth >= MAX_DEPTH) return

                val childCount = try { node.childCount } catch (_: Exception) { 0 }
                for (i in 0 until childCount) {
                    if (remainingBudget <= 0 || sb.length >= MAX_SCREEN_TEXT_CHARS) break
                    val child = try { node.getChild(i) } catch (_: Exception) { null }
                    if (child != null) {
                        traverse(child, depth + 1)
                    }
                }
            } finally {
                if (node !== skipNode) {
                    node.safeRecycle()
                }
            }
        }

        traverse(root, 0)
        return sb.toString().trim().take(MAX_SCREEN_TEXT_CHARS)
    }
}
