@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.config

import android.content.Context
import androidx.annotation.RawRes

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.negi.survey.slm.ConfigKey
import com.negi.survey.vm.Node
import com.negi.survey.vm.NodeType

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

import java.io.File
import java.io.IOException
import java.nio.charset.Charset

/**
 * Root configuration model for a survey specification.
 *
 * - Supports JSON and YAML via kotlinx.serialization and KAML.
 * - Keeps SLM (small language model) runtime params and system-prompt metadata under [SlmMeta].
 * - Also allows UI/runtime defaults for model download via [ModelDefaults] under top-level key "model_defaults".
 */
@Serializable
data class SurveyConfig(
    val prompts: List<Prompt> = emptyList(),
    val graph: Graph,
    val slm: SlmMeta = SlmMeta(), // Always non-null by default

    // NEW: Optional, loaded from YAML/JSON key "model_defaults"
    @SerialName("model_defaults")
    val modelDefaults: ModelDefaults? = null
) {
    // ------------------------------------------------------------------------
    // prompts
    // ------------------------------------------------------------------------
    @Serializable
    data class Prompt(val nodeId: String, val prompt: String)

    // ------------------------------------------------------------------------
    // graph
    // ------------------------------------------------------------------------
    @Serializable
    data class Graph(val startId: String, val nodes: List<NodeDTO>)

    /**
     * SLM runtime parameters + system-prompt metadata (all optional).
     * Snake_case keys are stabilized with @SerialName.
     */
    @Serializable
    data class SlmMeta(
        // --- runtime params (optional)
        @SerialName("accelerator") val accelerator: String? = null, // "CPU" / "GPU"
        @SerialName("max_tokens")  val maxTokens: Int? = null,
        @SerialName("top_k")       val topK: Int? = null,
        @SerialName("top_p")       val topP: Double? = null,
        @SerialName("temperature") val temperature: Double? = null,

        // --- system prompt pieces (optional)
        @SerialName("user_turn_prefix")       val user_turn_prefix: String? = null,
        @SerialName("model_turn_prefix")      val model_turn_prefix: String? = null,
        @SerialName("turn_end")               val turn_end: String? = null,
        @SerialName("empty_json_instruction") val empty_json_instruction: String? = null,
        @SerialName("preamble")               val preamble: String? = null,
        @SerialName("key_contract")           val key_contract: String? = null,
        @SerialName("length_budget")          val length_budget: String? = null,
        @SerialName("scoring_rule")           val scoring_rule: String? = null,
        @SerialName("strict_output")          val strict_output: String? = null
    )

    // ------------------------------------------------------------------------
    // NEW: Model download UI/runtime defaults (optional on disk; safe fallbacks here)
    // ------------------------------------------------------------------------
    @Serializable
    data class ModelDefaults(
        @SerialName("default_model_url")
        val defaultModelUrl: String =
            "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",
        @SerialName("default_file_name")
        val defaultFileName: String = "model.litertlm",
        @SerialName("timeout_ms")
        val timeoutMs: Long = 30L * 60 * 1000,
        @SerialName("ui_throttle_ms")
        val uiThrottleMs: Long = 250L,
        @SerialName("ui_min_delta_bytes")
        val uiMinDeltaBytes: Long = 1L * 1024L * 1024L
    )

    /** Convenience: always return non-null defaults (disk value or safe fallback). */
    fun modelDefaultsOrFallback(): ModelDefaults = modelDefaults ?: ModelDefaults()

    // ------------------------------------------------------------------------
    // Validation & Utilities
    // ------------------------------------------------------------------------

    /**
     * Validate structural consistency of this configuration.
     * Returns a list of human-readable issues; empty list means no issues found.
     */
    fun validate(): List<String> {
        val issues = mutableListOf<String>()

        val ids = graph.nodes.map { it.id }
        val idSet = ids.toSet()

        // startId existence and membership
        if (graph.startId.isBlank()) {
            issues += "graph.startId is blank"
        } else if (graph.startId !in idSet) {
            issues += "graph.startId='${graph.startId}' not found in node ids: ${idSet.joinToString(",")}"
        }

        // duplicate node ids
        val duplicateIds = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            issues += "duplicate node ids: ${duplicateIds.joinToString(",")}"
        }

        // prompts should refer to existing nodes and be unique per nodeId
        val unknownPromptTargets = prompts.asSequence()
            .map { it.nodeId }.filter { it !in idSet }.distinct().toList()
        if (unknownPromptTargets.isNotEmpty()) {
            issues += "prompts contain unknown nodeIds: ${unknownPromptTargets.joinToString(",")}"
        }

        val duplicatePromptTargets = prompts.groupingBy { it.nodeId }.eachCount()
            .filterValues { it > 1 }.keys
        if (duplicatePromptTargets.isNotEmpty()) {
            issues += "multiple prompts defined for nodeIds: ${duplicatePromptTargets.joinToString(",")}"
        }

        // nextId references must exist when present
        graph.nodes.forEach { node ->
            node.nextId?.takeIf { it.isNotBlank() }?.let { next ->
                if (next !in idSet) {
                    issues += "node '${node.id}' references unknown nextId='${next}'"
                }
            }
        }

        // simple content checks
        graph.nodes.asSequence()
            .filter { it.nodeType() == NodeType.AI && it.question.isBlank() }
            .forEach { issues += "AI node '${it.id}' has empty question" }

        // sanity checks for choice nodes (optional but helpful)
        graph.nodes.asSequence()
            .filter { it.nodeType() == NodeType.SINGLE_CHOICE || it.nodeType() == NodeType.MULTI_CHOICE }
            .filter { it.options.isEmpty() }
            .forEach { issues += "Choice node '${it.id}' has empty options" }

        // SLM param sanity (all optional, validate only if present)
        slm.accelerator?.let { acc ->
            val a = acc.trim().uppercase()
            if (a != "CPU" && a != "GPU") {
                issues += "slm.accelerator should be 'CPU' or 'GPU' (got '$acc')"
            }
        }
        slm.maxTokens?.let { if (it <= 0) issues += "slm.max_tokens must be > 0 (got $it)" }
        slm.topK?.let { if (it < 0) issues += "slm.top_k must be >= 0 (got $it)" }
        slm.topP?.let { if (it !in 0.0..1.0) issues += "slm.top_p must be in [0.0,1.0] (got $it)" }
        slm.temperature?.let { if (it < 0.0) issues += "slm.temperature must be >= 0.0 (got $it)" }

        return issues
    }

    /** Throw an IllegalStateException when validate() finds issues. */
    fun validateOrThrow() {
        val problems = validate()
        if (problems.isNotEmpty()) {
            throw IllegalStateException("SurveyConfig validation failed:\n - " + problems.joinToString("\n - "))
        }
    }

    /** Convert prompts to JSON Lines (one Prompt per line, compact). */
    fun toJsonl(): List<String> =
        SurveyConfigLoader.jsonCompact.let { j -> prompts.map { j.encodeToString(Prompt.serializer(), it) } }

    /** Serialize entire config to JSON (pretty or compact). */
    fun toJson(pretty: Boolean = true): String =
        (if (pretty) SurveyConfigLoader.jsonPretty else SurveyConfigLoader.jsonCompact)
            .encodeToString(serializer(), this)

    /** Serialize entire config to YAML. */
    fun toYaml(strict: Boolean = false): String =
        SurveyConfigLoader.yaml(strict).encodeToString(serializer(), this)

    /** Save to file in the selected format. */
    fun toFile(path: String, format: ConfigFormat = ConfigFormat.JSON, pretty: Boolean = true) {
        val text = when (format) {
            ConfigFormat.JSON -> toJson(pretty)
            ConfigFormat.YAML -> toYaml(strict = false)
            ConfigFormat.AUTO -> toJson(pretty) // AUTO makes little sense for writing; default JSON
        }
        runCatching {
            val f = File(path)
            f.parentFile?.mkdirs()
            f.writeText(text, Charsets.UTF_8)
        }.getOrElse { e ->
            throw IOException("Failed to write SurveyConfig to '$path': ${e.message}", e)
        }
    }

    /** Compose the SLM system prompt by concatenating non-empty fields. */
    fun composeSystemPrompt(): String {
        fun String?.addTo(sb: StringBuilder) {
            if (!this.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.appendLine()
                sb.append(this)
            }
        }
        return buildString {
            slm.preamble.addTo(this)
            slm.key_contract.addTo(this)
            slm.length_budget.addTo(this)
            slm.scoring_rule.addTo(this)
            slm.strict_output.addTo(this)
            slm.empty_json_instruction.addTo(this)
        }
    }

    /** Find a prompt text for a given nodeId, or null if not defined. */
    fun promptFor(nodeId: String): String? =
        prompts.firstOrNull { it.nodeId == nodeId }?.prompt
}

