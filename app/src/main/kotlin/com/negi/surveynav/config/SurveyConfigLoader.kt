package com.negi.surveynav.config

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Survey configuration that matches the provided JSON shape:
 * {
 *   "prompts": [ { "nodeId": "...", "prompt": "..." }, ... ],
 *   "graph": { "startId": "...", "nodes": [ ... ] }
 * }
 *
 * No PromptConfig (template-based) here: explicit per-node prompts only.
 */

@Serializable
data class SurveyConfig(
    val prompts: List<PromptEntry>,
    val graph: GraphConfig
) {
    /** Basic integrity checks */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (graph.startId.isBlank()) {
            errors += "graph.startId is blank"
        }

        val ids = graph.nodes.map { it.id }.toSet()
        if (graph.startId !in ids) {
            errors += "graph.startId='${graph.startId}' not found among node ids: ${ids.joinToString(",")}"
        }

        val dupIds = graph.nodes.groupingBy { it.id }.eachCount().filterValues { it > 1 }
        if (dupIds.isNotEmpty()) {
            errors += "duplicate node ids: ${dupIds.keys.joinToString(",")}"
        }

        // prompts must target existing nodes
        val unknownInPrompts = prompts.map { it.nodeId }.filter { it !in ids }
        if (unknownInPrompts.isNotEmpty()) {
            errors += "prompts contain unknown nodeIds: ${unknownInPrompts.joinToString(",")}"
        }

        // optional: warn if AI node has empty question (often unintended)
        graph.nodes.filter { it.nodeType() == NodeType.AI && it.question.isBlank() }
            .forEach { errors += "AI node '${it.id}' has empty question" }

        return errors
    }

    /** Encode to JSONL lines: one line per prompt { "nodeId": "...", "prompt": "..." } */
    fun toJsonl(): List<String> {
        val json = Json { prettyPrint = false }
        return prompts.map { json.encodeToString(it) }
    }
}

@Serializable
data class PromptEntry(
    val nodeId: String,
    val prompt: String
)

@Serializable
data class GraphConfig(
    val startId: String,
    val nodes: List<NodeDTO>
)

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

enum class NodeType {
    START, AI, REVIEW, DONE, UNKNOWN;

    companion object {
        fun from(raw: String?): NodeType = when (raw?.trim()?.uppercase()) {
            "START" -> START
            "AI"    -> AI
            "REVIEW"-> REVIEW
            "DONE"  -> DONE
            else    -> UNKNOWN
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
