package com.mystx.app.service

import android.content.Context
import android.content.SharedPreferences
import com.mystx.app.R
import com.mystx.app.api.ApiClientUtils
import com.mystx.app.api.ApiError
import com.mystx.app.api.ApiException
import com.mystx.app.api.GeminiClient
import com.mystx.app.api.OpenAICompatibleClient
import com.mystx.app.manager.AccessGateManager
import com.mystx.app.manager.KeyManager
import com.mystx.app.model.GeminiModels
import com.mystx.app.model.PrefKeys
import com.mystx.app.model.ProviderType
import com.mystx.app.provider.GeminiConfig
import com.mystx.app.provider.ProviderConfig
import com.mystx.app.provider.Providers
import com.mystx.app.provider.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

sealed interface CommandOutcome {
    data class Success(val text: String) : CommandOutcome
    /** The model refused in-band. Its answer, not a fault — re-running the same prompt won't help. */
    data object Refusal : CommandOutcome
    /** Nothing was sent and nothing will be until the user changes something. */
    data class Unavailable(val message: String) : CommandOutcome
    /** A request was attempted and failed. Retrying may work. */
    data class Failure(val message: String) : CommandOutcome
}

private const val DEFAULT_TEMPERATURE = 0.5f
private const val STRUCTURED_OUTPUT_RETRY_MS = 86_400_000L // re-try structured output after 24h

private data class ResolvedProvider(
    val config: ProviderConfig,
    val model: String,
    val endpoint: String
)

private val KNOWN_FAILOVER_TYPES = setOf(
    ProviderType.GEMINI,
    ProviderType.GROQ,
    ProviderType.BAI,
    ProviderType.CUSTOM
)

/**
 * Resolves the ordered list of providers to try for a command:
 * always starts with the active provider from [prefs], and when
 * [PrefKeys.FAILOVER_ENABLED] is true, appends any other configured providers
 * listed in [PrefKeys.FAILOVER_ORDER] (skipping duplicates and unconfigured
 * endpoints).
 */
private fun resolveProviderChain(
    prefs: SharedPreferences,
    modelOverride: String? = null
): List<ResolvedProvider> {
    val customEndpoint = prefs.getString(PrefKeys.CUSTOM_ENDPOINT, "") ?: ""
    val primaryConfig = Providers.forType(prefs.getString(PrefKeys.PROVIDER_TYPE, null))
    val primaryDefaultModel = prefs.getString(primaryConfig.modelPrefKey, primaryConfig.defaultModel)
    val primaryModel = if (!modelOverride.isNullOrBlank() && !modelOverride.equals("Global", ignoreCase = true)) {
        primaryConfig.sanitizeModel(modelOverride)
    } else {
        primaryConfig.sanitizeModel(primaryDefaultModel)
    }
    val primaryEndpoint = primaryConfig.resolveEndpoint(customEndpoint)

    val chain = mutableListOf<ResolvedProvider>()
    val seenTypes = mutableSetOf<String>()
    if (primaryConfig.isConfigured(primaryModel, primaryEndpoint)) {
        chain.add(ResolvedProvider(primaryConfig, primaryModel, primaryEndpoint))
        seenTypes.add(primaryConfig.type)
    }

    if (prefs.getBoolean(PrefKeys.FAILOVER_ENABLED, false)) {
        val rawOrder = prefs.getString(PrefKeys.FAILOVER_ORDER, PrefKeys.DEFAULT_FAILOVER_ORDER)
            ?: PrefKeys.DEFAULT_FAILOVER_ORDER
        for (entry in rawOrder.split(",")) {
            val type = entry.trim().lowercase(Locale.ROOT)
            if (type !in KNOWN_FAILOVER_TYPES || type in seenTypes) continue
            val fallbackConfig = Providers.forType(type)
            val fallbackModel = fallbackConfig.sanitizeModel(
                prefs.getString(fallbackConfig.modelPrefKey, fallbackConfig.defaultModel)
            )
            val fallbackEndpoint = fallbackConfig.resolveEndpoint(customEndpoint)
            if (!fallbackConfig.isConfigured(fallbackModel, fallbackEndpoint)) continue
            chain.add(ResolvedProvider(fallbackConfig, fallbackModel, fallbackEndpoint))
            seenTypes.add(fallbackConfig.type)
        }
    }

    return chain
}

