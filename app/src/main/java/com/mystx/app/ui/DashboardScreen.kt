package com.mystx.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.mystx.app.BuildConfig
import com.mystx.app.MystxApp
import com.mystx.app.R
import com.mystx.app.api.UpdateChecker
import com.mystx.app.manager.AccessGateManager
import com.mystx.app.manager.CommandManager
import com.mystx.app.manager.KeyManager
import com.mystx.app.manager.StatsManager
import com.mystx.app.model.KeyboardStyle
import com.mystx.app.model.PrefKeys
import com.mystx.app.ui.components.MystCard
import com.mystx.app.ui.components.MystCardShape
import com.mystx.app.ui.components.MystDialog
import com.mystx.app.ui.components.MystDivider
import com.mystx.app.ui.components.MystGradientButton
import com.mystx.app.ui.components.MystPill
import com.mystx.app.ui.components.MystStatTile
import com.mystx.app.ui.components.MystTonalButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

private fun isBatteryUnrestricted(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        true
    }
}

private fun openBatteryUnrestrictedSettings(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
        return
    } catch (_: Exception) {
    }
    try {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        return
    } catch (_: Exception) {
    }
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        )
    } catch (_: Exception) {
    }
}

private fun isXiaomiOrPoco(): Boolean {
    val m = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
    return m.contains("xiaomi") || m.contains("poco") || m.contains("redmi")
}

private fun isMystxKeyboardEnabled(context: Context): Boolean {
    return try {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        imm.enabledInputMethodList.any { it.packageName == context.packageName }
    } catch (_: Exception) {
        false
    }
}

private fun checkServiceEnabled(context: Context): Boolean {
    return try {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == context.packageName
        }
    } catch (_: Exception) {
        false
    }
}

@SuppressLint("SoonBlockedPrivateApi")
private fun isServiceCrashed(context: Context): Boolean {
    return try {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val field = AccessibilityServiceInfo::class.java.getDeclaredField("crashed")
        am.getInstalledAccessibilityServiceList().any {
            try {
                it.resolveInfo.serviceInfo.packageName == context.packageName && field.getBoolean(it)
            } catch (_: Exception) {
                false
            }
        }
    } catch (_: Exception) {
        false
    }
}

private fun readCrashMarker(context: Context): Long =
    try {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getLong(MystxApp.PREF_SERVICE_DIED_AT, 0L)
    } catch (_: Exception) {
        0L
    }

private fun clearCrashMarker(context: Context) {
    try {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().remove(MystxApp.PREF_SERVICE_DIED_AT).apply()
    } catch (_: Exception) {
    }
}

