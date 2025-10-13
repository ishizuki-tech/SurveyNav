@file:Suppress("unused")

package com.negi.survey.slm

import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession

/**
 * 生成ハイパラのプリセット定義（TopK / TopP / Temperature / MaxTokens）
 * 用途別に安定挙動を得やすい実戦値を設定。
 */
enum class PresetType {
    QA_SAFE,        // 対話・Q&A：自然さと安定のバランス
    CODE_STRICT,    // コード/厳密系：決定性を高める
    CREATIVE_HIGH,  // クリエイティブ生成：多様性重視
    SUMMARIZE,      // 要約：安定しつつ圧縮
    LOW_LATENCY     // 低遅延：モバイル向け高速応答
}

data class GenParams(
    val topK: Int,
    val topP: Float,
    val temperature: Float,
    val maxTokens: Int
)

object SlmPresets {
    // 実務で扱いやすいデフォルト群
    val defaults: Map<PresetType, GenParams> = mapOf(
        PresetType.QA_SAFE to GenParams(
            topK = 40, topP = 0.90f, temperature = 0.7f, maxTokens = 512
        ),
        PresetType.CODE_STRICT to GenParams(
            topK = 8, topP = 0.85f, temperature = 0.2f, maxTokens = 256
        ),
        PresetType.CREATIVE_HIGH to GenParams(
            topK = 80, topP = 0.95f, temperature = 1.0f, maxTokens = 512
        ),
        PresetType.SUMMARIZE to GenParams(
            topK = 32, topP = 0.90f, temperature = 0.4f, maxTokens = 256
        ),
        PresetType.LOW_LATENCY to GenParams(
            topK = 16, topP = 0.85f, temperature = 0.6f, maxTokens = 128
        )
    )

    /** プリセットから MediaPipe の SessionOptions を生成 */
    fun sessionOptions(preset: PresetType): LlmInferenceSession.LlmInferenceSessionOptions {
        val g = defaults.getValue(preset)
        return LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(g.topK)
            .setTopP(g.topP)
            .setTemperature(g.temperature)
            .build()
    }

    fun applyTo(
        builder: LlmInferenceSession.LlmInferenceSessionOptions.Builder,
        preset: PresetType
    ): LlmInferenceSession.LlmInferenceSessionOptions.Builder {
        val g = defaults.getValue(preset)
        return builder
            .setTopK(g.topK)
            .setTopP(g.topP)
            .setTemperature(g.temperature)
    }

    fun asConfigMap(preset: PresetType): Map<ConfigKey, Any> {
        val g = defaults.getValue(preset)
        return mapOf(
            ConfigKey.TOP_K to g.topK,
            ConfigKey.TOP_P to g.topP,
            ConfigKey.TEMPERATURE to g.temperature,
            ConfigKey.MAX_TOKENS to g.maxTokens
        )
    }
}

/** 使い勝手向上：Builder拡張 */
fun LlmInferenceSession.LlmInferenceSessionOptions.Builder.applyParams(
    p: GenParams
): LlmInferenceSession.LlmInferenceSessionOptions.Builder = this
    .setTopK(p.topK)
    .setTopP(p.topP)
    .setTemperature(p.temperature)
