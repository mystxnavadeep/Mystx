package com.mystx.app.service.discovery

import com.mystx.app.api.ApiClientUtils
import com.mystx.app.api.OpenAICompatibleClient
import com.mystx.app.model.ModelInfo
import com.mystx.app.model.ProviderType
import com.mystx.app.provider.EndpointValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Discovers available models for Custom / OpenAI-compatible endpoints by probing
 * common model endpoints (/v1/models, /models, /api/tags, /api/models).
 */
class OpenAICompatibleModelDiscovery(
    private val client: OpenAICompatibleClient = OpenAICompatibleClient()
) : ProviderModelDiscovery {

    override val providerType: String = ProviderType.CUSTOM

    override fun fallbackModels(): List<ModelInfo> = emptyList()

    override suspend fun discover(apiKey: String?, endpoint: String): Result<List<ModelInfo>> =
        withContext(Dispatchers.IO) {
            val cleanEndpoint = endpoint.trim()
            if (cleanEndpoint.isBlank()) {
                return@withContext Result.failure(Exception("Endpoint URL is required"))
            }
            if (EndpointValidator.validate(cleanEndpoint) != EndpointValidator.Error.NONE) {
                return@withContext Result.failure(Exception("Endpoint must be https:// or an http:// private-LAN address"))
            }

            val cleanKey = apiKey?.replace("\r", "")?.replace("\n", "")?.trim()?.takeIf { it.isNotBlank() }
            try {
                val result = client.fetchModels(cleanKey, cleanEndpoint)
                result.fold(
                    onSuccess = { ids ->
                        Result.success(ids.map { id ->
                            ModelInfo(id = id, displayName = id, provider = ProviderType.CUSTOM)
                        })
                    },
                    onFailure = { err ->
                        val safeMsg = ApiClientUtils.redactSecrets(err.message ?: "Model discovery failed", cleanKey)
                        Result.failure(Exception(safeMsg))
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val safeMsg = ApiClientUtils.redactSecrets(e.message ?: "Model discovery failed", cleanKey)
                Result.failure(Exception(safeMsg))
            }
        }
}