@Composable
fun DashboardScreen(
    keyManager: KeyManager,
    commandManager: CommandManager,
    statsManager: StatsManager,
    onOpenPdf: (Uri) -> Unit = {},
    onOpenPro: () -> Unit = {},
    onOpenGuide: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var isServiceEnabled by remember { mutableStateOf(checkServiceEnabled(context)) }
    var keyCount by remember { mutableIntStateOf(0) }
    var showKilledBanner by remember { mutableStateOf(false) }
    var batteryUnrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    var isKeyboardEnabled by remember { mutableStateOf(isMystxKeyboardEnabled(context)) }
    val settingsPrefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var selectedKbStyle by remember {
        mutableStateOf(
            KeyboardStyle.fromKey(settingsPrefs.getString(PrefKeys.KEYBOARD_STYLE, PrefKeys.DEFAULT_KEYBOARD_STYLE))
        )
    }

    var monthlyRequests by remember { mutableIntStateOf(statsManager.monthlyRequests) }
    var favoriteCommand by remember { mutableStateOf(statsManager.favoriteCommand) }
    var dailyCounts by remember { mutableStateOf(statsManager.dailyCounts()) }

    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    val uriHandler = LocalUriHandler.current
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                onOpenPdf(uri)
            }
        }
    )

    LaunchedEffect(Unit) {
        if (BuildConfig.ENABLE_UPDATER) {
            val info = UpdateChecker.checkForUpdates(BuildConfig.VERSION_NAME)
            if (info != null) {
                updateInfo = info
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(context) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val listener = AccessibilityManager.AccessibilityStateChangeListener {
            isServiceEnabled = checkServiceEnabled(context)
        }
        am?.addAccessibilityStateChangeListener(listener)
        onDispose { am?.removeAccessibilityStateChangeListener(listener) }
    }

    LaunchedEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val (newEnabled, newKeyCount, killed) = withContext(Dispatchers.IO) {
                Triple(
                    checkServiceEnabled(context),
                    keyManager.getKeys().size,
                    readCrashMarker(context) > 0L || isServiceCrashed(context)
                )
            }
            isServiceEnabled = newEnabled
            keyCount = newKeyCount
            monthlyRequests = statsManager.monthlyRequests
            favoriteCommand = statsManager.favoriteCommand
            dailyCounts = statsManager.dailyCounts()
            showKilledBanner = killed
            batteryUnrestricted = isBatteryUnrestricted(context)
            isKeyboardEnabled = isMystxKeyboardEnabled(context)
        }
    }

    val noData = stringResource(R.string.dashboard_no_data)
    val isProUser = AccessGateManager.isPro(context)
    val passRemaining = AccessGateManager.remainingPassFormatted(context)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Clean aligned header row: brand title + compact Guide chip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Mystx",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenGuide() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.HelpOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Guide",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Service + API Keys unified status card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MystCardShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                if (isServiceEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.outline
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isServiceEnabled) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
                                    else MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isServiceEnabled) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.error
                                    )
                            )
                        }
                        Column {
                            Text(
                                text = if (isServiceEnabled) "Service Active" else "Service Off",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (keyCount > 0) {
                                    stringResource(R.string.dashboard_keys_configured, keyCount)
                                } else {
                                    stringResource(R.string.dashboard_add_key_hint)
                                },
                                fontSize = 12.sp,
                                color = if (keyCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (!isServiceEnabled) {
                        MystGradientButton(
                            text = stringResource(R.string.service_enable),
                            onClick = { showAccessibilityDisclosure = true }
                        )
                    } else {
                        MystPill(text = "READY")
                    }
                }

                if (!isServiceEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Spacer(modifier = Modifier.height(10.dp))
                    MystDivider()
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Restricted Settings blocked? Open App Info → Allow restricted settings.",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp)
                        )
                        MystTonalButton(
                            text = "App Info",
                            onClick = {
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    )
                                    context.startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                } catch (_: Exception) {
                                }
                            }
                        )
                    }
                }
            }
        }

        // Mystx AI Keyboard Card (Zero Banking/BHIM/Paytm alerts — keeps user's own keyboard via Auto-Back)
        MystCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Mystx AI Keyboard",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        MystPill(text = if (isKeyboardEnabled) "ENABLED" else "BANKING SAFE")
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = if (isKeyboardEnabled) {
                            "Switch via globe icon, tap any command, and Auto-Back returns to your own keyboard."
                        } else {
                            "Works with BHIM/Paytm (0 alerts). Keep your own keyboard UI & switch in 1 tap."
                        },
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            // Selectable OEM Keyboard Skin Picker (Pixel, Samsung One UI, HyperOS, Vivo, Realme)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                KeyboardStyle.entries.forEach { skin ->
                    val active = selectedKbStyle == skin
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(
                            1.dp,
                            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                selectedKbStyle = skin
                                settingsPrefs.edit().putString(PrefKeys.KEYBOARD_STYLE, skin.key).apply()
                            }
                    ) {
                        Text(
                            text = skin.label,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MystTonalButton(
                    text = if (isKeyboardEnabled) "Keyboard Settings" else "Enable Keyboard",
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        } catch (_: Exception) {
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                if (isKeyboardEnabled) {
                    MystGradientButton(
                        text = "Switch Now",
                        onClick = {
                            try {
                                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                imm?.showInputMethodPicker()
                            } catch (_: Exception) {
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Battery / HyperOS Keep-Alive Card (auto-hides once unrestricted)
        if (!batteryUnrestricted) {
            MystCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            text = if (isXiaomiOrPoco()) "HyperOS / Poco Keep-Alive" else "Prevent Background Stop",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isXiaomiOrPoco()) {
                                "Set Battery to No restrictions so HyperOS doesn't stop Mystx Accessibility."
                            } else {
                                "Disable battery restriction so Mystx stays active in the background."
                            },
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    MystTonalButton(
                        text = "Allow",
                        onClick = { openBatteryUnrestrictedSettings(context) }
                    )
                }
            }
        }

        // Killed service warning banner (only when needed)
        if (showKilledBanner) {
            MystCard {
                Text(
                    text = stringResource(R.string.dashboard_service_killed_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.dashboard_service_killed_message),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MystGradientButton(
                        text = stringResource(R.string.service_enable),
                        onClick = {
                            clearCrashMarker(context)
                            showKilledBanner = false
                            showAccessibilityDisclosure = true
                        },
                        modifier = Modifier.weight(1f)
                    )
                    MystTonalButton(
                        text = stringResource(R.string.dashboard_service_killed_dismiss),
                        onClick = {
                            clearCrashMarker(context)
                            showKilledBanner = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // AI Access Card — compact status + side-by-side action buttons
        MystCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = if (isProUser) "Mystx Pro" else "AI Access",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when {
                            isProUser -> "Unlimited ad-free access active"
                            passRemaining != null -> "6h free pass active · $passRemaining"
                            else -> "Watch 1 video for 6h free, or unlock Pro"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MystPill(
                    text = when {
                        isProUser -> "PRO"
                        passRemaining != null -> "6H PASS"
                        else -> "FREE"
                    }
                )
            }
            if (!isProUser) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MystTonalButton(
                        text = if (passRemaining != null) "Extend 6h" else "Watch 6h Ad",
                        onClick = { AccessGateManager.launchUnlockPrompt(context) },
                        modifier = Modifier.weight(1f)
                    )
                    MystGradientButton(
                        text = "Go Pro · ₹40",
                        onClick = onOpenPro,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Sleek clickable PDF Reader row (no bloated giant button)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MystCardShape)
                .clickable {
                    try {
                        pdfPickerLauncher.launch(arrayOf("application/pdf"))
                    } catch (_: ActivityNotFoundException) {
                    } catch (_: Exception) {
                    }
                },
            shape = MystCardShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PDF Reader",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Open a PDF and highlight text to ask AI",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open PDF",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Compact 2-column metrics
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MystStatTile(
                value = "$monthlyRequests",
                label = stringResource(R.string.dashboard_monthly_requests),
                modifier = Modifier.weight(1f)
            )
            MystStatTile(
                value = favoriteCommand ?: noData,
                label = stringResource(R.string.dashboard_favorite_command),
                modifier = Modifier.weight(1f)
            )
        }

        // Sleek compact 7-day activity chart with subtle day tracks
        MystCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.dashboard_last_7_days),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${dailyCounts.sumOf { it.second }} runs",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            val maxCount = dailyCounts.maxOfOrNull { it.second } ?: 0
            val dayNameFmt = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
            val dateParseFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                dailyCounts.forEach { (dateStr, count) ->
                    val dayLabel = try {
                        val date = dateParseFmt.parse(dateStr)
                        date?.let { dayNameFmt.format(it).take(3) } ?: "?"
                    } catch (_: Exception) { "?" }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (count > 0) "$count" else "",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            if (count > 0 && maxCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight((count.toFloat() / maxCount).coerceAtLeast(0.12f))
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = dayLabel,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    updateInfo?.let { info ->
        MystDialog(
            title = "Update Available",
            message = "A new version of Mystx (${info.version}) is available. Would you like to download it now?",
            confirmLabel = "Update",
            dismissLabel = "Later",
            onConfirm = {
                try {
                    uriHandler.openUri(info.url)
                } catch (_: ActivityNotFoundException) {
                } catch (_: Exception) {
                }
                updateInfo = null
            },
            onDismissRequest = { updateInfo = null }
        )
    }

    if (showAccessibilityDisclosure) {
        MystDialog(
            title = stringResource(R.string.accessibility_disclosure_title),
            message = stringResource(R.string.accessibility_disclosure_text),
            confirmLabel = stringResource(R.string.accessibility_disclosure_agree),
            dismissLabel = stringResource(R.string.commands_cancel),
            onConfirm = {
                showAccessibilityDisclosure = false
                try {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                } catch (_: Exception) {
                }
            },
            onDismissRequest = { showAccessibilityDisclosure = false }
        )
    }
}
