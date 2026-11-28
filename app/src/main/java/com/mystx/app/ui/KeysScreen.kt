package com.mystx.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mystx.app.R
import com.mystx.app.api.ApiClientUtils
import com.mystx.app.api.GeminiClient
import com.mystx.app.api.OpenAICompatibleClient
import com.mystx.app.manager.KeyManager
import com.mystx.app.model.PrefKeys
import com.mystx.app.model.ProviderType
import com.mystx.app.provider.BaiConfig
import com.mystx.app.provider.GroqConfig
import com.mystx.app.ui.components.MystCard
import com.mystx.app.ui.components.MystDialog
import com.mystx.app.ui.components.MystEmptyState
import com.mystx.app.ui.components.MystGradientButton
import com.mystx.app.ui.components.MystItemCard
import com.mystx.app.ui.components.MystTextField
import com.mystx.app.ui.components.MystTonalButton
import com.mystx.app.ui.components.ScreenTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun getProviderHint(key: String): String {
    return when {
        key.startsWith("AIza") -> "Gemini"
        key.startsWith("gsk_") -> "Groq"
        key.startsWith("sk-") -> "OpenAI / Custom"
        else -> "Custom API"
    }
}

@Composable
fun KeysScreen(keyManager: KeyManager, prefs: SharedPreferences) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current
    var keys by remember { mutableStateOf<List<String>>(emptyList()) }
    var keyToDelete by remember { mutableStateOf<String?>(null) }
    var newKey by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val geminiClient = remember { GeminiClient() }
    val openAIClient = remember { OpenAICompatibleClient() }

    LaunchedEffect(Unit) {
        keys = withContext(Dispatchers.IO) { keyManager.getKeys() }
    }

    val validAddedMsg = stringResource(R.string.keys_valid_added)
    val alreadyAddedMsg = stringResource(R.string.keys_already_added)
    val validationFailedMsg = stringResource(R.string.keys_validation_failed)
    val keystoreErrorMsg = stringResource(R.string.keys_keystore_error)
    val customEndpointRequiredMsg = stringResource(R.string.keys_custom_endpoint_required)
    val signinRequiredMsg = stringResource(R.string.error_provider_auth_required)
    val endpointNeedsV1Msg = stringResource(R.string.keys_endpoint_needs_v1)

    val geminiTotal = keys.count { KeyManager.detectProviderType(it) == ProviderType.GEMINI }
    val geminiReady = keys.count { KeyManager.detectProviderType(it) == ProviderType.GEMINI && keyManager.getKeyHealth(it).isReady }
    val groqTotal = keys.count { KeyManager.detectProviderType(it) == ProviderType.GROQ }
    val groqReady = keys.count { KeyManager.detectProviderType(it) == ProviderType.GROQ && keyManager.getKeyHealth(it).isReady }
    val otherTotal = keys.size - geminiTotal - groqTotal

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { }
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ScreenTitle(stringResource(R.string.keys_title))

        if (!keyManager.keystoreAvailable) {
            MystCard {
                Text(
                    text = keystoreErrorMsg,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            }
        }

        // Provider Availability & Load Balancing Summary
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ProviderAvailabilityCard(
                label = "Gemini",
                ready = geminiReady,
                total = geminiTotal,
                modifier = Modifier.weight(1f)
            )
            ProviderAvailabilityCard(
                label = "Groq Backup",
                ready = groqReady,
                total = groqTotal,
                modifier = Modifier.weight(1f)
            )
            if (otherTotal > 0) {
                ProviderAvailabilityCard(
                    label = "Custom",
                    ready = otherTotal,
                    total = otherTotal,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        MystCard {
            MystTextField(
                value = newKey,
                onValueChange = { if (it.length <= 256) newKey = it },
                placeholder = { Text("Paste Gemini (AIza...) or Groq (gsk_...) key", fontSize = 13.sp) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(modifier = Modifier.height(10.dp))
            MystGradientButton(
                text = if (isTesting) stringResource(R.string.keys_testing) else stringResource(R.string.keys_add_key),
                onClick = {
                    if (newKey.isNotBlank()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isTesting = true
                        testResult = null
                        scope.launch {
                            val trimmedKey = newKey.trim()
                            if (withContext(Dispatchers.IO) { keyManager.getKeys() }.contains(trimmedKey)) {
                                isTesting = false
                                withContext(Dispatchers.IO) { keyManager.clearMarks(trimmedKey) }
                                testResult = alreadyAddedMsg
                                testSuccess = false
                                return@launch
                            }
                            // Auto-detect provider from key prefix so Gemini + Groq keys can be added freely
                            val detected = KeyManager.detectProviderType(trimmedKey)
                            val activeProvider = ProviderType.sanitize(prefs.getString(PrefKeys.PROVIDER_TYPE, null))
                            val customEndpoint = (prefs.getString(PrefKeys.CUSTOM_ENDPOINT, "") ?: "").trim()
                            val result = when {
                                detected == ProviderType.GEMINI -> geminiClient.validateKey(trimmedKey)
                                detected == ProviderType.GROQ -> openAIClient.validateKey(trimmedKey, GroqConfig.ENDPOINT)
                                activeProvider == ProviderType.CUSTOM && customEndpoint.isBlank() -> {
                                    isTesting = false
                                    testResult = customEndpointRequiredMsg
                                    testSuccess = false
                                    return@launch
                                }
                                activeProvider == ProviderType.BAI -> openAIClient.validateKey(trimmedKey, BaiConfig.ENDPOINT)
                                activeProvider == ProviderType.CUSTOM -> openAIClient.validateKey(trimmedKey, customEndpoint)
                                else -> geminiClient.validateKey(trimmedKey)
                            }
                            isTesting = false
                            if (result.isSuccess) {
                                if (!withContext(Dispatchers.IO) { keyManager.addKey(trimmedKey) }) {
                                    testResult = keystoreErrorMsg
                                    testSuccess = false
                                    return@launch
                                }
                                keys = withContext(Dispatchers.IO) { keyManager.getKeys() }
                                newKey = ""
                                testResult = validAddedMsg
                                testSuccess = true
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                            } else {
                                val raw = result.exceptionOrNull()?.message ?: ""
                                testResult = when {
                                    raw.contains(ApiClientUtils.SIGNIN_REQUIRED_MARKER) -> signinRequiredMsg
                                    raw.contains(ApiClientUtils.NEEDS_V1_MARKER) -> endpointNeedsV1Msg
                                    else -> ApiClientUtils.redactSecrets(raw).ifEmpty { validationFailedMsg }
                                }
                                testSuccess = false
                            }
                        }
                    }
                },
                enabled = newKey.isNotBlank() && !isTesting && keyManager.keystoreAvailable,
                modifier = Modifier.fillMaxWidth()
            )
            if (testResult != null) {
                Text(
                    text = testResult!!,
                    color = if (testSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MystTonalButton(
                    text = "Get Gemini Key",
                    onClick = { try { uriHandler.openUri("https://aistudio.google.com/api-keys") } catch (_: Exception) {} },
                    modifier = Modifier.weight(1f)
                )
                MystTonalButton(
                    text = "Get Groq Key",
                    onClick = { try { uriHandler.openUri("https://console.groq.com/keys") } catch (_: Exception) {} },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Auto-Rotation: Requests split across all Gemini keys. If all fail or hit quota, Mystx switches to your available Groq key automatically.",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (keys.isNotEmpty()) {
            MystCard(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    itemsIndexed(keys, key = { index, k -> "$index-${k.hashCode()}" }) { _, key ->
                        val health = keyManager.getKeyHealth(key)
                        val badgeText = when {
                            health.isReady -> "Ready"
                            health.isInvalid -> "Invalid"
                            else -> "Wait ${health.cooldownSeconds}s"
                        }
                        val badgeColor = when {
                            health.isReady -> MaterialTheme.colorScheme.tertiary
                            health.isInvalid -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                        MystItemCard {
                            Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "••••" + key.takeLast(4),
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = badgeText,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = badgeColor,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(badgeColor.copy(alpha = 0.14f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = getProviderHint(key),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = stringResource(R.string.delete_confirm_button),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable(
                                    interactionSource = null,
                                    indication = null
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    keyToDelete = key
                                }
                            )
                        }
                    }
                }
            }
        } else {
            MystCard(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    MystEmptyState(
                        icon = Icons.Default.Lock,
                        message = stringResource(R.string.keys_empty)
                    )
                }
            }
        }
    }

    keyToDelete?.let { keyValue ->
        MystDialog(
            title = stringResource(R.string.delete_confirm_key_title),
            message = stringResource(R.string.delete_confirm_message),
            confirmLabel = stringResource(R.string.delete_confirm_button),
            dismissLabel = stringResource(R.string.commands_cancel),
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                keyToDelete = null
                scope.launch {
                    val removed = withContext(Dispatchers.IO) { keyManager.removeKey(keyValue) }
                    if (removed) {
                        keys = withContext(Dispatchers.IO) { keyManager.getKeys() }
                    } else {
                        testResult = keystoreErrorMsg
                        testSuccess = false
                    }
                }
            },
            onDismissRequest = { keyToDelete = null }
        )
    }
}

@Composable
private fun ProviderAvailabilityCard(
    label: String,
    ready: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                text = label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$ready/$total ready",
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (ready > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}