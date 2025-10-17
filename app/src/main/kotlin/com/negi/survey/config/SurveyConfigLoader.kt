package com.negi.survey.config

import android.content.Context
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.negi.survey.vm.Node
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import java.io.File
import java.nio.charset.Charset

/**
 * Main configuration model for Survey definition
 */
@Serializable
data class SurveyConfig(
    val prompts: List<Prompt> = emptyList(),
    val graph: Graph,
    val slm: SlmMeta = SlmMeta()
) {
    @Serializable
    data class Prompt(val nodeId: String, val prompt: String)

    @Serializable
    data class Graph(val startId: String, val nodes: List<NodeDTO>)

    /**
     * Metadata and runtime parameters for SLM engine
     */
    @Serializable
    data class SlmMeta(
        @SerialName("accelerator") val accelerator: String? = null,
        @SerialName("max_tokens") val maxTokens: Int? = null,
        @SerialName("top_k") val topK: Int? = null,
        @SerialName("top_p") val topP: Double? = null,
        @SerialName("temperature") val temperature: Double? = null,
        @SerialName("user_turn_prefix") val user_turn_prefix: String? = null,
        @SerialName("model_turn_prefix") val model_turn_prefix: String? = null,
        @SerialName("turn_end") val turn_end: String? = null,
        @SerialName("empty_json_instruction") val empty_json_instruction: String? = null,
        @SerialName("preamble") val preamble: String? = null,
        @SerialName("key_contract") val key_contract: String? = null,
        @SerialName("length_budget") val length_budget: String? = null,
        @SerialName("scoring_rule") val scoring_rule: String? = null,
        @SerialName("strict_output") val strict_output: String? = null
    )

    /**
     * Validates consistency of the configuration graph and metadata
     */
    fun validate(): List<String> = buildList {
        val ids = graph.nodes.map { it.id }
        val idSet = ids.toSet()

        if (graph.startId.isBlank()) add("graph.startId is blank")
        else if (graph.startId !in idSet) add("graph.startId='${graph.startId}' not found in node ids: ${idSet.joinToString()}")

        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .takeIf { it.isNotEmpty() }?.let { add("duplicate node ids: ${it.joinToString()}") }

        prompts.map { it.nodeId }.filterNot { it in idSet }.distinct().takeIf { it.isNotEmpty() }?.let {
            add("prompts contain unknown nodeIds: ${it.joinToString()}")
        }

        prompts.groupingBy { it.nodeId }.eachCount().filterValues { it > 1 }.keys
            .takeIf { it.isNotEmpty() }?.let { add("multiple prompts defined for nodeIds: ${it.joinToString()}") }

        graph.nodes.forEach { node ->
            node.nextId?.takeIf { it.isNotBlank() }?.let { next ->
                if (next !in idSet) add("node '${node.id}' references unknown nextId='$next'")
            }
        }

        graph.nodes.filter { it.nodeType() == NodeType.AI && it.question.isBlank() }
            .forEach { add("AI node '${it.id}' has empty question") }

        graph.nodes.filter {
            (it.nodeType() == NodeType.SINGLE_CHOICE || it.nodeType() == NodeType.MULTI_CHOICE) && it.options.isEmpty()
        }.forEach {
            add("Choice node '${it.id}' must have at least one option")
        }

        graph.nodes.filter { it.title.isBlank() }.forEach {
            add("Node '${it.id}' has empty title")
        }

        graph.nodes.forEach { node ->
            val duplicates = node.options.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (duplicates.isNotEmpty()) {
                add("Node '${node.id}' has duplicate options: ${duplicates.joinToString()}")
            }
        }

        val reachable = mutableSetOf<String>()
        fun visit(id: String) {
            if (!reachable.add(id)) return
            val next = graph.nodes.find { it.id == id }?.nextId
            next?.let { visit(it) }
        }
        visit(graph.startId)
        graph.nodes.map { it.id }.filterNot { it in reachable }.forEach {
            add("Node '$it' is unreachable from startId '${graph.startId}'")
        }

        // SLM param checks
        slm.accelerator?.trim()?.uppercase()?.let {
            if (it != "CPU" && it != "GPU") add("slm.accelerator should be 'CPU' or 'GPU' (got '$it')")
        }
        slm.maxTokens?.takeIf { it <= 0 }?.let { add("slm.max_tokens must be > 0 (got $it)") }
        slm.topK?.takeIf { it < 0 }?.let { add("slm.top_k must be >= 0 (got $it)") }
        slm.topP?.takeIf { it !in 0.0..1.0 }?.let { add("slm.top_p must be in [0.0,1.0] (got $it)") }
        slm.temperature?.takeIf { it < 0.0 }?.let { add("slm.temperature must be >= 0.0 (got $it)") }
    }

    /**
     * Serialize prompts list to compact JSONL lines
     */
    fun toJsonl(): List<String> = SurveyConfigLoader.jsonCompact.let { j ->
        prompts.map { j.encodeToString(Prompt.serializer(), it) }
    }

    /**
     * Convert full config to JSON (pretty or compact)
     */
    fun toJson(pretty: Boolean = true): String =
        (if (pretty) SurveyConfigLoader.jsonPretty else SurveyConfigLoader.jsonCompact)
            .encodeToString(serializer(), this)

    /**
     * Convert full config to YAML
     */
    fun toYaml(strict: Boolean = false): String =
        SurveyConfigLoader.yaml(strict).encodeToString(serializer(), this)

    /**
     * Compose SLM prompt with only non-blank components
     */
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
}

