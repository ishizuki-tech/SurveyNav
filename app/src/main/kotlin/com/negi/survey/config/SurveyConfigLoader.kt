package com.negi.survey.config

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.Charset

/**
 * JSON schema (example)
 * {
 *   "prompts": [ { "nodeId": "...", "prompt": "..." }, ... ],
 *   "graph": { "startId": "...", "nodes": [ ... ] }
 * }
 *
 * Design notes for ViewModel / tests:
 * - Keep nested types (SurveyConfig.Prompt / SurveyConfig.Graph) for source compatibility.
 * - Provide typealiases (PromptEntry / GraphConfig) for flexible call sites.
 */

@Serializable
data class SurveyConfig(
    val prompts: List<Prompt> = emptyList(),
    val graph: Graph
) {
    /**
     * A single prompt mapped to a specific node.
     * Keeping this as a nested type to avoid import churn in callers.
     */
    @Serializable
    data class Prompt(
        val nodeId: String,
        val prompt: String
    )

    /**
     * Graph entry point and node list.
     * `startId` must correspond to an existing node id in `nodes`.
     */
    @Serializable
    data class Graph(
        val startId: String,
        val nodes: List<NodeDTO>
    )

    /**
     * Validate structural consistency of the configuration.
     *
     * Checks performed:
     * - `startId` is not blank and exists in `nodes`.
     * - Node id duplication.
     * - `prompts` referencing unknown node ids.
     * - Multiple prompts for the same node id.
     * - `nextId` (if present) references an existing node id.
     * - AI node with empty `question` (common authoring mistake).
     *
     * Returns:
     * - A list of human-readable issue messages; empty list means "valid".
     */
    fun validate(): List<String> {
        val issues = mutableListOf<String>()

        // Fast path: build id set once
        val ids = graph.nodes.map { it.id }
        val idSet = ids.toSet()

        // startId sanity
        if (graph.startId.isBlank()) {
            issues += "graph.startId is blank"
        } else if (graph.startId !in idSet) {
            issues += "graph.startId='${graph.startId}' not found in node ids: ${idSet.joinToString(",")}"
        }

        // Duplicate node ids
        val duplicateIds = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            issues += "duplicate node ids: ${duplicateIds.joinToString(",")}"
        }

        // Prompts → unknown nodes
        val unknownPromptTargets = prompts.asSequence()
            .map { it.nodeId }
            .filter { it !in idSet }
            .distinct()
            .toList()
        if (unknownPromptTargets.isNotEmpty()) {
            issues += "prompts contain unknown nodeIds: ${unknownPromptTargets.joinToString(",")}"
        }

        // Multiple prompts for a single node id
        val duplicatePromptTargets = prompts.groupingBy { it.nodeId }.eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicatePromptTargets.isNotEmpty()) {
            issues += "multiple prompts defined for nodeIds: ${duplicatePromptTargets.joinToString(",")}"
        }

        // nextId existence check
        graph.nodes.forEach { node ->
            node.nextId?.takeIf { it.isNotBlank() }?.let { next ->
                if (next !in idSet) {
                    issues += "node '${node.id}' references unknown nextId='${next}'"
                }
            }
        }

        // AI nodes must present a non-empty question (helps authoring quality)
        graph.nodes
            .asSequence()
            .filter { it.nodeType() == NodeType.AI && it.question.isBlank() }
            .forEach { issues += "AI node '${it.id}' has empty question" }

        return issues
    }

    /**
     * Export prompts as JSON Lines (one JSON object per line).
     * This is convenient for training data or bulk processing pipelines.
     */
    fun toJsonl(): List<String> {
        val json = Json { prettyPrint = false }
        return prompts.map { json.encodeToString(it) }
    }
}

/** Backward-compatible aliases to help migration without noisy imports. */
typealias PromptEntry = SurveyConfig.Prompt
typealias GraphConfig  = SurveyConfig.Graph

/**
 * Node DTO used by serialization and graph evaluation.
 * `type` is kept as raw String for compatibility with external JSON writers.
 */
@Serializable
data class NodeDTO(
    val id: String,
    val type: String,
    val title: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val nextId: String? = null
) {
    /** Converts raw `type` string to a typed enum with safe fallback. */
    fun nodeType(): NodeType = NodeType.from(type)
}

/**
 * Node types aligned with the ViewModel side.
 * Include TEXT / SINGLE_CHOICE / MULTI_CHOICE and authoring-related markers.
 */
enum class NodeType {
    START, TEXT, SINGLE_CHOICE, MULTI_CHOICE, AI, REVIEW, DONE, UNKNOWN;

    companion object {
        /**
         * Map a raw type string to enum value. Unknown strings are mapped to UNKNOWN.
         * The mapping is case-insensitive and tolerant of whitespace.
         */
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

/**
 * Utilities to load SurveyConfig from assets, file, or raw JSON string.
 * Errors are wrapped as IllegalArgumentException with clear context for logging or UI surfacing.
 */
object SurveyConfigLoader {

    // Centralized JSON instance with consistent decoding behavior.
    // - ignoreUnknownKeys: forward-compatible with new server fields
    // - isLenient: tolerate minor formatting quirks
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = true
    }

    /**
     * Load from Android assets. The default file name is "survey_config.json".
     * Throws IllegalArgumentException with detailed cause on failure.
     */
    fun fromAssets(
        context: Context,
        fileName: String = "survey_config.json",
        charset: Charset = Charsets.UTF_8
    ): SurveyConfig = try {
        context.assets.open(fileName).bufferedReader(charset).use { reader ->
            fromString(reader.readText())
        }
    } catch (ex: Exception) {
        throw IllegalArgumentException(
            "Failed to load SurveyConfig from assets/$fileName: ${ex.message}",
            ex
        )
    }

    /**
     * Load from an absolute or app-internal file path.
     * Validates file existence before reading to produce a clearer error message.
     */
    fun fromFile(
        path: String,
        charset: Charset = Charsets.UTF_8
    ): SurveyConfig = try {
        val file = File(path)
        require(file.exists()) { "Config file not found: $path" }
        file.bufferedReader(charset).use { reader ->
            fromString(reader.readText())
        }
    } catch (ex: Exception) {
        throw IllegalArgumentException(
            "Failed to load SurveyConfig from file '$path': ${ex.message}",
            ex
        )
    }

    /**
     * Parse from raw JSON string into a SurveyConfig instance.
     * Wraps serialization issues with a user-friendly message for logs/UI.
     */
    fun fromString(jsonText: String): SurveyConfig = try {
        json.decodeFromString(SurveyConfig.serializer(), jsonText)
    } catch (ex: SerializationException) {
        throw IllegalArgumentException(
            "JSON parsing error while parsing SurveyConfig: ${ex.message}",
            ex
        )
    } catch (ex: Exception) {
        throw IllegalArgumentException(
            "Unexpected error while parsing SurveyConfig: ${ex.message}",
            ex
        )
    }
}