private fun buildFailureOutcome(
    context: Context,
    keyManager: KeyManager,
    lastErrorMsg: String?,
    lastErrorWasRateLimit: Boolean,
    lastErrorWasPermission: Boolean,
    lastFailedKey: String?
): CommandOutcome.Failure {
    val waitMs = keyManager.getShortestWaitTimeMs()
    val failedKey = lastFailedKey
    val raw = lastErrorMsg
    return CommandOutcome.Failure(
        when {
            // Prefer the message carrying the actual wait time, but only when the last error
            // really was a rate limit — otherwise an unrelated failure would be masked by some
            // other key that merely happens to be cooling down.
            waitMs != null && (raw == null || lastErrorWasRateLimit) ->
                context.getString(R.string.toast_key_rate_limited, ((waitMs + 999) / 1000).coerceAtLeast(1))
            // Must precede the generic branch: raw is never null once a request was attempted. A
            // 403 is usually the selected model not being available to the project rather than
            // bad keys, so don't send the user off to check keys that are fine.
            lastErrorWasPermission -> context.getString(R.string.error_no_model_access)
            raw != null -> {
                val mapped = ErrorMessages.map(raw)
                if (mapped == R.string.error_invalid_key && failedKey != null && keyManager.getKeys().size > 1) {
                    context.getString(R.string.error_invalid_key_with_hint, "••••" + failedKey.takeLast(4))
                } else {
                    context.getString(mapped)
                }
            }
            keyManager.getKeys().isEmpty() -> context.getString(R.string.toast_no_keys)
            else -> context.getString(R.string.toast_all_keys_invalid)
        }
    )
}

/**
 * Everything a trigger command does between "user asked" and "text came back": provider
 * resolution, key rotation, rate-limit benching, cross-provider failover and error mapping.
 * Both entry points call this — the accessibility service for a typed `?trigger`, the
 * text-selection sheet for a tapped one — so a fix to the request policy lands in both at once.
 *
 * Knows nothing about how the result is delivered: no nodes, no toasts, no UI state. Suspends
 * on the caller's dispatcher and reads disk (prefs, Keystore), so call it off the main thread.
 *
 * @param onFirstAttempt run just before the first request actually goes out — after it is known
 *   that a usable key exists. The service starts its inline spinner here.
 */
