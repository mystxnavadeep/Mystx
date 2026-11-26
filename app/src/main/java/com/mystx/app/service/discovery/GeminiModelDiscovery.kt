package com.mystx.app.service.discovery

import com.mystx.app.api.ApiClientUtils
import com.mystx.app.api.optStringOrNull
import com.mystx.app.model.GeminiModels
import com.mystx.app.model.ModelInfo
import com.mystx.app.model.ProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Discovers available models for Google Gemini via the Generative Language API.
 * Queries `https://generativelanguage.googleapis.com/v1beta/models` (following
 * pagination up to 5 pages) and filters to models that support `generateContent`.
 */
class GeminiModelDiscovery : ProviderModelDiscovery {

    companion object {
        private const val MAX_PAGES = 5
    }

    override val providerType: String = ProviderType.GEMINI

    override fun fallbackModels(): List<ModelInfo> =
        GeminiModels.OPTIONS.map { (id, label) ->
            ModelInfo(id = id, displayName = label, provider = ProviderType.GEMINI)
        }

    override suspend fun discover(apiKey: String?, endpoint: String): Result<List<ModelInfo>> =
        withContext(Dispatchers.IO) {
            val key = apiKey?.replace("\r", "")?.replace("\n", "")?.trim()
            if (key.isNullOrBlank()) {
                return@withContext Result.failure(Exception("API key required for Gemini model discovery"))
            }

            val allModels = mutableListOf<ModelInfo>()
            val seenIds = mutableSetOf<String>()
            var pageToken: String? = null

            try {
                for (page in 0 until MAX_PAGES) {
                    currentCoroutineContext().ensureActive()
                    var connection: HttpURLConnection? = null
                    val completionHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                        if (cause is CancellationException) connection?.disconnect()
                    }
                    try {
                        val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100"
                        val url = if (pageToken.isNullOrBlank()) {
                            baseUrl
                        } else {
                            "$baseUrl&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}"
                        }
                        connection = URL(url).openConnection() as HttpURLConnection
                        connection.instanceFollowRedirects = false
                        connection.requestMethod = "GET"
                        connection.setRequestProperty("x-goog-api-key", key)
                        connection.connectTimeout = 15_000
                        connection.readTimeout = 15_000

                        val responseCode = connection.responseCode
                        if (responseCode in 200..299) {
                            val body = ApiClientUtils.readResponseBounded(connection)
                            for (m in parseGeminiModels(body)) {
                                if (seenIds.add(m.id)) {
                                    allModels.add(m)
                                }
                            }
                            val nextToken = try {
                                JSONObject(body).optStringOrNull("nextPageToken")?.trim()
                            } catch (_: Exception) {
                                null
                            }
                            if (nextToken.isNullOrEmpty()) break
                            pageToken = nextToken
                        } else {
                            if (allModels.isNotEmpty()) break
                            val errorBody = ApiClientUtils.readErrorBody(connection)
                            val apiMessage = ApiClientUtils.redactSecrets(
                                ApiClientUtils.extractApiErrorMessage(errorBody),
                                key
                            )
                            val detail = if (apiMessage.isNotBlank()) apiMessage else "Error $responseCode"
                            return@withContext Result.failure(Exception(detail))
                        }
                    } finally {
                        completionHandle?.dispose()
                        connection?.disconnect()
                    }
                }
                Result.success(if (allModels.isNotEmpty()) allModels else fallbackModels())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (allModels.isNotEmpty()) {
                    Result.success(allModels)
                } else {
                    val safeMsg = ApiClientUtils.redactSecrets(e.message ?: "Model discovery failed", key)
                    Result.failure(Exception(safeMsg))
                }
            }
        }

    internal fun parseGeminiModels(json: String): List<ModelInfo> {
        if (json.isBlank()) return emptyList()
        val list = mutableListOf<ModelInfo>()
        val seen = mutableSetOf<String>()
        try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("models") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val rawName = obj.optStringOrNull("name")?.trim() ?: ""
                val id = rawName.removePrefix("models/").trim()
                if (id.isBlank() || !seen.add(id)) continue

                // Verify the model supports text generation
                val methods = obj.optJSONArray("supportedGenerationMethods")
                var supportsGenerateContent = false
                if (methods != null) {
                    for (j in 0 until methods.length()) {
                        if (!methods.isNull(j) && methods.optString(j) == "generateContent") {
                            supportsGenerateContent = true
                            break
                        }
                    }
                }
                // Skip embedding, vision-only, or non-generative models
                if (!supportsGenerateContent) continue

                val rawDisplayName = obj.optStringOrNull("displayName")?.trim() ?: ""
                val displayName = if (rawDisplayName.isNotBlank()) {
                    rawDisplayName
                } else {
                    GeminiModels.label(id)
                }

                list.add(ModelInfo(id = id, displayName = displayName, provider = ProviderType.GEMINI))
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return list
    }
}