fun SurveyConfig.toSlmMpConfigMap(): Map<ConfigKey, Any> {
    val s = this.slm
    val acc = (s.accelerator ?: "GPU").uppercase()
    return buildMap {
        put(ConfigKey.ACCELERATOR, acc)
        put(ConfigKey.MAX_TOKENS, s.maxTokens ?: 8192)
        put(ConfigKey.TOP_K, s.topK ?: 50)
        put(ConfigKey.TOP_P, s.topP ?: 0.95)
        put(ConfigKey.TEMPERATURE, s.temperature ?: 0.7)
    }
}

// ------------------------------------------------------------------------
// Backward-compatible aliases
// ------------------------------------------------------------------------
typealias PromptEntry = SurveyConfig.Prompt
typealias GraphConfig  = SurveyConfig.Graph

// ------------------------------------------------------------------------
// Runtime node model and DTO
// ------------------------------------------------------------------------

/** Wire-format (serialized) node. */
@Serializable
data class NodeDTO(
    val id: String,
    val type: String,
    val title: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val nextId: String? = null
) {
    /** Convert the string type to [NodeType] using tolerant mapping. */
    fun nodeType(): NodeType = NodeType.from(type)

    /** Optional: map to runtime Node if needed. */
    fun toNode(): Node = Node(
        id = id,
        type = nodeType(),
        title = title,
        question = question,
        options = options,
        nextId = nextId
    )
}

