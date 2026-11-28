package com.mystx.app.api

import com.mystx.app.provider.EndpointValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class OpenAICompatibleClient {

    companion object {
        private val HTTP_PREFIX_REGEX = Regex("^HTTP_\\d+:\\s*")
        private val CLOUD_KEY_PREFIXES = listOf("sk-", "gsk_", "AIza", "xai-", "sk-ant-")
        private const val MAX_STREAM_BYTES = 512 * 1024
    }

    private fun sanitizeKeyForEndpoint(apiKey: String?, endpoint: String): Result<String> {
        val cleanKey = apiKey?.replace("\r", "")?.replace("\n", "")?.trim() ?: ""
        if (cleanKey.isNotEmpty() && endpoint.trim().startsWith("http://", ignoreCase = true)) {
            if (CLOUD_KEY_PREFIXES.any { cleanKey.startsWith(it) }) {
                val msg = "Refusing to send a cloud API key over cleartext http:// endpoint. Use https:// instead."
                return Result.failure(ApiException(ApiError.Other(msg), msg))
            }
        }
        return Result.success(cleanKey)
    }

    suspend fun validateKey(apiKey: String, endpoint: String): Result<String> = withContext(Dispatchers.IO) {
        if (EndpointValidator.validate(endpoint) != EndpointValidator.Error.NONE) {
            return@withContext Result.failure(Exception("Endpoint must be https:// or an http:// private-LAN address"))
        }
        val cleanKey = sanitizeKeyForEndpoint(apiKey, endpoint).getOrElse {
            return@withContext Result.failure(it)
        }
        var connection: HttpURLConnection? = null
        val completionHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) connection?.disconnect()
        }
        try {
            val baseUrl = endpoint.trimEnd('/')
            connection = URL("$baseUrl/models")
                .openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            if (cleanKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $cleanKey")
                if (endpoint.contains("sarvam.ai", ignoreCase = true)) connection.setRequestProperty("api-subscription-key", cleanKey)
            }
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                connection.inputStream?.use { stream ->
                    val buf = ByteArray(1024)
                    while (stream.read(buf) != -1) { /* drain */ }
                }
                Result.success("Valid")
            } else {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val apiMessage = ApiClientUtils.redactSecrets(
                    ApiClientUtils.extractApiErrorMessage(errorBody),
                    cleanKey
                )

                when (responseCode) {
                    429 -> Result.failure(Exception("Rate limited. Please try again later."))
                    401, 403 -> {
                        val signinUrl = ApiClientUtils.extractSigninUrl(errorBody)
                        val detail = when {
                            signinUrl != null || apiMessage.contains("not currently signed in", ignoreCase = true) ->
                                "${ApiClientUtils.SIGNIN_REQUIRED_MARKER}: ${apiMessage.ifEmpty { "server sign-in required" }}"
                            apiMessage.isNotEmpty() -> apiMessage
                            else -> "Invalid API key"
                        }
                        Result.failure(Exception(detail))
                    }
                    404 -> {
                        // Some local servers (Ollama) only serve the OpenAI-compatible API
                        // under /v1; the stored endpoint may just be missing that suffix.
                        connection?.disconnect()
                        connection = null
                        if (!baseUrl.endsWith("/v1") && probeModels("$baseUrl/v1/models", cleanKey)) {
                            Result.failure(Exception(ApiClientUtils.NEEDS_V1_MARKER))
                        } else {
                            val detail = if (apiMessage.isNotEmpty()) apiMessage else "Unexpected error"
                            Result.failure(Exception("Error $responseCode: $detail"))
                        }
                    }
                    else -> {
                        val detail = if (apiMessage.isNotEmpty()) apiMessage else "Unexpected error"
                        Result.failure(Exception("Error $responseCode: $detail"))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val safeMsg = ApiClientUtils.redactSecrets(e.message ?: "Unexpected error", cleanKey)
            Result.failure(Exception(safeMsg))
        } finally {
            completionHandle?.dispose()
            connection?.disconnect()
        }
    }

    /**
     * GETs [url] and reports whether the server answered 2xx. Used by [validateKey]'s
     * and [doGenerate]'s /v1 fallback probe — a 200 here proves the endpoint is reachable.
     */
    private suspend fun probeModels(url: String, cleanKey: String): Boolean {
        var probe: HttpURLConnection? = null
        val completionHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) probe?.disconnect()
        }
        return try {
            probe = URL(url).openConnection() as HttpURLConnection
            probe.instanceFollowRedirects = false
            probe.requestMethod = "GET"
            if (cleanKey.isNotBlank()) {
                probe.setRequestProperty("Authorization", "Bearer $cleanKey")
            }
            probe.connectTimeout = 15_000
            probe.readTimeout = 15_000
            val code = probe.responseCode
            if (code in 200..299) {
                probe.inputStream?.use { stream ->
                    val buf = ByteArray(1024)
                    while (stream.read(buf) != -1) { /* drain */ }
                }
            }
            code in 200..299
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            completionHandle?.dispose()
            probe?.disconnect()
        }
    }

    /**
     * Fetches the model list from a Custom (OpenAI-compatible) endpoint for the Settings
     * model dropdown. Probes the paths local LLM servers actually implement, in coverage
     * order: `/v1/models` (OpenAI/Ollama/LM Studio/vLLM/llama.cpp/LiteLLM), then `/models`,
     * then Ollama's native `/api/tags`, then Open WebUI's `/api/models`. Stops at the first
     * 2xx with a non-empty list.
     *
     *  - 401/403 stops the probe immediately: the server exists and rejected the key, and
     *    every other path on it will reject too. The failure carries the same signin/raw
     *    message handling as [validateKey].
     *  - Connection failures stop the probe too — the endpoint is unreachable, not the path.
     *  - Any 2xx (even an empty list) marks the server reachable: if no path yields model
     *    ids, the result is an empty success list so the UI can show "No models found".
     *    A failure is returned only when every path 404s/5xxes — the server is not serving
     *    any known models path at all.
     *
     * [apiKey] may be null: local servers without a configured key (Ollama, LM Studio,
     * llama.cpp) want no Authorization header at all, and sending a placeholder helps nobody.
     */
    suspend fun fetchModels(apiKey: String?, endpoint: String): Result<List<String>> = withContext(Dispatchers.IO) {
        if (EndpointValidator.validate(endpoint) != EndpointValidator.Error.NONE) {
            return@withContext Result.failure(Exception("Endpoint must be https:// or an http:// private-LAN address"))
        }
        val cleanKey = sanitizeKeyForEndpoint(apiKey, endpoint).getOrElse {
            return@withContext Result.failure(it)
        }
        val baseUrl = endpoint.trimEnd('/')
        var lastDetail: String? = null
        // Set on the first 2xx, whatever the body: a server that answered is reachable even
        // when it lists no models, so "No models found" must win over a 404 seen elsewhere.
        var reachable = false
        for (path in listOf("$baseUrl/v1/models", "$baseUrl/models", "$baseUrl/api/tags", "$baseUrl/api/models")) {
            val probe = try {
                httpGet(path, cleanKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val safeMsg = ApiClientUtils.redactSecrets(e.message ?: "Unexpected error", cleanKey)
                return@withContext Result.failure(Exception(safeMsg))
            }
            when {
                probe.code in 200..299 -> {
                    reachable = true
                    val ids = ApiClientUtils.parseModelIds(probe.body)
                    if (ids.isNotEmpty()) return@withContext Result.success(ids)
                    // Reachable but empty at this path — try the next one.
                }
                probe.code == 401 || probe.code == 403 -> {
                    val apiMessage = ApiClientUtils.redactSecrets(
                        ApiClientUtils.extractApiErrorMessage(probe.body),
                        cleanKey
                    )
                    val signinUrl = ApiClientUtils.extractSigninUrl(probe.body)
                    val detail = when {
                        signinUrl != null || apiMessage.contains("not currently signed in", ignoreCase = true) ->
                            "${ApiClientUtils.SIGNIN_REQUIRED_MARKER}: ${apiMessage.ifEmpty { "server sign-in required" }}"
                        apiMessage.isNotEmpty() -> apiMessage
                        else -> "Invalid API key"
                    }
                    return@withContext Result.failure(Exception(detail))
                }
                else -> {
                    // 404/405/5xx — this path is not served here; remember the detail and
                    // try the next one.
                    val apiMessage = ApiClientUtils.redactSecrets(
                        ApiClientUtils.extractApiErrorMessage(probe.body),
                        cleanKey
                    )
                    lastDetail = if (apiMessage.isNotEmpty()) apiMessage else "Unexpected error"
                }
            }
        }
        if (lastDetail != null && !reachable) Result.failure(Exception(lastDetail))
        else Result.success(emptyList())
    }

    private data class Probe(val code: Int, val body: String)

    /** GETs [url], returning the status code and the (bounded) body on either stream. */
    private suspend fun httpGet(url: String, cleanKey: String): Probe {
        var connection: HttpURLConnection? = null
        val completionHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) connection?.disconnect()
        }
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            if (cleanKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $cleanKey")
                if (url.contains("sarvam.ai", ignoreCase = true)) connection.setRequestProperty("api-subscription-key", cleanKey)
            }
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            val code = connection.responseCode
            val body = if (code in 200..299) ApiClientUtils.readResponseBounded(connection)
            else ApiClientUtils.readErrorBody(connection)
            Probe(code, body)
        } catch (e: CancellationException) {
            throw e
        } finally {
            completionHandle?.dispose()
            connection?.disconnect()
        }
    }

    suspend fun generate(
        prompt: String,
        text: String,
        apiKey: String,
        model: String,
        temperature: Double,
        endpoint: String,
        useJsonObjectMode: Boolean = false,
        extraParams: Map<String, Any> = emptyMap()
    ): Result<GenerateResult> = withContext(Dispatchers.IO) {
        var result = doGenerate(prompt, text, apiKey, model, temperature, endpoint, useJsonObjectMode, extraParams)

        // Retry once for transient network/server errors (with 1.5s backoff)
        if (result.isFailure && result.exceptionOrNull().isTransientNetwork()) {
            kotlinx.coroutines.delay(1500)
            result = doGenerate(prompt, text, apiKey, model, temperature, endpoint, useJsonObjectMode, extraParams)
        }

        val cleaned = stripHttpPrefix(result.map { it.text })
        val meta = result.getOrNull()
        cleaned.map { GenerateResult(it, meta?.structuredOutputFailed == true, meta?.truncated == true) }
    }

    suspend fun generateTextStream(
        endpoint: String,
        apiKey: String,
        model: String,
        prompt: String,
        text: String,
        temperature: Float = 0.7f,
        maxOutputTokens: Int = 2048,
        onChunk: (String) -> Unit
    ): Result<ApiResult> = withContext(Dispatchers.IO) {
        if (EndpointValidator.validate(endpoint) != EndpointValidator.Error.NONE) {
            return@withContext Result.failure(Exception("Endpoint must be https:// or an http:// private-LAN address"))
        }
        val cleanKey = sanitizeKeyForEndpoint(apiKey, endpoint).getOrElse {
            return@withContext Result.failure(it)
        }
        var connection: HttpURLConnection? = null
        val completionHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) connection?.disconnect()
        }
        try {
            val baseUrl = endpoint.trimEnd('/')
            connection = URL("$baseUrl/chat/completions")
                .openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            if (cleanKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $cleanKey")
                if (endpoint.contains("sarvam.ai", ignoreCase = true)) connection.setRequestProperty("api-subscription-key", cleanKey)
            }
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            val boundaryTag = ApiClientUtils.generateBoundaryTag()
            val systemContent = ApiClientUtils.buildSystemPromptPrefix(boundaryTag) + prompt

            val jsonBody = JSONObject().apply {
                val safeModel = model.replace(Regex("[^a-zA-Z0-9._\\-/: ]"), "")
                put("model", safeModel)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemContent)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", ApiClientUtils.wrapUserTextWithTag(text, boundaryTag))
                    })
                })
                put("temperature", temperature.toDouble())
                put("max_tokens", maxOutputTokens)
                put("stream", true)
            }

            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val fullText = StringBuilder()
                val rawBuffer = StringBuilder()
                var sawDataLine = false
                var truncated = false
                var totalBytes = 0

                connection.inputStream.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            currentCoroutineContext().ensureActive()
                            val currentLine = line ?: continue
                            totalBytes += currentLine.toByteArray(Charsets.UTF_8).size + 1
                            if (totalBytes > MAX_STREAM_BYTES) {
                                truncated = true
                                break
                            }
                            val trimmed = currentLine.trim()
                            if (trimmed.startsWith("data:")) {
                                sawDataLine = true
                                val payload = trimmed.removePrefix("data:").trim()
                                if (payload == "[DONE]") break
                                if (payload.isEmpty()) continue

                                val chunkJson = try {
                                    JSONObject(payload)
                                } catch (_: Exception) {
                                    continue
                                }
                                val choices = chunkJson.optJSONArray("choices")
                                val choice = choices?.optJSONObject(0) ?: continue
                                val finishReason = choice.optStringOrNull("finish_reason") ?: ""
                                if (finishReason == "content_filter") {
                                    return@withContext Result.failure(Exception("Response blocked by content filter"))
                                }
                                if (finishReason == "length") {
                                    truncated = true
                                }
                                val delta = choice.optJSONObject("delta")?.optStringOrNull("content")
                                if (!delta.isNullOrEmpty()) {
                                    fullText.append(delta)
                                    onChunk(delta)
                                }
                            } else if (!sawDataLine) {
                                rawBuffer.append(currentLine).append('\n')
                            }
                        }
                    }
                }

                // Fallback for servers that ignore stream=true and return plain JSON.
                if (!sawDataLine) {
                    val rawBody = rawBuffer.toString().trim()
                    if (rawBody.isNotEmpty()) {
                        val jsonResponse = JSONObject(rawBody)
                        val choices = jsonResponse.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val choice = choices.optJSONObject(0)
                                ?: return@withContext Result.failure(Exception("No choices found in response"))
                            val finishReason = choice.optStringOrNull("finish_reason") ?: ""
                            if (finishReason == "content_filter") {
                                return@withContext Result.failure(Exception("Response blocked by content filter"))
                            }
                            if (finishReason == "length") {
                                truncated = true
                            }
                            val message = choice.optJSONObject("message")
                            val refusal = message?.optStringOrNull("refusal")
                            if (!refusal.isNullOrBlank()) {
                                return@withContext Result.failure(Exception("Response refused by model"))
                            }
                            val content = message?.optStringOrNull("content") ?: ""
                            if (content.isNotEmpty()) {
                                fullText.append(content)
                                onChunk(content)
                            }
                        } else {
                            return@withContext Result.failure(Exception("No choices found in response"))
                        }
                    }
                }

                val resultText = fullText.toString().trim()
                if (resultText.isBlank()) {
                    Result.failure(Exception("Model returned empty response"))
                } else {
                    Result.success(ApiResult(text = resultText, truncated = truncated))
                }
            } else {
                mapHttpError(connection, responseCode, baseUrl, cleanKey)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mapException(e, cleanKey)
        } finally {
            completionHandle?.dispose()
            connection?.disconnect()
        }
    }

    private suspend fun mapHttpError(
        connection: HttpURLConnection,
        responseCode: Int,
        baseUrl: String,
        cleanKey: String
    ): Result<ApiResult> = when (responseCode) {
        413 -> {
            val errorBody = ApiClientUtils.readErrorBody(connection)
            val apiMessage = ApiClientUtils.redactSecrets(
                ApiClientUtils.extractApiErrorMessage(errorBody),
                cleanKey
            )
            val detail = if (apiMessage.isNotEmpty()) apiMessage else "Request too large"
            Result.failure(ApiException(ApiError.RequestTooLarge(detail), detail))
        }
        429 -> {
            val retryAfter = connection.getHeaderField("Retry-After")
            val seconds = ApiClientUtils.parseRetryAfterSeconds(retryAfter)
                ?.coerceIn(0L, Int.MAX_VALUE.toLong())
                ?.toInt()
            val msg = if (seconds != null) "Rate limit exceeded, retry after ${seconds}s" else "Rate limit exceeded"
            Result.failure(ApiException(ApiError.RateLimit(msg, seconds), msg))
        }
        400, 422 -> {
            val errorBody = ApiClientUtils.readErrorBody(connection)
            val apiMessage = ApiClientUtils.redactSecrets(
                ApiClientUtils.extractApiErrorMessage(errorBody),
                cleanKey
            )
            val detail = if (apiMessage.isNotEmpty()) apiMessage else "Bad request"
            val providerCode = ApiClientUtils.extractApiErrorCode(errorBody)
            val withCode = if (providerCode.isNotEmpty()) "$detail [$providerCode]" else detail
            Result.failure(ApiException(ApiError.Other(withCode), withCode))
        }
        401, 403 -> {
            val errorBody = ApiClientUtils.readErrorBody(connection)
            val apiMessage = ApiClientUtils.redactSecrets(
                ApiClientUtils.extractApiErrorMessage(errorBody),
                cleanKey
            )
            val signinUrl = ApiClientUtils.extractSigninUrl(errorBody)
            val detail = when {
                signinUrl != null || apiMessage.contains("not currently signed in", ignoreCase = true) ->
                    "${ApiClientUtils.SIGNIN_REQUIRED_MARKER}: ${apiMessage.ifEmpty { "server sign-in required" }}"
                apiMessage.isNotEmpty() -> apiMessage
                else -> "Invalid API key"
            }
            Result.failure(ApiException(ApiError.InvalidKey(detail), detail))
        }
        else -> {
            val errorBody = ApiClientUtils.readErrorBody(connection)
            if (responseCode == 404 && !baseUrl.endsWith("/v1")) {
                connection.disconnect()
                if (probeModels("$baseUrl/v1/models", cleanKey)) {
                    return Result.failure(
                        ApiException(ApiError.Other(ApiClientUtils.NEEDS_V1_MARKER), ApiClientUtils.NEEDS_V1_MARKER)
                    )
                }
            }
            var detail = ApiClientUtils.redactSecrets(
                ApiClientUtils.sanitizeErrorForUser(responseCode, errorBody, "Unexpected error (HTTP $responseCode)"),
                cleanKey
            )
            val providerCode = ApiClientUtils.extractApiErrorCode(errorBody)
            if (responseCode == 404 || providerCode == "model_not_found") {
                detail = "Model not found. $detail"
            }
            val apiError = if (responseCode in 500..599) ApiError.ServerError(detail) else ApiError.Other(detail)
            Result.failure(ApiException(apiError, detail))
        }
    }

    private fun mapException(e: Exception, cleanKey: String): Result<ApiResult> {
        val safeMsg = ApiClientUtils.redactSecrets(e.message ?: "Unknown error", cleanKey)
        val apiError = when (e) {
            is ApiException -> e.apiError
            is SocketTimeoutException, is UnknownHostException, is ConnectException, is java.net.SocketException ->
                ApiError.Network(ApiClientUtils.redactSecrets(e.message ?: "Network error", cleanKey))
            is org.json.JSONException -> ApiError.Other("Invalid response from server")
            else -> ApiError.Other(safeMsg)
        }
        return if (e is ApiException) Result.failure(e) else Result.failure(ApiException(apiError, safeMsg))
    }

    private fun stripHttpPrefix(result: Result<String>): Result<String> {
        if (result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: ""
            val cleaned = msg.replaceFirst(HTTP_PREFIX_REGEX, "")
            if (cleaned != msg) return Result.failure(Exception(cleaned))
        }
        return result
    }

    private suspend fun doGenerate(
        prompt: String,
        text: String,
        apiKey: String,
        model: String,
        temperature: Double,
        endpoint: String,
        withJsonObject: Boolean = false,
        extraParams: Map<String, Any> = emptyMap()
    ): Result<GenerateResult> {
        if (EndpointValidator.validate(endpoint) != EndpointValidator.Error.NONE) {
            return Result.failure(Exception("Endpoint must be https:// or an http:// private-LAN address"))
        }
        val cleanKey = sanitizeKeyForEndpoint(apiKey, endpoint).getOrElse {
            return Result.failure(it)
        }
        var connection: HttpURLConnection? = null
        val completionHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) connection?.disconnect()
        }
        return try {
            val baseUrl = endpoint.trimEnd('/')
            connection = URL("$baseUrl/chat/completions")
                .openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            if (cleanKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $cleanKey")
                if (endpoint.contains("sarvam.ai", ignoreCase = true)) connection.setRequestProperty("api-subscription-key", cleanKey)
            }
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            val boundaryTag = ApiClientUtils.generateBoundaryTag()
            val systemPrefix = ApiClientUtils.buildSystemPromptPrefix(boundaryTag)
            val systemContent = if (withJsonObject) {
                systemPrefix + prompt + " Respond with JSON: {\"text\": \"your result\"}"
            } else {
                systemPrefix + prompt
            }

            val jsonBody = JSONObject().apply {
                val safeModel = model.replace(Regex("[^a-zA-Z0-9._\\-/: ]"), "")
                put("model", safeModel)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemContent)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", ApiClientUtils.wrapUserTextWithTag(text, boundaryTag))
                    })
                })
                put("temperature", temperature)
                if (withJsonObject) {
                    put("response_format", JSONObject().apply {
                        put("type", "json_object")
                    })
                }
                // Extra provider-specific params (e.g. Groq reasoning controls),
                // resolved by the caller from the active provider config. Empty
                // for providers/models that take none, so nothing is sent.
                extraParams.forEach { (k, v) -> put(k, v) }
            }

            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = ApiClientUtils.readResponseBounded(connection)

                val jsonResponse = JSONObject(response)
                val choices = jsonResponse.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val choice = choices.optJSONObject(0)
                        ?: return Result.failure(Exception("No choices found in response"))

                    val finishReason = choice.optStringOrNull("finish_reason") ?: ""
                    if (finishReason == "content_filter") {
                        return Result.failure(Exception("Response blocked by content filter"))
                    }

                    val message = choice.optJSONObject("message")
                    val refusal = message?.optStringOrNull("refusal")
                    if (!refusal.isNullOrBlank()) {
                        return Result.failure(Exception("Response refused by model"))
                    }
                    var resultText = message?.optStringOrNull("content") ?: ""
                    if (resultText.isBlank()) {
                        return Result.failure(Exception("Model returned empty response"))
                    }

                    val isTruncated = finishReason == "length"
                    if (withJsonObject) {
                        val (extracted, parseFailed) = ApiClientUtils.tryExtractStructuredText(resultText)
                        if (extracted != null) {
                            return Result.success(GenerateResult(extracted, truncated = isTruncated))
                        }
                        // Do not fall through with a raw JSON payload — that pasted literal
                        // JSON such as {"text": ""} (parsed, no usable field) or a truncated
                        // object (unparseable but clearly JSON) into the user's text field.
                        if (!parseFailed || resultText.trimStart().startsWith("{")) {
                            return Result.failure(Exception(
                                "Model returned empty response (${ApiClientUtils.STRUCTURED_UNUSABLE_MARKER})"))
                        }
                        // Genuinely not JSON: the model ignored response_format. Use the text,
                        // but flag it so the caller disables JSON mode for 24h.
                        resultText = ApiClientUtils.stripMarkdownFences(resultText)
                        return Result.success(GenerateResult(
                            resultText, structuredOutputFailed = true, truncated = isTruncated))
                    }

                    resultText = ApiClientUtils.stripMarkdownFences(resultText)
                    Result.success(GenerateResult(resultText, truncated = isTruncated))
                } else {
                    Result.failure(Exception("No choices found in response"))
                }
            } else if (responseCode == 400 || responseCode == 422) {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val apiMessage = ApiClientUtils.redactSecrets(
                    ApiClientUtils.extractApiErrorMessage(errorBody),
                    cleanKey
                )
                val detail = if (apiMessage.isNotEmpty()) apiMessage else "Bad request"
                // Keep the machine-readable reason: Groq puts json_validate_failed in
                // error.code, not error.message, and that code is the only thing separating
                // "the model could not produce valid JSON" (recoverable by dropping JSON mode)
                // from a genuine bad request. Never reaches the user — the service maps every
                // raw message onto a localized string.
                val providerCode = ApiClientUtils.extractApiErrorCode(errorBody)
                val withCode = if (providerCode.isNotEmpty()) "$detail [$providerCode]" else detail
                Result.failure(Exception("HTTP_${responseCode}: $withCode"))
            } else {
                mapHttpError(connection, responseCode, baseUrl, cleanKey)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mapException(e, cleanKey)
        } finally {
            completionHandle?.dispose()
            connection?.disconnect()
        }
    }
}