suspend fun runTextCommand(
    context: Context,
    keyManager: KeyManager,
    geminiClient: GeminiClient,
    openAIClient: OpenAICompatibleClient,
    prompt: String,
    text: String,
    modelOverride: String? = null,
    temperatureOverride: Float? = null,
    onFirstAttempt: () -> Unit = {}
): CommandOutcome {
    // keys_keystore_error rather than a "reinstall" message: the usual cause is the Keystore key
    // being invalidated by a lock-screen change, where re-adding the keys is enough.
    if (!AccessGateManager.hasAiAccess(context)) {
        AccessGateManager.launchUnlockPrompt(context)
        return CommandOutcome.Unavailable("Watch a short video to unlock 6 hours of AI (or Go Pro).")
    }
    if (!keyManager.keystoreAvailable) {
        return CommandOutcome.Unavailable(context.getString(R.string.keys_keystore_error))
    }

    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val providerChain = resolveProviderChain(prefs, modelOverride)
    if (providerChain.isEmpty()) {
        return CommandOutcome.Unavailable(context.getString(R.string.toast_custom_not_configured))
    }

    val defaultTemp = prefs.getFloat(PrefKeys.TEMPERATURE, DEFAULT_TEMPERATURE)
    val temperature = (temperatureOverride ?: defaultTemp).toDouble()
    val useStructuredOutput = System.currentTimeMillis() -
        prefs.getLong(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT, 0L) > STRUCTURED_OUTPUT_RETRY_MS

    var lastErrorMsg: String? = null
    var lastErrorWasRateLimit = false
    var lastErrorWasPermission = false
    var lastFailedKey: String? = null
    var started = false
    val hasFailover = providerChain.size > 1

    for (resolved in providerChain) {
        val provider = resolved.config
        val model = resolved.model
        val endpoint = resolved.endpoint

        val tried = mutableSetOf<String>()
        var shouldFailover = true

        // One attempt per configured key; getNextKey skips the ones already tried, plus any that are
        // benched for rate limiting or known-invalid. Keyless local Custom endpoints run once with "".
        val configuredKeys = keyManager.getKeys()
        val keylessCustom = provider.type == ProviderType.CUSTOM && configuredKeys.isEmpty()
        val maxAttempts = configuredKeys.size.coerceAtLeast(1)
        while (tried.size < maxAttempts) {
            currentCoroutineContext().ensureActive()
            val key = if (keylessCustom) {
                if (tried.isNotEmpty()) break
                ""
            } else {
                keyManager.getNextKey(tried) ?: break
            }
            tried.add(key)
            if (!started) {
                started = true
                onFirstAttempt()
            }

            val result = try {
                when (provider.transport) {
                    Transport.OPENAI_COMPAT -> openAIClient.generate(
                        prompt, text, key, model, temperature, endpoint,
                        useJsonObjectMode = provider.useJsonObjectMode(useStructuredOutput),
                        extraParams = provider.reasoningParams(model)
                    )
                    Transport.GEMINI_NATIVE -> geminiClient.generate(
                        prompt, text, key, model, temperature, useStructuredOutput,
                        thinkingLevel = provider.thinkingLevel(model)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            }

            result.onSuccess { generated ->
                if (ApiClientUtils.isModelRefusal(generated.text)) return CommandOutcome.Refusal
                if (generated.structuredOutputFailed) {
                    prefs.edit()
                        .putLong(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT, System.currentTimeMillis())
                        .apply()
                }
                // Keep the truncation warning localized and shared by both entry points rather than
                // leaving callers to duplicate it (or clients to inject an English-only string).
                val outputText = if (generated.truncated) {
                    generated.text + "\n\n" + context.getString(R.string.note_response_truncated)
                } else {
                    generated.text
                }
                return CommandOutcome.Success(outputText)
            }

            val error = result.exceptionOrNull()
            val msg = error?.message ?: ""
            lastErrorMsg = msg
            when (val apiError = (error as? ApiException)?.apiError) {
                is ApiError.RateLimit -> {
                    lastErrorWasRateLimit = true
                    lastErrorWasPermission = false
                    if (key.isNotEmpty()) {
                        keyManager.reportRateLimit(key, apiError.retryAfterSeconds?.toLong() ?: 60)
                    }
                }
                is ApiError.RequestTooLarge -> {
                    // Per-key/org TPM budget exceeded — rotate to the next key without countdown.
                    lastErrorWasRateLimit = false
                    lastErrorWasPermission = false
                }
                is ApiError.InvalidKey -> {
                    lastErrorWasRateLimit = false
                    // Server-side sign-in failures (Ollama Cloud) are not the key's fault:
                    // don't bench it, don't record it as a failed key, and don't let an
                    // earlier iteration's permission verdict override the sign-in message.
                    if (msg.contains(ApiClientUtils.SIGNIN_REQUIRED_MARKER)) {
                        lastFailedKey = null
                        lastErrorWasPermission = false
                    } else {
                        lastFailedKey = key.ifEmpty { null }
                        // Distinguish "this key is bad" from "this key may not use this model" (both
                        // arrive as 401/403) so the final message names the right fix.
                        val m = msg.lowercase(Locale.ROOT)
                        lastErrorWasPermission = m.contains("permission") ||
                            m.contains("does not have access") || m.contains("not been used in project")
                        // Never bench the last remaining key, and don't bench keys across providers
                        // when failover is active since a key invalid for one provider may belong to another.
                        if (!hasFailover && key.isNotEmpty() && keyManager.getKeys().size > 1) {
                            keyManager.markInvalid(key)
                        }
                    }
                }
                // 5xx — try the next key (and fail over if all keys fail).
                is ApiError.ServerError -> {
                    lastErrorWasRateLimit = false
                    lastErrorWasPermission = false
                }
                else -> {
                    // Non-retryable error. Clear flags so an earlier 429 or 403 does not mask it.
                    lastErrorWasRateLimit = false
                    lastErrorWasPermission = false
                    shouldFailover = false
                    break
                }
            }
        }

        if (!shouldFailover) break
    }

    return buildFailureOutcome(
        context = context,
        keyManager = keyManager,
        lastErrorMsg = lastErrorMsg,
        lastErrorWasRateLimit = lastErrorWasRateLimit,
        lastErrorWasPermission = lastErrorWasPermission,
        lastFailedKey = lastFailedKey
    )
}

/**
 * Streaming variant of [runTextCommand] using SSE with the same key rotation
 * and cross-provider failover policy.
 */
suspend fun runTextCommandStream(
    context: Context,
    prefs: SharedPreferences,
    keyManager: KeyManager,
    geminiClient: GeminiClient,
    openAIClient: OpenAICompatibleClient,
    prompt: String,
    text: String,
    temperature: Float = 0.7f,
    maxOutputTokens: Int = 2048,
    onChunk: (String) -> Unit
): CommandOutcome {
    if (!AccessGateManager.hasAiAccess(context)) {
        AccessGateManager.launchUnlockPrompt(context)
        return CommandOutcome.Unavailable("Watch a short video to unlock 6 hours of AI (or Go Pro).")
    }
    if (!keyManager.keystoreAvailable) {
        return CommandOutcome.Unavailable(context.getString(R.string.keys_keystore_error))
    }

    val providerChain = resolveProviderChain(prefs)
    if (providerChain.isEmpty()) {
        return CommandOutcome.Unavailable(context.getString(R.string.toast_custom_not_configured))
    }

    var lastErrorMsg: String? = null
    var lastErrorWasRateLimit = false
    var lastErrorWasPermission = false
    var lastFailedKey: String? = null
    val hasFailover = providerChain.size > 1

    for (resolved in providerChain) {
        val provider = resolved.config
        val model = resolved.model
        val endpoint = resolved.endpoint

        val tried = mutableSetOf<String>()
        var shouldFailover = true

        val configuredKeys = keyManager.getKeys()
        val keylessCustom = provider.type == ProviderType.CUSTOM && configuredKeys.isEmpty()
        val maxAttempts = configuredKeys.size.coerceAtLeast(1)
        while (tried.size < maxAttempts) {
            currentCoroutineContext().ensureActive()
            val key = if (keylessCustom) {
                if (tried.isNotEmpty()) break
                ""
            } else {
                keyManager.getNextKey(tried) ?: break
            }
            tried.add(key)

            var emittedAnyChunk = false
            val trackedOnChunk: (String) -> Unit = { delta ->
                emittedAnyChunk = true
                onChunk(delta)
            }

            val result = try {
                when (provider.transport) {
                    Transport.OPENAI_COMPAT -> openAIClient.generateTextStream(
                        endpoint = endpoint,
                        apiKey = key,
                        model = model,
                        prompt = prompt,
                        text = text,
                        temperature = temperature,
                        maxOutputTokens = maxOutputTokens,
                        onChunk = trackedOnChunk
                    )
                    Transport.GEMINI_NATIVE -> geminiClient.generateTextStream(
                        apiKey = key,
                        model = model,
                        prompt = prompt,
                        text = text,
                        temperature = temperature,
                        maxOutputTokens = maxOutputTokens,
                        onChunk = trackedOnChunk
                    )
                }
            } catch (e: CancellationException) {
                throw e
            }

            result.onSuccess { generated ->
                if (ApiClientUtils.isModelRefusal(generated.text)) return CommandOutcome.Refusal
                val outputText = if (generated.truncated) {
                    generated.text + "\n\n" + context.getString(R.string.note_response_truncated)
                } else {
                    generated.text
                }
                return CommandOutcome.Success(outputText)
            }

            val error = result.exceptionOrNull()
            val msg = error?.message ?: ""
            lastErrorMsg = msg
            if (emittedAnyChunk) {
                // Partial tokens were already delivered to the caller; do not concatenate another attempt.
                lastErrorWasRateLimit = false
                lastErrorWasPermission = false
                shouldFailover = false
                break
            }

            when (val apiError = (error as? ApiException)?.apiError) {
                is ApiError.RateLimit -> {
                    lastErrorWasRateLimit = true
                    lastErrorWasPermission = false
                    if (key.isNotEmpty()) {
                        keyManager.reportRateLimit(key, apiError.retryAfterSeconds?.toLong() ?: 60)
                    }
                }
                is ApiError.RequestTooLarge -> {
                    lastErrorWasRateLimit = false
                    lastErrorWasPermission = false
                }
                is ApiError.InvalidKey -> {
                    lastErrorWasRateLimit = false
                    if (msg.contains(ApiClientUtils.SIGNIN_REQUIRED_MARKER)) {
                        lastFailedKey = null
                        lastErrorWasPermission = false
                    } else {
                        lastFailedKey = key.ifEmpty { null }
                        val m = msg.lowercase(Locale.ROOT)
                        lastErrorWasPermission = m.contains("permission") ||
                            m.contains("does not have access") || m.contains("not been used in project")
                        if (!hasFailover && key.isNotEmpty() && keyManager.getKeys().size > 1) {
                            keyManager.markInvalid(key)
                        }
                    }
                }
                is ApiError.ServerError -> {
                    lastErrorWasRateLimit = false
                    lastErrorWasPermission = false
                }
                else -> {
                    lastErrorWasRateLimit = false
                    lastErrorWasPermission = false
                    shouldFailover = false
                    break
                }
            }
        }

        if (!shouldFailover) break
    }

    return buildFailureOutcome(
        context = context,
        keyManager = keyManager,
        lastErrorMsg = lastErrorMsg,
        lastErrorWasRateLimit = lastErrorWasRateLimit,
        lastErrorWasPermission = lastErrorWasPermission,
        lastFailedKey = lastFailedKey
    )
}

/**
 * Runs a Gemini Vision command on raw image bytes, rotating through configured
 * Gemini API keys using the saved Gemini model (or [GeminiModels.DEFAULT]).
 */
suspend fun runImageCommand(
    context: Context,
    prefs: SharedPreferences,
    keyManager: KeyManager,
    geminiClient: GeminiClient,
    prompt: String,
    imageBytes: ByteArray,
    mimeType: String
): CommandOutcome {
    if (!AccessGateManager.hasAiAccess(context)) {
        AccessGateManager.launchUnlockPrompt(context)
        return CommandOutcome.Unavailable("Watch a short video to unlock 6 hours of AI (or Go Pro).")
    }
    if (!keyManager.keystoreAvailable) {
        return CommandOutcome.Unavailable(context.getString(R.string.keys_keystore_error))
    }

    val model = GeminiConfig.sanitizeModel(prefs.getString(PrefKeys.GEMINI_MODEL, GeminiModels.DEFAULT))
    val temperature = prefs.getFloat(PrefKeys.TEMPERATURE, DEFAULT_TEMPERATURE)

    var lastErrorMsg: String? = null
    var lastErrorWasRateLimit = false
    var lastErrorWasPermission = false
    var lastFailedKey: String? = null
    val tried = mutableSetOf<String>()

    val configuredKeys = keyManager.getKeys()
    val maxAttempts = configuredKeys.size.coerceAtLeast(1)
    while (tried.size < maxAttempts) {
        currentCoroutineContext().ensureActive()
        val key = keyManager.getNextKey(tried) ?: break
        tried.add(key)

        val result = try {
            geminiClient.generateFromImage(
                apiKey = key,
                model = model,
                prompt = prompt,
                imageBytes = imageBytes,
                mimeType = mimeType,
                temperature = temperature
            )
        } catch (e: CancellationException) {
            throw e
        }

        result.onSuccess { generated ->
            if (ApiClientUtils.isModelRefusal(generated.text)) return CommandOutcome.Refusal
            val outputText = if (generated.truncated) {
                generated.text + "\n\n" + context.getString(R.string.note_response_truncated)
            } else {
                generated.text
            }
            return CommandOutcome.Success(outputText)
        }

        val error = result.exceptionOrNull()
        val msg = error?.message ?: ""
        lastErrorMsg = msg
        when (val apiError = (error as? ApiException)?.apiError) {
            is ApiError.RateLimit -> {
                lastErrorWasRateLimit = true
                lastErrorWasPermission = false
                if (key.isNotEmpty()) {
                    keyManager.reportRateLimit(key, apiError.retryAfterSeconds?.toLong() ?: 60)
                }
            }
            is ApiError.RequestTooLarge -> {
                lastErrorWasRateLimit = false
                lastErrorWasPermission = false
            }
            is ApiError.InvalidKey -> {
                lastErrorWasRateLimit = false
                lastFailedKey = key.ifEmpty { null }
                val m = msg.lowercase(Locale.ROOT)
                lastErrorWasPermission = m.contains("permission") ||
                    m.contains("does not have access") || m.contains("not been used in project")
                if (key.isNotEmpty() && keyManager.getKeys().size > 1) {
                    keyManager.markInvalid(key)
                }
            }
            is ApiError.ServerError -> {
                lastErrorWasRateLimit = false
                lastErrorWasPermission = false
            }
            else -> {
                lastErrorWasRateLimit = false
                lastErrorWasPermission = false
                break
            }
        }
    }

    return buildFailureOutcome(
        context = context,
        keyManager = keyManager,
        lastErrorMsg = lastErrorMsg,
        lastErrorWasRateLimit = lastErrorWasRateLimit,
        lastErrorWasPermission = lastErrorWasPermission,
        lastFailedKey = lastFailedKey
    )
}
