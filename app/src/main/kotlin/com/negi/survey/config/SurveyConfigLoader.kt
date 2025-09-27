package com.negi.survey.config

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * JSON 形:
 * {
 *   "prompts": [ { "nodeId": "...", "prompt": "..." }, ... ],
 *   "graph": { "startId": "...", "nodes": [ ... ] }
 * }
 *
 * ViewModel/テスト互換のため:
 * - SurveyConfig.Prompt / SurveyConfig.Graph をネスト型として提供
 * - 互換 typealias: PromptEntry / GraphConfig
 */

@Serializable
data class SurveyConfig(
    val prompts: List<Prompt> = emptyList(),
    val graph: Graph
) {
    /** ネスト型（既存コード互換） */
    @Serializable
    data class Prompt(
        val nodeId: String,
        val prompt: String
    )

    @Serializable
    data class Graph(
        val startId: String,
        val nodes: List<NodeDTO>
    )

    /**
     * 整合性チェック
     * - startId の存在
     * - ノードID重複
     * - prompts の未知 nodeId
     * - nextId の存在
     * - AI ノードで question 空など、気づきやすい指摘
     * - 同一 nodeId への prompt 重複
     */
    fun validate(): List<String> {
        val issues = mutableListOf<String>()

        if (graph.startId.isBlank()) {
            issues += "graph.startId is blank"
        }

        val ids = graph.nodes.map { it.id }
        val idSet = ids.toSet()

        if (graph.startId !in idSet) {
            issues += "graph.startId='${graph.startId}' not found among node ids: ${idSet.joinToString(",")}"
        }

        // duplicate node ids
        val dupIds = ids.groupingBy { it }.eachCount().filterValues { it > 1 }
        if (dupIds.isNotEmpty()) {
            issues += "duplicate node ids: ${dupIds.keys.joinToString(",")}"
        }

        // prompts target unknown nodes
        val unknownInPrompts = prompts.map { it.nodeId }.filter { it !in idSet }.distinct()
        if (unknownInPrompts.isNotEmpty()) {
            issues += "prompts contain unknown nodeIds: ${unknownInPrompts.joinToString(",")}"
        }

        // duplicate prompts for same nodeId
        val dupPrompts = prompts.groupingBy { it.nodeId }.eachCount().filterValues { it > 1 }.keys
        if (dupPrompts.isNotEmpty()) {
            issues += "multiple prompts defined for nodeIds: ${dupPrompts.joinToString(",")}"
        }

        // nextId existence
        graph.nodes.forEach { n ->
            val next = n.nextId
            if (!next.isNullOrBlank() && next !in idSet) {
                issues += "node '${n.id}' references unknown nextId='${next}'"
            }
        }

        // AI node with empty question (多くの場合ミス)
        graph.nodes
            .filter { it.nodeType() == NodeType.AI && it.question.isBlank() }
            .forEach { issues += "AI node '${it.id}' has empty question" }

        return issues
    }

    /** 1 prompt = 1 行の JSONL 出力 */
    fun toJsonl(): List<String> {
        val json = Json { prettyPrint = false }
        return prompts.map { json.encodeToString(it) }
    }
}

/** 既存コード互換のためのエイリアス（どちらの呼び方でも使える） */
typealias PromptEntry = SurveyConfig.Prompt
typealias GraphConfig  = SurveyConfig.Graph

@Serializable
data class NodeDTO(
    val id: String,
    val type: String,
    val title: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val nextId: String? = null
) {
    fun nodeType(): NodeType = NodeType.from(type)
}

/**
 * ViewModel 側と整合する NodeType
 * - TEXT / SINGLE_CHOICE / MULTI_CHOICE を含む
 */
enum class NodeType {
    START, TEXT, SINGLE_CHOICE, MULTI_CHOICE, AI, REVIEW, DONE, UNKNOWN;

    companion object {
        fun from(raw: String?): NodeType = when (raw?.trim()?.uppercase()) {
            "START"         -> START
            "TEXT"          -> TEXT
            "SINGLE_CHOICE" -> SINGLE_CHOICE
            "MULTI_CHOICE"  -> MULTI_CHOICE
            "AI"            -> AI
            "REVIEW"        -> REVIEW
            "DONE"          -> DONE
            else            -> UNKNOWN
        }
    }
}

/** Loader utilities */
object SurveyConfigLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = true
    }

    fun fromAssets(context: Context, fileName: String = "survey_config.json"): SurveyConfig =
        try {
            val text = context.assets.open(fileName).bufferedReader().use { it.readText() }
            fromString(text)
        } catch (ex: Exception) {
            throw IllegalArgumentException("Failed to load SurveyConfig from assets/$fileName: ${ex.message}", ex)
        }

    fun fromFile(path: String): SurveyConfig =
        try {
            val f = File(path)
            require(f.exists()) { "Config file not found: $path" }
            val text = f.bufferedReader().use { it.readText() }
            fromString(text)
        } catch (ex: Exception) {
            throw IllegalArgumentException("Failed to load SurveyConfig from file '$path': ${ex.message}", ex)
        }

    fun fromString(jsonText: String): SurveyConfig =
        try {
            json.decodeFromString(SurveyConfig.serializer(), jsonText)
        } catch (ex: SerializationException) {
            throw IllegalArgumentException("JSON parsing error while parsing SurveyConfig: ${ex.message}", ex)
        } catch (ex: Exception) {
            throw IllegalArgumentException("Unexpected error while parsing SurveyConfig: ${ex.message}", ex)
        }
}
