package com.negi.survey.config

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.negi.survey.vm.Node
import java.io.File
import java.nio.charset.Charset

@Serializable
data class SurveyConfig(
    val prompts: List<Prompt> = emptyList(),
    val graph: Graph,
    val slm: SlmMeta = SlmMeta() // 常に非nullで扱えるよう既定値
) {
    // ------------ prompts ------------
    @Serializable
    data class Prompt(
        val nodeId: String,
        val prompt: String
    )

    // ------------ graph ------------
    @Serializable
    data class Graph(
        val startId: String,
        val nodes: List<NodeDTO>
    )

    /**
     * SLM runtime parameters + system-prompt metadata (all optional).
     * すべての snake_case に SerialName を明示してkamlの名前解決を安定化
     */
    @Serializable
    data class SlmMeta(
        // --- runtime params (optional)
        @SerialName("accelerator") val accelerator: String? = null, // "CPU"/"GPU"
        @SerialName("max_tokens")  val maxTokens: Int? = null,
        @SerialName("top_k")       val topK: Int? = null,
        @SerialName("top_p")       val topP: Double? = null,
        @SerialName("temperature") val temperature: Double? = null,

        // --- meta/system prompt pieces (optional)
        @SerialName("user_turn_prefix")      val user_turn_prefix: String? = null,
        @SerialName("model_turn_prefix")     val model_turn_prefix: String? = null,
        @SerialName("turn_end")              val turn_end: String? = null,
        @SerialName("empty_json_instruction")val empty_json_instruction: String? = null,
        @SerialName("preamble")              val preamble: String? = null,
        @SerialName("key_contract")          val key_contract: String? = null,
        @SerialName("length_budget")         val length_budget: String? = null,
        @SerialName("scoring_rule")          val scoring_rule: String? = null,
        @SerialName("strict_output")         val strict_output: String? = null
    )

    /** 構造検証 */
    fun validate(): List<String> {
        val issues = mutableListOf<String>()

        val ids = graph.nodes.map { it.id }
        val idSet = ids.toSet()

        if (graph.startId.isBlank()) {
            issues += "graph.startId is blank"
        } else if (graph.startId !in idSet) {
            issues += "graph.startId='${graph.startId}' not found in node ids: ${idSet.joinToString(",")}"
        }

        val duplicateIds = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) issues += "duplicate node ids: ${duplicateIds.joinToString(",")}"

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

        graph.nodes.forEach { node ->
            node.nextId?.takeIf { it.isNotBlank() }?.let { next ->
                if (next !in idSet) {
                    issues += "node '${node.id}' references unknown nextId='${next}'"
                }
            }
        }

        graph.nodes.asSequence()
            .filter { it.nodeType() == NodeType.AI && it.question.isBlank() }
            .forEach { issues += "AI node '${it.id}' has empty question" }

        // SLM param sanity (optional)
        slm.accelerator?.let { acc ->
            val a = acc.trim().uppercase()
            if (a != "CPU" && a != "GPU") issues += "slm.accelerator should be 'CPU' or 'GPU' (got '$acc')"
        }
        slm.maxTokens?.let { if (it <= 0) issues += "slm.max_tokens must be > 0 (got $it)" }
        slm.topK?.let { if (it < 0) issues += "slm.top_k must be >= 0 (got $it)" }
        slm.topP?.let { if (it !in 0.0..1.0) issues += "slm.top_p must be in [0.0,1.0] (got $it)" }
        slm.temperature?.let { if (it < 0.0) issues += "slm.temperature must be >= 0.0 (got $it)" }

        return issues
    }

    fun toJsonl(): List<String> =
        SurveyConfigLoader.jsonCompact.let { j -> prompts.map { j.encodeToString(Prompt.serializer(), it) } }

    fun toJson(pretty: Boolean = true): String =
        (if (pretty) SurveyConfigLoader.jsonPretty else SurveyConfigLoader.jsonCompact)
            .encodeToString(serializer(), this)

    fun toYaml(strict: Boolean = false): String =
        SurveyConfigLoader.yaml(strict).encodeToString(serializer(), this)

    /** SLMシステムプロンプト組み立て（非空のみ連結） */
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

/** Backward-compatible aliases */
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

enum class ConfigFormat { JSON, YAML, AUTO }

object SurveyConfigLoader {

    internal val jsonCompact: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = true
    }
    internal val jsonPretty: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
    }

    // strict=false かつ encodeDefaults=false（既定値は省略してOK）
    internal fun yaml(strict: Boolean = false): Yaml =
        Yaml(
            configuration = YamlConfiguration(
                encodeDefaults = false,
                strictMode = strict   // デコード側は fromString で常に false を渡す
            )
        )

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
        val preview = text.safePreview()
        throw IllegalArgumentException(
            "Parsing error (format=${format.name}). First 200 chars: $preview :: ${ex.message}",
            ex
        )
    } catch (ex: Exception) {
        val preview = text.safePreview()
        throw IllegalArgumentException(
            "Unexpected error while parsing SurveyConfig. First 200 chars: $preview :: ${ex.message}",
            ex
        )
    }

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
        return sniff ?: ConfigFormat.JSON
    }

    private fun sniffFormat(text: String): ConfigFormat {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return ConfigFormat.JSON
        val firstNonEmpty = trimmed.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        if (firstNonEmpty.startsWith("---")) return ConfigFormat.YAML
        if (firstNonEmpty.startsWith("- ")) return ConfigFormat.YAML
        if (":" in firstNonEmpty && !firstNonEmpty.startsWith("{")) return ConfigFormat.YAML
        return ConfigFormat.JSON
    }

    private fun String.normalize(): String =
        this.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trimEnd('\n')

    private fun String.safePreview(max: Int = 200): String =
        this.replace("\n", "\\n")
            .replace("\r", "\\r")
            .let { if (it.length <= max) it else it.substring(0, max) + "…" }
}
