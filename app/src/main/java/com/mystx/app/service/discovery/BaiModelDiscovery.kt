package com.mystx.app.service.discovery

import com.mystx.app.api.ApiClientUtils
import com.mystx.app.model.BaiModels
import com.mystx.app.model.ModelInfo
import com.mystx.app.model.ProviderType
import com.mystx.app.provider.BaiConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Discovers available models for B.ai gateway via `https://api.b.ai/v1/models`.
 */
class BaiModelDiscovery : ProviderModelDiscovery {

    override val providerType: String = ProviderType.BAI

    override fun fallbackModels(): List<ModelInfo> =
        BaiModels.OPTIONS.map { (id, label) ->
            ModelInfo(id = id, displayName = label, provider = ProviderType.BAI)
        }

    override suspend fun discover(apiKey: String?, endpoint: String): Result<List<ModelInfo>> =
        withContext(Dispatchers.IO) {
            val key = apiKey?.replace("\r", "")?.replace("\n", "")?.trim()
            if (key.isNullOrBlank()) {
                return@withContext Result.failure(Exception("API key required for B.ai model discovery"))
            }

            var connection: HttpURLConnection? = null
            val completionHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) connection?.disconnect()
            }
            try {
                val url = "${BaiConfig.ENDPOINT}/models"
                connection = URL(url).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $key")
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000

                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    val body = ApiClientUtils.readResponseBounded(connection)
                    val models = parseBaiModels(body)
                    Result.success(if (models.isNotEmpty()) models else fallbackModels())
                } else {
                    val errorBody = ApiClientUtils.readErrorBody(connection)
                    val apiMessage = ApiClientUtils.redactSecrets(
                        ApiClientUtils.extractApiErrorMessage(errorBody),
                        key
                    )
                    val detail = if (apiMessage.isNotBlank()) apiMessage else "Error $responseCode"
                    Result.failure(Exception(detail))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val safeMsg = ApiClientUtils.redactSecrets(e.message ?: "Model discovery failed", key)
                Result.failure(Exception(safeMsg))
            } finally {
                completionHandle?.dispose()
                connection?.disconnect()
            }
        }

    internal fun parseBaiModels(json: String): List<ModelInfo> {
        val ids = ApiClientUtils.parseModelIds(json)
        if (ids.isEmpty()) return emptyList()
        return ids.map { id ->
            ModelInfo(id = id, displayName = BaiModels.label(id), provider = ProviderType.BAI)
        }
    }
}
