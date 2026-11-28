package com.mystx.app.model

/**
 * Curated Sarvam AI V2 models (https://api.sarvam.ai/v2).
 */
object SarvamModels {

    private data class Spec(val id: String, val label: String)

    private val SPECS: List<Spec> = listOf(
        Spec("sarvam-105b-conversations", "Sarvam 105B Fast"),
        Spec("sarvam-105b", "Sarvam 105B Flagship"),
        Spec("glm5.3-flash", "GLM 5.3 Flash"),
        Spec("gemma4", "Gemma 4"),
        Spec("deepseekv4.1-flash-1017", "DeepSeek V4.1 Flash")
    )

    val DEFAULT: String = SPECS.first().id
    val ALL: List<String> = SPECS.map { it.id }
    val OPTIONS: List<Pair<String, String>> = SPECS.map { it.id to it.label }

    fun label(model: String): String = SPECS.firstOrNull { it.id == model }?.label ?: model
    fun sanitize(value: String?): String = value?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT
}