/** Supported input formats, AUTO will sniff by filename or content. */
enum class ConfigFormat { JSON, YAML, AUTO }

// ------------------------------------------------------------------------
// Loader (assets / raw resource / file path / raw string) with robust errors
// ------------------------------------------------------------------------

object SurveyConfigLoader {

    // Compact JSON (ignore unknown keys; lenient for minor formatting inconsistencies).
    internal val jsonCompact: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = true
    }

    // Pretty JSON for debugging.
    internal val jsonPretty: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
    }

    /**
     * YAML encoder/decoder instance.
     * - encodeDefaults=false keeps output clean (omits default values).
     * - strictMode toggles strict key checking (encoder/decoder).
     *
     * Note: For decoding we generally call yaml(strict = false) to be permissive.
     */
    internal fun yaml(strict: Boolean = false): Yaml =
        Yaml(
            configuration = YamlConfiguration(
                encodeDefaults = false,
                strictMode = strict
            )
        )

    // ---------- Sources ----------

    /** Load config from res/raw. Will AUTO-detect format by resource name or content. */
    fun fromRawResource(
        context: Context,
        @RawRes resId: Int,
        charset: Charset = Charsets.UTF_8,
        format: ConfigFormat = ConfigFormat.AUTO
    ): SurveyConfig = try {
        val name = runCatching { context.resources.getResourceEntryName(resId) }.getOrNull()
        context.resources.openRawResource(resId).bufferedReader(charset).use { reader ->
            val text = reader.readText().normalize()
            fromString(text, format = pickFormat(format, name, text))
        }
    } catch (ex: Exception) {
        throw IllegalArgumentException(
            "Failed to load SurveyConfig from raw resource (id=$resId): ${ex.message}",
            ex
        )
    }

    /** Load config from assets. Will AUTO-detect format by filename or content. */
    fun fromAssets(
        context: Context,
        fileName: String,
        charset: Charset = Charsets.UTF_8,
        format: ConfigFormat = ConfigFormat.AUTO
    ): SurveyConfig = try {
        context.assets.open(fileName).bufferedReader(charset).use { reader ->
            val text = reader.readText().normalize()
            fromString(text, format = pickFormat(format, fileName, text))
        }
    } catch (ex: Exception) {
        throw IllegalArgumentException(
            "Failed to load SurveyConfig from assets/$fileName: ${ex.message}",
            ex
        )
    }

    /** Load config from a file path. Will AUTO-detect format. */
    fun fromFile(
        path: String,
        charset: Charset = Charsets.UTF_8,
        format: ConfigFormat = ConfigFormat.AUTO
    ): SurveyConfig = try {
        val file = File(path)
        require(file.exists()) { "Config file not found: $path" }
        file.bufferedReader(charset).use { reader ->
            val text = reader.readText().normalize()
            fromString(text, format = pickFormat(format, file.name, text))
        }
    } catch (ex: Exception) {
        throw IllegalArgumentException(
            "Failed to load SurveyConfig from file '$path': ${ex.message}",
            ex
        )
    }

    /** Parse config from a raw string. If format=AUTO, detect from filename hint or content. */
    fun fromString(
        text: String,
        format: ConfigFormat = ConfigFormat.AUTO,
        fileNameHint: String? = null
    ): SurveyConfig = try {
        val sanitized = text.normalize()
        val chosen = pickFormat(format, fileNameHint, sanitized)
        when (chosen) {
            ConfigFormat.JSON -> jsonCompact.decodeFromString(SurveyConfig.serializer(), sanitized)
            ConfigFormat.YAML -> yaml(strict = false).decodeFromString(SurveyConfig.serializer(), sanitized)
            ConfigFormat.AUTO -> error("AUTO should have been resolved; this is a bug.")
        }
    } catch (ex: SerializationException) {
        val where = ex.message.orEmpty()
        val preview = text.safePreview()
        throw IllegalArgumentException(
            "Parsing error (format=${format.name}). First 200 chars: $preview :: $where",
            ex
        )
    } catch (ex: YamlException) {
        // Include line/column context from KAML
        val where = "YAML at ${ex.path}:${ex.line}:${ex.column}"
        val preview = text.safePreview()
        throw IllegalArgumentException(
            "Parsing error ($where). First 200 chars: $preview :: ${ex.message}",
            ex
        )
    } catch (ex: Exception) {
        val preview = text.safePreview()
        throw IllegalArgumentException(
            "Unexpected error while parsing SurveyConfig. First 200 chars: $preview :: ${ex.message}",
            ex
        )
    }

    // ---------- Helpers ----------

    /** Decide the final format if AUTO was requested. */
    private fun pickFormat(
        desired: ConfigFormat,
        fileName: String? = null,
        text: String? = null
    ): ConfigFormat {
        if (desired != ConfigFormat.AUTO) return desired
        val lower = fileName?.lowercase().orEmpty()
        if (lower.endsWith(".json")) return ConfigFormat.JSON
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return ConfigFormat.YAML
        val sniff = text?.let(::sniffFormat)
        return sniff ?: ConfigFormat.JSON // default to JSON if unsure
    }

    /** Very lightweight content-based sniffing between JSON and YAML. */
    private fun sniffFormat(text: String): ConfigFormat {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return ConfigFormat.JSON
        val firstNonEmpty = trimmed.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        if (firstNonEmpty.startsWith("---")) return ConfigFormat.YAML
        if (firstNonEmpty.startsWith("- ")) return ConfigFormat.YAML
        if (":" in firstNonEmpty && !firstNonEmpty.startsWith("{")) return ConfigFormat.YAML
        return ConfigFormat.JSON
    }

    /** Normalize common text issues: strip BOM, unify newlines, trim trailing newlines. */
    private fun String.normalize(): String =
        this.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trimEnd('\n')

    /** Safe preview for error messages (no newlines, max length). */
    private fun String.safePreview(max: Int = 200): String =
        this.replace("\n", "\\n")
            .replace("\r", "\\r")
            .let { if (it.length <= max) it else it.substring(0, max) + "…" }
}
