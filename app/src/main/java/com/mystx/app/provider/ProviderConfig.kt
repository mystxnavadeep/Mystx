package com.mystx.app.provider

import com.mystx.app.model.BaiModels
import com.mystx.app.model.GeminiModels
import com.mystx.app.model.GroqModels
import com.mystx.app.model.OpenCodeModels
import com.mystx.app.model.PrefKeys
import com.mystx.app.model.ProviderType
import com.mystx.app.model.SarvamModels

enum class Transport { GEMINI_NATIVE, OPENAI_COMPAT }

interface ProviderConfig {
    val type: String
    val transport: Transport
    val modelPrefKey: String
    val defaultModel: String
    fun sanitizeModel(stored: String?): String
    fun resolveEndpoint(customEndpoint: String): String
    fun reasoningParams(model: String): Map<String, Any> = emptyMap()
    fun thinkingLevel(model: String): String? = null
    fun useJsonObjectMode(structuredOutputEnabled: Boolean): Boolean = false
    fun isConfigured(model: String, endpoint: String): Boolean = true
}

object GeminiConfig : ProviderConfig {
    override val type = ProviderType.GEMINI
    override val transport = Transport.GEMINI_NATIVE
    override val modelPrefKey = PrefKeys.GEMINI_MODEL
    override val defaultModel = GeminiModels.DEFAULT
    override fun sanitizeModel(stored: String?): String {
        val trimmed = stored?.trim()
        if (trimmed.isNullOrBlank() || trimmed == "gemini-2.5-flash-lite") return defaultModel
        return trimmed
    }
    override fun resolveEndpoint(customEndpoint: String): String = ""
    override fun thinkingLevel(model: String): String? = GeminiModels.thinkingLevel(model)
}

/** Sarvam AI V2 — OpenAI-compatible endpoint at https://api.sarvam.ai/v2. */
object SarvamConfig : ProviderConfig {
    const val ENDPOINT = "https://api.sarvam.ai/v2"

    override val type = ProviderType.SARVAM
    override val transport = Transport.OPENAI_COMPAT
    override val modelPrefKey = PrefKeys.SARVAM_MODEL
    override val defaultModel = SarvamModels.DEFAULT
    override fun sanitizeModel(stored: String?): String {
        val trimmed = stored?.trim()
        if (trimmed.isNullOrBlank()) return defaultModel
        return trimmed
    }
    override fun resolveEndpoint(customEndpoint: String): String = ENDPOINT
}

/** OpenCode Zen — OpenAI-compatible gateway at https://opencode.ai/zen/v1. */
object OpenCodeConfig : ProviderConfig {
    const val ENDPOINT = "https://opencode.ai/zen/v1"

    override val type = ProviderType.OPENCODE
    override val transport = Transport.OPENAI_COMPAT
    override val modelPrefKey = PrefKeys.OPENCODE_MODEL
    override val defaultModel = OpenCodeModels.DEFAULT
    override fun sanitizeModel(stored: String?): String {
        val trimmed = stored?.trim()
        if (trimmed.isNullOrBlank()) return defaultModel
        return trimmed
    }
    override fun resolveEndpoint(customEndpoint: String): String = ENDPOINT
}

/** Legacy Groq config kept for unit-test compatibility. */
object GroqConfig : ProviderConfig {
    const val ENDPOINT = "https://api.groq.com/openai/v1"

    override val type = ProviderType.GROQ
    override val transport = Transport.OPENAI_COMPAT
    override val modelPrefKey = PrefKeys.GROQ_MODEL
    override val defaultModel = GroqModels.DEFAULT
    override fun sanitizeModel(stored: String?): String {
        val trimmed = stored?.trim()
        if (trimmed.isNullOrBlank()) return defaultModel
        return trimmed
    }
    override fun resolveEndpoint(customEndpoint: String): String = ENDPOINT
    override fun reasoningParams(model: String): Map<String, Any> = GroqModels.reasoningParams(model)
    override fun useJsonObjectMode(structuredOutputEnabled: Boolean): Boolean = structuredOutputEnabled
}

object BaiConfig : ProviderConfig {
    const val ENDPOINT = "https://api.b.ai/v1"

    override val type = ProviderType.BAI
    override val transport = Transport.OPENAI_COMPAT
    override val modelPrefKey = PrefKeys.BAI_MODEL
    override val defaultModel = BaiModels.DEFAULT
    override fun sanitizeModel(stored: String?): String {
        val trimmed = stored?.trim()
        if (trimmed.isNullOrBlank()) return defaultModel
        return trimmed
    }
    override fun resolveEndpoint(customEndpoint: String): String = ENDPOINT
}

object CustomConfig : ProviderConfig {
    override val type = ProviderType.CUSTOM
    override val transport = Transport.OPENAI_COMPAT
    override val modelPrefKey = PrefKeys.CUSTOM_MODEL
    override val defaultModel = ""
    override fun sanitizeModel(stored: String?): String = stored?.trim() ?: ""
    override fun resolveEndpoint(customEndpoint: String): String = customEndpoint
    override fun isConfigured(model: String, endpoint: String): Boolean =
        model.isNotBlank() && endpoint.isNotBlank()
}

object Providers {
    fun forType(type: String?): ProviderConfig = when (ProviderType.sanitize(type)) {
        ProviderType.SARVAM -> SarvamConfig
        ProviderType.OPENCODE -> OpenCodeConfig
        ProviderType.GROQ -> GroqConfig
        ProviderType.BAI -> BaiConfig
        ProviderType.CUSTOM -> CustomConfig
        else -> GeminiConfig
    }
}
