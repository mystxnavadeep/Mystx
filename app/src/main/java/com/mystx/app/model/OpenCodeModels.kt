package com.mystx.app.model

/**
 * Curated OpenCode Zen models (https://opencode.ai/zen/v1).
 */
object OpenCodeModels {

    private data class Spec(val id: String, val label: String)

    private val SPECS: List<Spec> = listOf(
        Spec("glm-4.7-free", "GLM 4.7 Free"),
        Spec("minimax-m2.1-free", "MiniMax M2.1 Free"),
        Spec("kimi-k2", "Kimi K2"),
        Spec("qwen3-coder", "Qwen 3 Coder"),
        Spec("claude-sonnet-4-5", "Claude Sonnet 4.5"),
        Spec("gpt-5.2", "GPT 5.2")
    )

    val DEFAULT: String = SPECS.first().id
    val ALL: List<String> = SPECS.map { it.id }
    val OPTIONS: List<Pair<String, String>> = SPECS.map { it.id to it.label }

    fun label(model: String): String = SPECS.firstOrNull { it.id == model }?.label ?: model
    fun sanitize(value: String?): String = value?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT
}