typealias PromptEntry = SurveyConfig.Prompt
typealias GraphConfig = SurveyConfig.Graph

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
    fun toNode(): Node = Node(
        id = id,
        type = runCatching { com.negi.survey.vm.NodeType.valueOf(type.uppercase()) }.getOrElse { com.negi.survey.vm.NodeType.TEXT },
        title = title,
        question = question,
        options = options,
        nextId = nextId
    )
}

enum class NodeType {
    START, TEXT, SINGLE_CHOICE, MULTI_CHOICE, AI, REVIEW, DONE, UNKNOWN;
    companion object {
        fun from(raw: String?): NodeType = when (raw?.trim()?.uppercase()) {
            "START" -> START
            "TEXT" -> TEXT
            "SINGLE_CHOICE" -> SINGLE_CHOICE
            "MULTI_CHOICE" -> MULTI_CHOICE
            "AI" -> AI
            "REVIEW" -> REVIEW
            "DONE" -> DONE
            else -> UNKNOWN
        }
    }
}

enum class ConfigFormat { JSON, YAML, AUTO }

object SurveyConfigLoader {
    internal val jsonCompact: Json = Json { ignoreUnknownKeys = true; prettyPrint = false; isLenient = true }
    internal val jsonPretty: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }
    internal fun yaml(strict: Boolean = false): Yaml = Yaml(
        serializersModule = EmptySerializersModule(),
        configuration = YamlConfiguration(
            encodeDefaults = false,
            strictMode = strict
        )
    )
    fun fromAssets(context: Context, fileName: String, charset: Charset = Charsets.UTF_8, format: ConfigFormat = ConfigFormat.AUTO): SurveyConfig =
        context.assets.open(fileName).bufferedReader(charset).use { reader ->
            val text = reader.readText().normalize()
            fromString(text, format = pickFormat(format, fileName, text))
        }

    fun fromFile(path: String, charset: Charset = Charsets.UTF_8, format: ConfigFormat = ConfigFormat.AUTO): SurveyConfig =
        File(path).bufferedReader(charset).use { reader ->
            val text = reader.readText().normalize()
            fromString(text, format = pickFormat(format, File(path).name, text))
        }

    fun fromString(text: String, format: ConfigFormat = ConfigFormat.AUTO, fileNameHint: String? = null): SurveyConfig {
        val sanitized = text.normalize()
        return when (val chosen = pickFormat(format, fileNameHint, sanitized)) {
            ConfigFormat.JSON -> jsonCompact.decodeFromString(SurveyConfig.serializer(), sanitized)
            ConfigFormat.YAML -> yaml(false).decodeFromString(SurveyConfig.serializer(), sanitized)
            ConfigFormat.AUTO -> error("AUTO should have been resolved; this is a bug.")
        }
    }

    private fun pickFormat(desired: ConfigFormat, fileName: String? = null, text: String? = null): ConfigFormat {
        if (desired != ConfigFormat.AUTO) return desired
        val lower = fileName?.lowercase().orEmpty()
        if (lower.endsWith(".json")) return ConfigFormat.JSON
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return ConfigFormat.YAML
        return sniffFormat(text ?: "") ?: ConfigFormat.JSON
    }

    private fun sniffFormat(text: String): ConfigFormat? {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return ConfigFormat.JSON
        val firstNonEmpty = trimmed.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        if (firstNonEmpty.startsWith("---") || firstNonEmpty.startsWith("- ") || (":" in firstNonEmpty && !firstNonEmpty.startsWith("{"))) {
            return ConfigFormat.YAML
        }
        return ConfigFormat.JSON
    }

    private fun String.normalize(): String =
        this.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n").trimEnd('\n')

    private fun String.safePreview(max: Int = 200): String =
        this.replace("\n", "\\n").replace("\r", "\\r").let { if (it.length <= max) it else it.substring(0, max) + "…" }
}
