package com.mystx.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.mystx.app.R
import com.mystx.app.api.ApiClientUtils
import com.mystx.app.api.GeminiClient
import com.mystx.app.api.OpenAICompatibleClient
import com.mystx.app.manager.KeyManager
import com.mystx.app.model.PrefKeys
import com.mystx.app.model.ProviderType
import com.mystx.app.provider.BaiConfig
import com.mystx.app.provider.OpenCodeConfig
import com.mystx.app.provider.SarvamConfig
import com.mystx.app.ui.components.MystAuroraBackdrop
import com.mystx.app.ui.components.MystCard
import com.mystx.app.ui.components.MystDivider
import com.mystx.app.ui.components.MystGradientButton
import com.mystx.app.ui.components.MystPill
import com.mystx.app.ui.components.MystTextField
import com.mystx.app.ui.components.MystTonalButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun isMystxAccessibilityActive(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}

/**
 * 2-Step Post-Login Quick Start flow:
 * Step 1: Accessibility Permission (+ Android 13+ Restricted Settings shortcut)
 * Step 2: Add API Keys (supports multiple Gemini keys for round-robin load balancing
 *         + Groq / Custom backup keys for automatic failover)
 */
@Composable
fun QuickStartScreen(
    keyManager: KeyManager,
    prefs: SharedPreferences,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var step by remember { mutableIntStateOf(1) }
    var serviceEnabled by remember { mutableStateOf(isMystxAccessibilityActive(context)) }
    var batteryOk by remember {
        mutableStateOf(
            try {
                (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            } catch (_: Exception) { true }
        )
    }
    var keys by remember { mutableStateOf<List<String>>(emptyList()) }
    var newKey by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    val geminiClient = remember { GeminiClient() }
    val openAIClient = remember { OpenAICompatibleClient() }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            serviceEnabled = withContext(Dispatchers.IO) { isMystxAccessibilityActive(context) }
            batteryOk = try {
                (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            } catch (_: Exception) { true }
            keys = withContext(Dispatchers.IO) { keyManager.getKeys() }
        }
    }

    val activeProv = ProviderType.sanitize(prefs.getString(PrefKeys.PROVIDER_TYPE, ProviderType.GEMINI))
    val geminiCount = keys.count { keyManager.getProviderForKey(it, activeProv) == ProviderType.GEMINI }
    val sarvamCount = keys.count { keyManager.getProviderForKey(it, activeProv) == ProviderType.SARVAM }
    val otherCount = keys.size - geminiCount - sarvamCount

    Box(modifier = Modifier.fillMaxSize()) {
        MystAuroraBackdrop()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top bar: Quick Start title + Skip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Quick Start",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Step $step of 2 · Ready in 30 seconds",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onComplete) {
                    Text(
                        text = "Skip",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Step Progress Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StepIndicatorChip(
                    number = "1",
                    label = "Permission",
                    active = step == 1,
                    done = serviceEnabled,
                    onClick = { step = 1 },
                    modifier = Modifier.weight(1f)
                )
                StepIndicatorChip(
                    number = "2",
                    label = "API Keys",
                    active = step == 2,
                    done = keys.isNotEmpty(),
                    onClick = { step = 2 },
                    modifier = Modifier.weight(1f)
                )
            }

            if (step == 1) {
                // STEP 1: PERMISSION PAGE
                MystCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Enable Accessibility",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        MystPill(text = if (serviceEnabled) "ACTIVE" else "REQUIRED")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Mystx uses Android Accessibility only to detect slash commands (like /fix or /heng) while you type and replace them with AI output. Passwords and banking fields are never read.",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    MystGradientButton(
                        text = if (serviceEnabled) "Accessibility Enabled ✓" else "Open Accessibility Settings",
                        onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            } catch (_: ActivityNotFoundException) {
                            } catch (_: Exception) {
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!serviceEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Spacer(modifier = Modifier.height(12.dp))
                        MystDivider()
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Android 13+ Restricted Settings?",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Tap App Info → tap ⋮ menu (top-right) → Allow restricted settings, then enable Mystx in Accessibility.",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        MystTonalButton(
                            text = "Open App Info",
                            onClick = {
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    )
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (!batteryOk) {
                    MystCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "Disable Battery Restrictions",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Recommended on Poco / Xiaomi HyperOS so the system doesn't kill Mystx.",
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            MystTonalButton(
                                text = "Allow",
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                        )
                                    } catch (_: Exception) {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                        } catch (_: Exception) {}
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                MystGradientButton(
                    text = "Next: Add API Keys →",
                    onClick = { step = 2 },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // STEP 2: ADD API KEYS (MULTI-KEY LOAD BALANCING + GROQ FAILOVER)
                MystCard {
                    Text(
                        text = "Add Your API Keys",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Add multiple Gemini keys to split requests automatically, plus a Sarvam AI or OpenCode key as instant backup if Gemini hits rate limits.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Live available provider key counts
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProviderCountPill("Gemini", geminiCount, Modifier.weight(1f))
                        ProviderCountPill("Sarvam AI", sarvamCount, Modifier.weight(1f))
                        if (otherCount > 0) {
                            ProviderCountPill("Custom", otherCount, Modifier.weight(1f))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    MystTextField(
                        value = newKey,
                        onValueChange = { if (it.length <= 256) newKey = it },
                        placeholder = { Text("Paste Gemini, Sarvam AI, or OpenCode key", fontSize = 13.sp) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    MystGradientButton(
                        text = if (isTesting) "Verifying Key..." else "Verify & Add Key",
                        enabled = newKey.isNotBlank() && !isTesting && keyManager.keystoreAvailable,
                        onClick = {
                            val trimmed = newKey.trim()
                            if (trimmed.isBlank()) return@MystGradientButton
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isTesting = true
                            statusMessage = null
                            scope.launch {
                                val existing = withContext(Dispatchers.IO) { keyManager.getKeys() }
                                if (existing.contains(trimmed)) {
                                    withContext(Dispatchers.IO) { keyManager.clearMarks(trimmed) }
                                    isTesting = false
                                    statusIsError = false
                                    statusMessage = "Key already added — refreshed as Ready."
                                    newKey = ""
                                    return@launch
                                }
                                val detected = KeyManager.detectProviderType(trimmed, activeProv)
                                val customEp = (prefs.getString(PrefKeys.CUSTOM_ENDPOINT, "") ?: "").trim()
                                val result = when (detected) {
                                    ProviderType.GEMINI -> geminiClient.validateKey(trimmed)
                                    ProviderType.SARVAM -> openAIClient.validateKey(trimmed, SarvamConfig.ENDPOINT)
                                    ProviderType.OPENCODE -> openAIClient.validateKey(trimmed, OpenCodeConfig.ENDPOINT)
                                    else -> {
                                        val activeProv = ProviderType.sanitize(prefs.getString(PrefKeys.PROVIDER_TYPE, null))
                                        when {
                                            activeProv == ProviderType.BAI -> openAIClient.validateKey(trimmed, BaiConfig.ENDPOINT)
                                            customEp.isNotBlank() -> openAIClient.validateKey(trimmed, customEp)
                                            else -> geminiClient.validateKey(trimmed)
                                        }
                                    }
                                }
                                isTesting = false
                                if (result.isSuccess) {
                                    val saved = withContext(Dispatchers.IO) { keyManager.addKey(trimmed, detected) }
                                    if (saved) {
                                        keys = withContext(Dispatchers.IO) { keyManager.getKeys() }
                                        // Enable automatic failover when user adds backup keys
                                        prefs.edit().putBoolean(PrefKeys.FAILOVER_ENABLED, true).apply()
                                        newKey = ""
                                        statusIsError = false
                                        val label = when (detected) {
                                            ProviderType.GEMINI -> "Gemini"
                                            ProviderType.SARVAM -> "Sarvam AI"
                                            ProviderType.OPENCODE -> "OpenCode"
                                            else -> "Custom"
                                        }
                                        statusMessage = "$label key added! Add another key for rotation/failover or tap Finish."
                                        try {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("", ""))
                                        } catch (_: Exception) {}
                                    } else {
                                        statusIsError = true
                                        statusMessage = context.getString(R.string.keys_keystore_error)
                                    }
                                } else {
                                    statusIsError = true
                                    val raw = result.exceptionOrNull()?.message ?: ""
                                    statusMessage = ApiClientUtils.redactSecrets(raw).ifBlank {
                                        "Invalid API key. Check the key and try again."
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    statusMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = msg,
                            fontSize = 12.sp,
                            color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    MystDivider()
                    Spacer(modifier = Modifier.height(10.dp))

                    // Free API key quick links
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MystTonalButton(
                            text = "Get Gemini Key",
                            onClick = {
                                try { uriHandler.openUri("https://aistudio.google.com/api-keys") } catch (_: Exception) {}
                            },
                            modifier = Modifier.weight(1f)
                        )
                        MystTonalButton(
                            text = "Get Sarvam Key",
                            onClick = {
                                try { uriHandler.openUri("https://dashboard.sarvam.ai") } catch (_: Exception) {}
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MystTonalButton(
                        text = "← Back",
                        onClick = { step = 1 },
                        modifier = Modifier.weight(0.4f)
                    )
                    MystGradientButton(
                        text = if (keys.isNotEmpty()) "Finish Setup →" else "Continue Without Key",
                        onClick = onComplete,
                        modifier = Modifier.weight(0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StepIndicatorChip(
    number: String,
    label: String,
    active: Boolean,
    done: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            done -> MaterialTheme.colorScheme.tertiary
                            active -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                } else {
                    Text(
                        text = number,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ProviderCountPill(
    name: String,
    count: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(
                text = name,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$count ready",
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
